package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.ClassUtils;

/**
 * Guard: the only way to sign in or up is our own email + password (`/auth/signin`, `/auth/signup`).
 * No Google, GitHub or other external identity provider (root `CLAUDE.md`: "no OAuth"). If someone
 * adds an OAuth dependency, this fails and forces the decision through a task.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NoSocialLoginTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void noOauthClientOrResourceServerLibraryIsOnTheClasspath() {
        List<String> classes = List.of(
                "org.springframework.security.oauth2.client.registration.ClientRegistrationRepository",
                "org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter",
                "org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter",
                "org.springframework.security.oauth2.jwt.JwtDecoder");
        ClassLoader loader = getClass().getClassLoader();

        for (String name : classes) {
            assertThat(ClassUtils.isPresent(name, loader)).as(name).isFalse();
        }
    }

    @Test
    void theUsualSocialLoginEndpointsAreNotServed() throws Exception {
        for (String path : List.of(
                "/oauth2/authorization/google",
                "/oauth2/authorization/github",
                "/login/oauth2/code/google",
                "/login/oauth2/code/github",
                "/login/oauth2")) {
            int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

            // Not a redirect to a provider (3xx) and not a page (2xx): unauthenticated and unknown.
            assertThat(status).as(path).isGreaterThanOrEqualTo(400);
        }
    }
}
