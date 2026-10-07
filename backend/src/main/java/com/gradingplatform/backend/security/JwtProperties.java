package com.gradingplatform.backend.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings. `secret` comes from the `JWT_SECRET` environment variable (relaxed binding to
 * `jwt.secret`) and has no default anywhere: a missing or too-short secret stops the application
 * at startup instead of signing tokens with something guessable. `expiration` defaults to 24 hours
 * in `application.yml`.
 *
 * @param secret HMAC-SHA256 signing key, at least 32 characters (256 bits); `openssl rand -hex 32`
 *     gives 64
 * @param expiration how long an issued token stays valid; there is no refresh flow in v1
 */
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration expiration) {

    /** Fewest characters accepted (256 bits for HS256, all of them single bytes for hex output). */
    public static final int MIN_SECRET_LENGTH = 32;

    public JwtProperties {
        // Checked here, not with bean-validation annotations: Spring Boot prints the rejected value
        // in its startup error, which would write a too-short secret into the logs.
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jwt.secret (the JWT_SECRET environment variable) must be set");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("jwt.secret (the JWT_SECRET environment variable) must be at least "
                    + MIN_SECRET_LENGTH + " characters long");
        }
        if (expiration == null || expiration.isZero() || expiration.isNegative()) {
            throw new IllegalArgumentException("jwt.expiration must be a positive duration, was " + expiration);
        }
    }

    /** Never print the secret, whatever logs or exception messages format this object. */
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, expiration=" + expiration + "]";
    }
}
