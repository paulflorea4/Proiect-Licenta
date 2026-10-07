package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import com.gradingplatform.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link ErrorResponse} straight to the servlet response, for the errors the security
 * filters answer before Spring MVC (and its exception handler) is involved.
 */
class JsonErrorWriter {

    private final JsonMapper mapper;

    JsonErrorWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(ErrorResponse.of(code)));
    }
}
