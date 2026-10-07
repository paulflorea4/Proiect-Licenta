package com.gradingplatform.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The JWT filter chain (2.4b). Stateless: no session, no cookie, no login form; each request
 * carries `Authorization: Bearer <token>`, which {@link JwtAuthenticationFilter} verifies.
 *
 * <p>Public: `GET /health`, `POST /auth/signup`, `POST /auth/signin` and Spring's `/error` page.
 * Everything else needs a valid token; later tasks add role rules on top.
 *
 * <p>CSRF protection is off. It defends against a browser attaching ambient credentials (a session
 * or cookie) to a forged request; the token here is only ever sent by our own client code in a
 * header, never as a cookie (2.4a), so there is nothing for a forged request to ride on.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                // Uses the `corsConfigurationSource` bean (config/CorsConfig). It answers preflight
                // requests itself, ahead of the authentication filter.
                .cors(Customizer.withDefaults())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                // No token, or one that does not verify: a plain 401 with no body, no redirect to a
                // login page and no `WWW-Authenticate` challenge. A wrong role stays 403.
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        // Spring MVC reports an error status (400, 401, 409...) by forwarding to
                        // /error, which this chain also guards. Without this every error answered
                        // to an anonymous caller (signup, signin) would turn into a 403.
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/signin")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        return http.build();
    }
}
