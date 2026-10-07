package com.gradingplatform.backend.security;

import com.gradingplatform.backend.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues our own signed JWTs (HS256). This is the only way a token comes into existence: there is
 * no OAuth or other external identity provider. Validating a token on each request is 2.4b.
 */
@Service
public class JwtService {

    /** Claim names, shared with whatever validates tokens (2.4b). The subject is the user id. */
    public static final String CLAIM_EMAIL = "email";

    public static final String CLAIM_ROLE = "role";

    /** A token and when it stops being valid. */
    public record IssuedToken(String value, Instant expiresAt, Duration validFor) {}

    private final SecretKey key;
    private final Duration validFor;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.validFor = properties.expiration();
        this.clock = clock;
    }

    /** Claims: subject = user id, email, role, issued-at and expiry. */
    public IssuedToken issue(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(validFor);
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt, validFor);
    }
}
