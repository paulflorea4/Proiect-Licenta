package com.gradingplatform.backend.security;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues our own signed JWTs (HS256) and verifies them on each request (2.4b). This is the only way
 * a token comes into existence: there is no OAuth or other external identity provider.
 */
@Service
public class JwtService {

    /** Claim names, read back by {@link #parse}. The subject is the user id. */
    public static final String CLAIM_EMAIL = "email";

    public static final String CLAIM_ROLE = "role";

    /** A token and when it stops being valid. */
    public record IssuedToken(String value, Instant expiresAt, Duration validFor) {}

    private final SecretKey key;
    private final JwtParser parser;
    private final Duration validFor;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.validFor = properties.expiration();
        this.clock = clock;
        // Only tokens signed with our key verify: unsigned ("alg": "none") and other-key tokens are
        // refused, and the expiry is judged by the injected clock.
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * The user a token describes, or empty when it is not one of ours: malformed, unsigned, signed
     * with another key, expired, or missing a claim. The reason is deliberately not reported, so
     * callers cannot tell a forger what was wrong.
     */
    public Optional<AuthenticatedUser> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String email = claims.get(CLAIM_EMAIL, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (claims.getSubject() == null || email == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(Long.parseLong(claims.getSubject()), email, Role.valueOf(role)));
        } catch (JwtException | IllegalArgumentException e) {
            // Includes NumberFormatException (non-numeric subject) and an unknown role name.
            return Optional.empty();
        }
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
