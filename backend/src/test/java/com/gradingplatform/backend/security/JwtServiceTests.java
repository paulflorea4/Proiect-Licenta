package com.gradingplatform.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** 2.4a: what `JwtService` puts in a token and how it signs it. */
class JwtServiceTests {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofHours(24)), CLOCK);

    @Test
    void theTokenCarriesUserIdEmailRoleAndTimes() {
        Claims claims =
                parse(service.issue(user(42L, "ada@example.com", Role.TEACHER)).value(), SECRET);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("email", String.class)).isEqualTo("ada@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("TEACHER");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void everyRoleIsCarriedByName() {
        for (Role role : Role.values()) {
            Claims claims = parse(service.issue(user(1L, "a@example.com", role)).value(), SECRET);

            assertThat(claims.get("role", String.class)).isEqualTo(role.name());
        }
    }

    @Test
    void issuedTokenReportsWhenItExpiresAndForHowLong() {
        JwtService.IssuedToken token = service.issue(user(1L, "a@example.com", Role.STUDENT));

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(token.validFor()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void theExpiryFollowsTheConfiguredDuration() {
        JwtService shortLived = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(5)), CLOCK);

        Claims claims =
                parse(shortLived.issue(user(1L, "a@example.com", Role.STUDENT)).value(), SECRET);

        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void theTokenIsSignedWithHmacSha256() {
        String token = service.issue(user(1L, "a@example.com", Role.STUDENT)).value();

        assertThat(Jwts.parser()
                        .verifyWith(key(SECRET))
                        .build()
                        .parseSignedClaims(token)
                        .getHeader()
                        .getAlgorithm())
                .isEqualTo("HS256");
    }

    @Test
    void aTokenSignedWithTheRightSecretVerifiesAndWithAnotherSecretDoesNot() {
        String token = service.issue(user(1L, "a@example.com", Role.STUDENT)).value();

        assertThat(parse(token, SECRET)).isNotNull();
        assertThatThrownBy(() -> parse(token, "a-different-secret-0123456789abcdef0123456789abcdef"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTamperedTokenDoesNotVerify() {
        String token = service.issue(user(1L, "a@example.com", Role.STUDENT)).value();
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming ADMIN, keeping the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"1\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> parse(forged, SECRET)).isInstanceOf(JwtException.class);
    }

    @Test
    void theTokenIsRejectedOnceItHasExpired() {
        String token = service.issue(user(1L, "a@example.com", Role.STUDENT)).value();
        Clock later = Clock.fixed(NOW.plus(Duration.ofHours(24)).plusSeconds(1), ZoneOffset.UTC);

        assertThatThrownBy(() -> Jwts.parser()
                        .verifyWith(key(SECRET))
                        .clock(() -> java.util.Date.from(later.instant()))
                        .build()
                        .parseSignedClaims(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void theTokenNeverContainsThePasswordHash() {
        User user = new User("a@example.com", "$2a$10$SECRETHASH", "A", Role.STUDENT);
        setId(user, 1L);

        String token = service.issue(user).value();
        String payload =
                new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).doesNotContain("SECRETHASH").doesNotContain("$2a");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static Claims parse(String token, String secret) {
        return Jwts.parser()
                .verifyWith(key(secret))
                .clock(() -> java.util.Date.from(NOW))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static User user(Long id, String email, Role role) {
        User user = new User(email, "hash", "Name", role);
        setId(user, id);
        return user;
    }

    /** `User.id` is generated by the database; tests that never touch it set it by reflection. */
    private static void setId(User user, Long id) {
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
