package com.gradingplatform.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Minimal filter chain so far (2.2b, 2.3b): `GET /health` and `POST /auth/signup` are public,
 * everything else needs authentication. 2.4b replaces this with the JWT chain, which must keep
 * those public (plus `/auth/signin`) and decides CSRF and sessions for the whole API.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Signup is anonymous and has no session or cookie to forge a request against, so
                // it is exempt from the CSRF check; nothing else is exempted here.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/signup")))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/signup")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        return http.build();
    }
}
