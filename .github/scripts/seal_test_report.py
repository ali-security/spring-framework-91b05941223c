#!/usr/bin/env python3
"""Summarize Gradle JUnit XML results and give every skipped test its reason.

Gradle prints nothing for passing tests and writes skipped tests without a
reason, so this prints per-module counts, a TOTAL line, and one line per
(class, reason) group of skipped tests, the reason being resolved from the
test source (method annotations, assumptions in the body, class annotations,
assumptions in setup methods, then the superclass chain).
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict, defaultdict

LEG = sys.argv[1] if len(sys.argv) > 1 else ""

SKIP_ANN = re.compile(
    r"@(Disabled\w*|Enabled\w*|Ignore|IfProfileValue|EnabledForTestGroups|DisabledOnJre|EnabledOnJre|"
    r"DisabledOnOs|EnabledOnOs|DisabledIf\w*|EnabledIf\w*|Timed)\b(\([^)]*\))?"
)
ASSUME = re.compile(r"\b(assume\w*|Assumptions\.\w+|Assume\.\w+|assumingThat)\s*\(")
SETUP = re.compile(r"@(BeforeEach|BeforeAll|Before|BeforeClass)\b")
SRC_ROOTS = None


def src_roots():
    global SRC_ROOTS
    if SRC_ROOTS is None:
        SRC_ROOTS = [d for d in glob.glob("*/src/*/java") + glob.glob("*/src/*/kotlin") if os.path.isdir(d)]
        SRC_ROOTS += [d for d in glob.glob("src/*/java") if os.path.isdir(d)]
    return SRC_ROOTS


def find_source(fqcn):
    outer = fqcn.split("$")[0]
    rel = outer.replace(".", "/")
    for root in src_roots():
        for ext in (".java", ".kt"):
            p = os.path.join(root, rel + ext)
            if os.path.isfile(p):
                return p
    return None


def find_simple(simple, pkg, text):
    m = re.search(r"^import\s+([\w.]+\." + re.escape(simple) + r")\s*;?\s*$", text, re.M)
    if m:
        return m.group(1)
    return (pkg + "." + simple) if pkg else simple


def read(p):
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def is_comment(line):
    s = line.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def annotations_above(lines, idx):
    out = []
    j = idx - 1
    while j >= 0:
        s = lines[j].strip()
        if not s or is_comment(lines[j]) or s.startswith("@") or s.endswith(")") or s.endswith(",") or s.startswith("\""):
            if s.startswith("@") or (not is_comment(lines[j]) and s):
                out.append(s)
            j -= 1
            continue
        break
    joined = " ".join(reversed(out))
    return joined


def ann_reason(text):
    hits = []
    for m in SKIP_ANN.finditer(text):
        hits.append(m.group(0))
    return "; ".join(hits)


def method_body(lines, idx):
    depth = 0
    started = False
    body = []
    for k in range(idx, min(len(lines), idx + 400)):
        line = lines[k]
        body.append(line)
        depth += line.count("{") - line.count("}")
        if "{" in line:
            started = True
        if started and depth <= 0:
            break
    return "\n".join(body)


def assume_reason(body):
    flat = re.sub(r"\s+", " ", body)
    hits = []
    for m in ASSUME.finditer(flat):
        start = m.start()
        depth = 0
        end = start
        for k in range(m.end() - 1, min(len(flat), m.end() + 300)):
            if flat[k] == "(":
                depth += 1
            elif flat[k] == ")":
                depth -= 1
                if depth == 0:
                    end = k + 1
                    break
        hits.append(flat[start:end] if end > start else flat[start:start + 120])
    return "; ".join(hits[:2])


def class_decl_index(lines, simple):
    pat = re.compile(r"^\s*(public|protected|private|abstract|final|static|internal|open|data|\s)*\s*(class|interface|object)\s+" + re.escape(simple) + r"\b")
    for i, line in enumerate(lines):
        if is_comment(line):
            continue
        if pat.search(line):
            return i
    return None


def resolve(fqcn, method, depth=0):
    if depth > 6:
        return None
    path = find_source(fqcn)
    if not path:
        return None
    text = read(path)
    lines = text.splitlines()
    pkgm = re.search(r"^package\s+([\w.]+)", text, re.M)
    pkg = pkgm.group(1) if pkgm else ""
    simple = fqcn.split("$")[-1].split(".")[-1]

    # 1. method annotations, 2. assumptions in method body
    if method:
        mpat = re.compile(r"(void|fun|\w+>?)\s+`?" + re.escape(method) + r"`?\s*\(")
        for i, line in enumerate(lines):
            if is_comment(line):
                continue
            if mpat.search(line):
                r = ann_reason(annotations_above(lines, i))
                if r:
                    return r
                r = assume_reason(method_body(lines, i))
                if r:
                    return r
                break

    # 3. class annotations (the declaring/nested class, then the outer class)
    for name in [simple, fqcn.split("$")[0].split(".")[-1]]:
        ci = class_decl_index(lines, name)
        if ci is not None:
            r = ann_reason(annotations_above(lines, ci))
            if r:
                return "class " + name + " " + r

    # 4. assumptions in setup methods
    for i, line in enumerate(lines):
        if SETUP.search(line) and not is_comment(line):
            for k in range(i + 1, min(len(lines), i + 6)):
                if "(" in lines[k] and not lines[k].strip().startswith("@"):
                    r = assume_reason(method_body(lines, k))
                    if r:
                        return "setup " + r
                    break

    # 5. superclass chain
    ci = class_decl_index(lines, simple)
    if ci is not None:
        decl = " ".join(lines[ci:ci + 3])
        m = re.search(r"\bextends\s+([\w.]+)", decl) or re.search(r"\)\s*:\s*([\w.]+)\s*\(", decl)
        if m:
            sup = m.group(1).split("<")[0]
            sup_fq = sup if "." in sup else find_simple(sup, pkg, text)
            r = resolve(sup_fq, method, depth + 1)
            if r:
                return r + " (inherited from " + sup + ")"

    # 6. parameterized invocations reported only as "[n]" carry no method name:
    #    report the assumptions the class makes about its parameters.
    if depth == 0 or not method:
        hits = []
        for h in assume_reason_all(text):
            if h not in hits:
                hits.append(h)
        if hits:
            return "parameterized invocation aborted by an assumption in " + simple + ": " + "; ".join(hits)
    return None


def assume_reason_all(text):
    flat = re.sub(r"\s+", " ", "\n".join(l for l in text.splitlines() if not is_comment(l) and not l.strip().startswith("import ")))
    out = []
    for m in ASSUME.finditer(flat):
        depth = 0
        for k in range(m.end() - 1, min(len(flat), m.end() + 300)):
            if flat[k] == "(":
                depth += 1
            elif flat[k] == ")":
                depth -= 1
                if depth == 0:
                    out.append(flat[m.start():k + 1])
                    break
    return out


def clean_name(name):
    n = re.sub(r"\[[^\]]*\]$", "", name).strip()
    n = re.sub(r"\(.*\)$", "", n).strip()
    n = n.strip("`").strip()
    if " " in n:
        n = n.split(" ")[0]
    return n


def main():
    files = sorted(set(glob.glob("*/build/test-results/**/TEST-*.xml", recursive=True)))
    per_module = OrderedDict()
    skips = OrderedDict()
    failures = []
    for f in files:
        module = f.split(os.sep)[0]
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            print("WARN: cannot parse %s: %s" % (f, e))
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            agg = per_module.setdefault(module, defaultdict(int))
            for tc in s.findall("testcase"):
                agg["tests"] += 1
                cls = tc.get("classname", "")
                name = tc.get("name", "")
                if tc.find("failure") is not None or tc.find("error") is not None:
                    agg["failed"] += 1
                    failures.append("%s.%s" % (cls, name))
                elif tc.find("skipped") is not None:
                    agg["skipped"] += 1
                    msg = (tc.find("skipped").get("message") or "").strip()
                    reason = msg or resolve(cls, clean_name(name)) or "skipped at runtime by the JUnit engine (no static condition in source)"
                    skips.setdefault((cls, reason), []).append(name)
                else:
                    agg["passed"] += 1
    print("=" * 78)
    print("SEAL TEST REPORT [%s] from %d JUnit XML files" % (LEG, len(files)))
    tot = defaultdict(int)
    for module, a in per_module.items():
        print("TEST SUMMARY [%s]: tests=%d passed=%d failed=%d skipped=%d" % (module, a["tests"], a["passed"], a["failed"], a["skipped"]))
        for k in ("tests", "passed", "failed", "skipped"):
            tot[k] += a[k]
    if skips:
        print("SKIPPED TESTS (grouped by class and reason):")
        for (cls, reason), names in skips.items():
            short = ", ".join((clean_name(n) or n) for n in names[:3]) + (" ..." if len(names) > 3 else "")
            print("  SKIPPED %d x %s [%s]: %s" % (len(names), cls, short, reason))
    if failures:
        print("FAILED TESTS:")
        for n in failures:
            print("  FAILED " + n)
    print("TOTAL [%s]: tests=%d passed=%d failed=%d skipped=%d" % (LEG, tot["tests"], tot["passed"], tot["failed"], tot["skipped"]))
    print("=" * 78)


if __name__ == "__main__":
    main()
