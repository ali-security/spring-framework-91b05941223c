/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.servlet.function;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.testfixture.servlet.MockHttpServletRequest;
import org.springframework.web.testfixture.servlet.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Arjen Poutsma
 */
class SseServerResponseTests {

	private MockHttpServletRequest mockRequest;

	private MockHttpServletResponse mockResponse;

	@BeforeEach
	void setUp() {
		this.mockRequest = new MockHttpServletRequest("GET", "https://example.com");
		this.mockRequest.setAsyncSupported(true);
		this.mockResponse = new MockHttpServletResponse();
	}

	@Test
	void sendString() throws Exception {
		String body = "foo bar";
		ServerResponse response = ServerResponse.sse(sse -> {
			try {
				sse.send(body);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});

		ServerResponse.Context context = Collections::emptyList;

		ModelAndView mav = response.writeTo(this.mockRequest, this.mockResponse, context);
		assertThat(mav).isNull();

		String expected = "data:" + body + "\n\n";
		assertThat(this.mockResponse.getContentAsString()).isEqualTo(expected);
	}

	@Test
	void sendObject() throws Exception {
		Person person = new Person("John Doe", 42);
		ServerResponse response = ServerResponse.sse(sse -> {
			try {
				sse.send(person);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});

		ServerResponse.Context context = () -> Collections.singletonList(new MappingJackson2HttpMessageConverter());

		ModelAndView mav = response.writeTo(this.mockRequest, this.mockResponse, context);
		assertThat(mav).isNull();

		String expected = "data:{\"name\":\"John Doe\",\"age\":42}\n\n";
		assertThat(this.mockResponse.getContentAsString()).isEqualTo(expected);
	}

	@Test
	void builder() throws Exception {
		ServerResponse response = ServerResponse.sse(sse -> {
			try {
				sse.id("id")
						.event("name")
						.comment("comment line 1\ncomment line 2")
						.retry(Duration.ofSeconds(1))
						.data("data");
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});

		ServerResponse.Context context = Collections::emptyList;

		ModelAndView mav = response.writeTo(this.mockRequest, this.mockResponse, context);
		assertThat(mav).isNull();

		String expected = "id:id\n" +
				"event:name\n" +
				":comment line 1\n" +
				":comment line 2\n" +
				"retry:1000\n" +
				"data:data\n\n";
		assertThat(this.mockResponse.getContentAsString()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void sendMultiLineString(String newLineChars, String description) throws Exception {
		String body = "foo" + newLineChars + "event:injected" + newLineChars + newLineChars + "data:evil";
		ServerResponse response = ServerResponse.sse(sse -> {
			try {
				sse.send(body);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});

		ServerResponse.Context context = Collections::emptyList;

		ModelAndView mav = response.writeTo(this.mockRequest, this.mockResponse, context);
		assertThat(mav).isNull();

		assertThat(this.mockResponse.getContentAsString())
				.isEqualTo("data:foo\ndata:event:injected\ndata:\ndata:data:evil\n\n");
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void supportMultiLineComments(String newLineChars, String description) throws Exception {
		ServerResponse response = ServerResponse.sse(sse -> {
			try {
				sse.comment("foo" + newLineChars + "bar" + newLineChars + "baz").data("payload");
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});

		ServerResponse.Context context = Collections::emptyList;

		ModelAndView mav = response.writeTo(this.mockRequest, this.mockResponse, context);
		assertThat(mav).isNull();

		assertThat(this.mockResponse.getContentAsString()).isEqualTo(":foo\n:bar\n:baz\ndata:payload\n\n");
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void rejectInvalidId(String newLineChars, String description) throws Exception {
		ServerResponse response = ServerResponse.sse(sse -> sse.id("first" + newLineChars + "second"));
		ServerResponse.Context context = Collections::emptyList;

		assertThatThrownBy(() -> response.writeTo(this.mockRequest, this.mockResponse, context))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("illegal character");
		assertThat(this.mockResponse.getContentAsString()).isEmpty();
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("newLineCharacters")
	void rejectInvalidEvent(String newLineChars, String description) throws Exception {
		ServerResponse response = ServerResponse.sse(sse -> sse.event("first" + newLineChars + "second"));
		ServerResponse.Context context = Collections::emptyList;

		assertThatThrownBy(() -> response.writeTo(this.mockRequest, this.mockResponse, context))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("illegal character");
		assertThat(this.mockResponse.getContentAsString()).isEmpty();
	}

	private static Stream<Arguments> newLineCharacters() {
		return Stream.of(
				Arguments.of("\n", "LF"),
				Arguments.of("\r", "CR"),
				Arguments.of("\r\n", "CRLF")
		);
	}


	private static final class Person {

		private final String name;

		private final int age;


		public Person(String name, int age) {
			this.name = name;
			this.age = age;
		}

		@SuppressWarnings("unused")
		public String getName() {
			return this.name;
		}

		@SuppressWarnings("unused")
		public int getAge() {
			return this.age;
		}
	}

}
