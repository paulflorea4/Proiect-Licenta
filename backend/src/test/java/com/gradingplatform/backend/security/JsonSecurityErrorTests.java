package com.gradingplatform.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 2.6a: the security chain's own 401 and 403 answer in the standard error shape. The chain has no
 * role rule of its own yet (roles are `@PreAuthorize`, 2.5a), so the access-denied handler is
 * exercised directly.
 */
class JsonSecurityErrorTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonErrorWriter writer = new JsonErrorWriter(mapper);

    @Test
    void theEntryPointAnswers401WithTheStandardBodyAndNoChallenge() throws Exception {
        var response = new MockHttpServletResponse();

        new JsonAuthenticationEntryPoint(writer)
                .commence(new MockHttpServletRequest(), response, new BadCredentialsException("secret detail"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(body(response))
                .containsEntry("status", 401)
                .containsEntry("code", "UNAUTHENTICATED")
                .containsEntry("message", ErrorCode.UNAUTHENTICATED.defaultMessage())
                .hasSize(3);
        assertThat(response.getContentAsString()).doesNotContain("secret detail");
    }

    @Test
    void theAccessDeniedHandlerAnswers403WithTheStandardBody() throws Exception {
        var response = new MockHttpServletResponse();

        new JsonAccessDeniedHandler(writer)
                .handle(new MockHttpServletRequest(), response, new AccessDeniedException("needs ROLE_ADMIN"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body(response)).containsEntry("status", 403).containsEntry("code", "ACCESS_DENIED");
        assertThat(response.getContentAsString()).doesNotContain("ROLE_ADMIN");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return mapper.readValue(response.getContentAsString(), Map.class);
    }
}
