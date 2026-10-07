package com.gradingplatform.backend.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which browser origins may call the API (2.4d). `allowedOrigins` comes from the comma-separated
 * `CORS_ALLOWED_ORIGINS` environment variable (relaxed binding to `cors.allowed-origins`).
 *
 * <p>Unset or empty means no cross-origin call is allowed, which is the safe failure. A wildcard
 * or anything that is not a plain origin (`scheme://host[:port]`, no path, no trailing slash) stops
 * the application at startup, because a browser compares origins as exact strings and such an entry
 * would silently never match, or match everything.
 *
 * @param allowedOrigins exact origins, e.g. `http://localhost:5173`; trimmed and de-duplicated
 */
@ConfigurationProperties("cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        } else {
            var origins = new LinkedHashSet<String>();
            for (String origin : allowedOrigins) {
                String trimmed = origin == null ? "" : origin.trim();
                // A trailing comma in the variable gives an empty entry: ignore it, not an error.
                if (!trimmed.isEmpty()) {
                    origins.add(requireOrigin(trimmed));
                }
            }
            allowedOrigins = List.copyOf(origins);
        }
    }

    private static String requireOrigin(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid(value);
        }
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        // getRawPath is "" for `http://host` and "/" for `http://host/`: only the first is an origin.
        boolean onlyAnOrigin = uri.getHost() != null
                && uri.getRawPath().isEmpty()
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getRawUserInfo() == null;
        if (!httpScheme || !onlyAnOrigin) {
            throw invalid(value);
        }
        return value;
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("cors.allowed-origins (the CORS_ALLOWED_ORIGINS environment variable) "
                + "must list origins such as http://localhost:5173 (http or https, no path, no trailing slash, "
                + "no wildcard), but contains '" + value + "'");
    }
}
