package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request body over {@link #MAX_BODY_BYTES} with 413 {@code REQUEST_TOO_LARGE} before
 * anything reads it (5.1f): without it Jackson holds a whole JSON body in memory before any field
 * cap ({@code SubmissionService.SOURCE_MAX_BYTES}, the test case texts) is checked. It runs ahead
 * of the security chain, so an anonymous caller is bounded too.
 *
 * <p>A declared {@code Content-Length} over the maximum is refused without reading a byte; the
 * container never delivers more than the declared length. A body of unknown length (chunked) is
 * read up to the maximum plus one byte, refused if it goes over, and otherwise handed on from
 * memory. Multipart bodies are left alone: {@code spring.servlet.multipart} bounds them while they
 * are parsed, with their own answer (5.1b).
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    /**
     * Placeholder pending the human's tuning. It must stay above the largest valid body: a test
     * case carries two texts of {@code TestCaseRequest.TEXT_MAX} characters, and a client may
     * escape every character as {@code \}{@code uXXXX} (6 bytes), about 1.2 MB in all.
     */
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final JsonErrorWriter writer;

    RequestBodyLimitFilter(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            refuse(response);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            refuse(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    /**
     * The unread rest of the body stays on the connection, so it cannot be reused: say so, and
     * flush the answer before returning. Without the flush, Tomcat, which will not swallow more
     * than 2 MB of leftover body, closes the connection before the buffered 413 was sent, and the
     * client sees an aborted connection instead of an answer.
     */
    private void refuse(HttpServletResponse response) throws IOException {
        response.setHeader("Connection", "close");
        writer.write(response, ErrorCode.REQUEST_TOO_LARGE);
        response.flushBuffer();
    }

    /** The same test Spring's multipart resolver makes before it parses a request as multipart. */
    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    /** The request with its (bounded) body already read into memory. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body has already been read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
