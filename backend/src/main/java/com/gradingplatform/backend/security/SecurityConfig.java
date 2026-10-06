package com.gradingplatform.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Minimal filter chain so far (2.2b, 2.3b, 2.4a): `GET /health`, `POST /auth/signup`,
 * `POST /auth/signin` and Spring's `/error` page are public, everything else needs authentication. 2.4b replaces this with
 * the JWT chain, which must keep those three public. Tokens travel only in the `Authorization`
 * header (never a cookie), so 2.4b can switch CSRF off for the stateless API.
 */
@Configuration
public class SecurityConfig {

    private static final String SIGNUP = "/auth/signup";
    private static final String SIGNIN = "/auth/signin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        http
                // Signup and signin are anonymous and have no session or cookie to forge a request
                // against, so they are exempt from the CSRF check; nothing else is exempted here.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        paths.matcher(HttpMethod.POST, SIGNUP), paths.matcher(HttpMethod.POST, SIGNIN)))
                .authorizeHttpRequests(requests -> requests
                        // Spring MVC reports an error status (400, 401, 409...) by forwarding to
                        // /error, which this chain also guards. Without this every error answered
                        // to an anonymous caller (signup, signin) would turn into a 403.
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, SIGNUP, SIGNIN)
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        return http.build();
    }
}
