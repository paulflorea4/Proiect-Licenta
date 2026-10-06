package com.gradingplatform.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal filter chain so far (2.2b): `GET /health` is public, everything else needs
 * authentication. 2.4b replaces this with the JWT chain and must keep `/health` public.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/health")
                .permitAll()
                .anyRequest()
                .authenticated());
        return http.build();
    }
}
