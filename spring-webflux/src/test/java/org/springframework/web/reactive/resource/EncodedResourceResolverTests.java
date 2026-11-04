/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.resource.GzipSupport.GzippedFiles;
import org.springframework.web.testfixture.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.testfixture.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EncodedResourceResolver}.
 *
 * @author Rossen Stoyanchev
 */
@ExtendWith(GzipSupport.class)
public class EncodedResourceResolverTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);


	private ResourceResolverChain resolver;

	private List<Resource> locations;


	@BeforeEach
	public void setup() {
		Cache cache = new ConcurrentMapCache("resourceCache");

		VersionResourceResolver versionResolver = new VersionResourceResolver();
		versionResolver.setStrategyMap(Collections.singletonMap("/**", new ContentVersionStrategy()));

		List<ResourceResolver> resolvers = new ArrayList<>();
		resolvers.add(new CachingResourceResolver(cache));
		resolvers.add(new EncodedResourceResolver());
		resolvers.add(versionResolver);
		resolvers.add(new PathResourceResolver());
		this.resolver = new DefaultResourceResolverChain(resolvers);

		this.locations = new ArrayList<>();
		this.locations.add(new ClassPathResource("test/", getClass()));
		this.locations.add(new ClassPathResource("testalternatepath/", getClass()));
	}


	@Test
	public void resolveGzipped(GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(actual.getFilename()).isEqualTo(getResource(file).getFilename());

		boolean condition = actual instanceof HttpResource;
		assertThat(condition).isTrue();
		HttpHeaders headers = ((HttpResource) actual).getResponseHeaders();
		assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
		assertThat(headers.getFirst(HttpHeaders.VARY)).isEqualTo("Accept-Encoding");
	}

	@Test
	public void resolveGzippedWithVersion(GzippedFiles gzippedFiles) {

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));
		gzippedFiles.create("foo.css");
		String file = "foo-e36d2e05253c6c7085a91522ce43a0b4.css";
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource("foo.css.gz").getDescription());
		assertThat(actual.getFilename()).isEqualTo(getResource("foo.css").getFilename());
		boolean condition = actual instanceof HttpResource;
		assertThat(condition).isTrue();
	}

	@Test
	public void resolveFromCacheWithEncodingVariants(GzippedFiles gzippedFiles) {

		// 1. Resolve, and cache .gz variant

		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "gzip"));

		String file = "js/foo.js";
		gzippedFiles.create(file);
		Resource resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
		boolean condition = resolved instanceof HttpResource;
		assertThat(condition).isTrue();

		// 2. Resolve unencoded resource

		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/js/foo.js"));
		resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
		boolean condition1 = resolved instanceof HttpResource;
		assertThat(condition1).isFalse();
	}

	@Test  // SPR-13149
	public void resolveWithNullRequest() {

		String file = "js/foo.js";
		Resource resolved = this.resolver.resolveResource(null, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved.getFilename()).isEqualTo(getResource(file).getFilename());
	}

	@Test  // CVE-2026-22741
	public void resolveGzippedWithQualityValue(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		assertGzippedResolved(file, "gzip;q=1.0");
	}

	@Test  // CVE-2026-22741
	public void resolveGzippedWithUpperCaseCoding(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		assertGzippedResolved(file, "GZIP");
	}

	@Test  // CVE-2026-22741
	public void resolveGzippedWithMultipleCodings(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		assertGzippedResolved(file, "deflate, gzip;q=0.9, *;q=0.1");
	}

	@Test  // CVE-2026-22741
	public void doNotResolveGzippedWhenCodingIsOnlyASubstringOfAcceptEncoding(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		// "gzip" appears in the header, but is not actually an accepted content coding
		assertPlainResolved(file, "x-gzip-foo");
		assertPlainResolved(file, "identity;q=1, notgzip");
		assertPlainResolved(file, "gzipped");
		assertPlainResolved(file, "foo-gzip;q=1");
	}

	@Test  // CVE-2026-22741
	public void doNotResolveGzippedForBlankAcceptEncoding(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		assertPlainResolved(file, "   ");
		assertPlainResolved(file, ",,");
	}

	@Test  // CVE-2026-22741
	public void substringMatchMustNotPoisonThePlainCacheEntry(GzippedFiles gzippedFiles) {
		String file = "js/foo.js";
		gzippedFiles.create(file);

		// 1. Crafted header that merely contains "gzip" as a substring. The caching resolver
		// treats it as "no content coding", so serving the gzipped variant here would store it
		// under the plain cache key.
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/js/foo.js").header("Accept-Encoding", "x-gzip-foo"));
		Resource resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved).isNotInstanceOf(HttpResource.class);

		// 2. A client that sends no Accept-Encoding at all must never receive the gzipped variant
		exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/js/foo.js"));
		resolved = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(resolved.getDescription()).isEqualTo(getResource(file).getDescription());
		assertThat(resolved).isNotInstanceOf(HttpResource.class);
	}

	@Test  // CVE-2026-22741
	public void parseAcceptEncodingTokenizesHeader() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("")
				.header("Accept-Encoding", " GZIP ; q=1.0 , deflate,  Br;q=0.5 ,, "));

		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange))
				.containsExactly("gzip", "deflate", "br");
	}

	@Test  // CVE-2026-22741
	public void parseAcceptEncodingWithNoHeader() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(""));

		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange)).isEmpty();
	}

	@Test  // CVE-2026-22741
	public void parseAcceptEncodingWithBlankHeader() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "   "));

		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange)).isEmpty();
	}

	@Test  // CVE-2026-22741
	public void parseAcceptEncodingDoesNotMatchSubstrings() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("").header("Accept-Encoding", "x-gzip-foo, hybrid, notgzip;q=1"));

		assertThat(EncodedResourceResolver.parseAcceptEncoding(exchange))
				.containsExactly("x-gzip-foo", "hybrid", "notgzip")
				.doesNotContain("gzip", "br");
	}

	private void assertGzippedResolved(String file, String acceptEncoding) {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/" + file).header("Accept-Encoding", acceptEncoding));
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription()).isEqualTo(getResource(file + ".gz").getDescription());
		assertThat(actual.getFilename()).isEqualTo(getResource(file).getFilename());
		assertThat(actual).isInstanceOf(HttpResource.class);
		HttpHeaders headers = ((HttpResource) actual).getResponseHeaders();
		assertThat(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
	}

	private void assertPlainResolved(String file, String acceptEncoding) {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/" + file).header("Accept-Encoding", acceptEncoding));
		Resource actual = this.resolver.resolveResource(exchange, file, this.locations).block(TIMEOUT);

		assertThat(actual.getDescription())
				.as("Accept-Encoding \"%s\" must not select the gzipped variant", acceptEncoding)
				.isEqualTo(getResource(file).getDescription());
		assertThat(actual).isNotInstanceOf(HttpResource.class);
	}

	private Resource getResource(String filePath) {
		return new ClassPathResource("test/" + filePath, getClass());
	}

}
