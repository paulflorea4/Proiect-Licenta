package com.gradingplatform.backend.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for the browser frontend (2.4d). The bean is named `corsConfigurationSource`, which is the
 * name Spring Security's `http.cors(...)` looks up, so the check runs inside the security chain
 * before authentication: a preflight `OPTIONS` request never carries a token and must not get a 401.
 *
 * <p>No credentials: the JWT travels in the `Authorization` header, never as a cookie (2.4a), so the
 * browser has nothing ambient to attach and `Access-Control-Allow-Credentials` stays off.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    /** How long a browser may reuse a preflight answer. */
    static final Duration PREFLIGHT_MAX_AGE = Duration.ofHours(1);

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var cors = new CorsConfiguration();
        // Exact origins only; with an empty list nothing is allowed cross-origin.
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(PREFLIGHT_MAX_AGE);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
