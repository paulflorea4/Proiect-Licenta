package com.gradingplatform.backend.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.dto.TestCaseRequest;
import com.gradingplatform.backend.service.SubmissionService;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 5.1f: the filter on its own. The order and the answers over real HTTP are in `RealHttpRequestBodyLimitTests`. */
class RequestBodyLimitFilterTests {

    private static final int MAX = RequestBodyLimitFilter.MAX_BODY_BYTES;

    /** The most a JSON string can take per Java character: {@code \}{@code uXXXX}. */
    private static final int ESCAPED = 6;

    /** Room for the keys, the numbers and the punctuation of any of our bodies. */
    private static final int STRUCTURE = 4096;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(new JsonErrorWriter(mapper));

    /** A request whose declared length is {@code declared} (-1 = unknown, i.e. chunked). */
    private static MockHttpServletRequest request(long declared, byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anything") {
            @Override
            public long getContentLengthLong() {
                return declared;
            }

            @Override
            public int getContentLength() {
                return (int) declared;
            }
        };
        request.setContentType("application/json");
        request.setContent(content);
        return request;
    }

    /** A request that fails the test if anyone reads its body. */
    private static MockHttpServletRequest unreadable(long declared, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anything") {
            @Override
            public long getContentLengthLong() {
                return declared;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read");
            }
        };
        request.setContentType(contentType);
        return request;
    }

    private static byte[] bytes(int length) {
        byte[] content = new byte[length];
        Arrays.fill(content, (byte) 'x');
        return content;
    }

    private void assertRefused(MockHttpServletResponse response, MockFilterChain chain) throws Exception {
        assertThat(chain.getRequest()).as("the request went on").isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.isCommitted()).as("flushed").isTrue();
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(mapper.readValue(response.getContentAsString(), Map.class))
                .containsEntry("status", 413)
                .containsEntry("code", "REQUEST_TOO_LARGE")
                .hasSize(3);
    }

    @Test
    void aDeclaredLengthOverTheMaximumIsRefusedWithoutReadingTheBody() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(unreadable(MAX + 1L, "application/json"), response, chain);

        assertRefused(response, chain);
    }

    @Test
    void aDeclaredLengthWithinTheMaximumGoesOnUntouched() throws Exception {
        for (long declared : new long[] {0, 1, MAX}) {
            HttpServletRequest request = unreadable(declared, "application/json");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).as("declared %d", declared).isSameAs(request);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void aBodyOfUnknownLengthOverTheMaximumIsRefused() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request(-1, bytes(MAX + 1)), response, chain);

        assertRefused(response, chain);
    }

    @Test
    void aBodyOfUnknownLengthWithinTheMaximumIsHandedOnByteForByte() throws Exception {
        for (byte[] body : new byte[][] {new byte[0], "{\"sourceCode\":\"é ✓\"}".getBytes(UTF_8), bytes(MAX)}) {
            var chain = new MockFilterChain();

            filter.doFilter(request(-1, body), new MockHttpServletResponse(), chain);

            var passed = (HttpServletRequest) chain.getRequest();
            assertThat(passed).isNotNull();
            assertThat(passed.getContentLengthLong()).isEqualTo(body.length);
            assertThat(passed.getContentLength()).isEqualTo(body.length);
            assertThat(passed.getInputStream().readAllBytes()).isEqualTo(body);
        }
    }

    @Test
    void aBufferedBodyCanAlsoBeReadAsTextInTheRequestsEncoding() throws Exception {
        String json = "{\"sourceCode\":\"é ✓\"}";
        MockHttpServletRequest request = request(-1, json.getBytes(UTF_8));
        request.setCharacterEncoding("UTF-8");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ServletInputStream stream = chain.getRequest().getInputStream();
        assertThat(stream.isFinished()).isFalse();
        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
        assertThat(chain.getRequest().getReader().readLine()).isEqualTo(json);
    }

    @Test
    void multipartIsLeftToItsOwnBound() throws Exception {
        for (String type : new String[] {"multipart/form-data; boundary=x", "Multipart/Mixed"}) {
            HttpServletRequest request = unreadable(MAX + 1L, type);
            var chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(type).isSameAs(request);
        }
    }

    @Test
    void theMaximumStaysAboveTheLargestValidBodyEvenWithEveryCharacterEscaped() {
        int testCase = TestCaseRequest.NAME_MAX + 2 * TestCaseRequest.TEXT_MAX;
        int assignment = AssignmentRequest.TITLE_MAX
                + AssignmentRequest.DESCRIPTION_MAX
                + AssignmentRequest.LANGUAGE_MAX
                + AssignmentRequest.STARTER_CODE_MAX;
        int course = CourseRequest.TITLE_MAX + CourseRequest.DESCRIPTION_MAX;
        // Source code is capped in UTF-8 bytes, and every character takes at least one.
        int submission = SubmissionService.SOURCE_MAX_BYTES;

        for (int characters : new int[] {testCase, assignment, course, submission}) {
            assertThat((long) MAX).isGreaterThan((long) characters * ESCAPED + STRUCTURE);
        }
    }
}
