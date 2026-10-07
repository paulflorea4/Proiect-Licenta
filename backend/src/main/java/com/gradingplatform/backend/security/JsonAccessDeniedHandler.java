package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** A signed-in caller was refused by a rule of the security chain itself: 403 in the standard error shape. */
class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonErrorWriter writer;

    JsonAccessDeniedHandler(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(response, ErrorCode.ACCESS_DENIED);
    }
}
