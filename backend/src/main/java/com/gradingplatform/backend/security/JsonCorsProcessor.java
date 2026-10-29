package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import java.io.IOException;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Spring's CORS check, unchanged, except for its refusal (8.0b): a request from an origin, or with
 * a method or header, that the CORS list does not allow is the standard 403 {@code ACCESS_DENIED}
 * body (2.6a) instead of Spring's plain text {@code Invalid CORS request}. The {@code Vary} headers
 * the check adds before refusing are on the servlet response already, so they stay.
 */
class JsonCorsProcessor extends DefaultCorsProcessor {

    private final JsonErrorWriter writer;

    JsonCorsProcessor(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        writer.write(((ServletServerHttpResponse) response).getServletResponse(), ErrorCode.ACCESS_DENIED);
    }
}
