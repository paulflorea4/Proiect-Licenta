package com.gradingplatform.backend.config;

import com.gradingplatform.backend.security.AuthCookie;
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
 * <p>Credentials allowed since 8.2e: the JWT is an `httpOnly` cookie (8.2b), which the browser sends
 * on a cross-origin call (the dev frontend on :5173 to the API on :8080) only when the response
 * allows credentials. That is safe only because the origins are exact (never `*`, which the
 * browser refuses with credentials anyway) and cookie-authenticated writes need the CSRF header
 * (8.2d).
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
        // X-Requested-With: the CSRF header a cookie-authenticated write must carry (8.2d).
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", AuthCookie.CSRF_HEADER));
        cors.setAllowCredentials(true);
        cors.setMaxAge(PREFLIGHT_MAX_AGE);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
