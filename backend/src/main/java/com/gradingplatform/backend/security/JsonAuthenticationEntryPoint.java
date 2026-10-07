package com.gradingplatform.backend.security;

import com.gradingplatform.backend.dto.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * An anonymous caller (no token, or one that does not verify) reached a protected path: 401 in the
 * standard error shape. No redirect to a login page and no {@code WWW-Authenticate} challenge
 * (2.4b).
 */
class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonErrorWriter writer;

    JsonAuthenticationEntryPoint(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        writer.write(response, ErrorCode.UNAUTHENTICATED);
    }
}
