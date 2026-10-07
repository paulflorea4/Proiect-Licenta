package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.security.JwtProperties;
import com.gradingplatform.backend.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * 2.4b: the JWT filter and the public-vs-protected rules, over a real HTTP connection (see
 * {@link RealHttpAuthTests} for why `MockMvc` is not enough). No controller needs a login yet, so
 * a protected path that has no handler (`/courses`) stands in: an authenticated caller gets
 * Spring's 404 for it, an anonymous one is turned away with 401 before routing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpJwtFilterTests {

    private static final String PROTECTED = "/courses";

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtProperties jwtProperties;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void emptyUsersTable() {
        users.deleteAll();
    }

    @Test
    void aProtectedPathWithoutATokenIs401() throws Exception {
        HttpResponse<String> response = get(PROTECTED, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aValidTokenLetsTheRequestThroughTheSecurityChain() throws Exception {
        String token = tokenFor(Role.STUDENT);

        // 404, not 401: authentication passed and routing found no handler.
        assertThat(get(PROTECTED, "Bearer " + token).statusCode()).isEqualTo(404);
    }

    @Test
    void theSchemeIsMatchedCaseInsensitively() throws Exception {
        String token = tokenFor(Role.STUDENT);

        assertThat(get(PROTECTED, "bearer " + token).statusCode()).isEqualTo(404);
    }

    @Test
    void everyRoleIsAccepted() throws Exception {
        for (Role role : Role.values()) {
            users.deleteAll();
            assertThat(get(PROTECTED, "Bearer " + tokenFor(role)).statusCode())
                    .as(role.name())
                    .isEqualTo(404);
        }
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        String expired = tokenBuilder("1", Role.STUDENT)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(48))))
                .expiration(Date.from(Instant.now().minus(Duration.ofHours(24))))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(get(PROTECTED, "Bearer " + expired).statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenSignedWithAnotherKeyIs401() throws Exception {
        String forged = tokenBuilder("1", Role.ADMIN)
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(
                        "an-attacker-secret-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(get(PROTECTED, "Bearer " + forged).statusCode()).isEqualTo(401);
    }

    @Test
    void aTamperedPayloadIs401() throws Exception {
        String[] parts = tokenFor(Role.STUDENT).split("\\.");
        String adminPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"1\",\"email\":\"a@example.com\",\"role\":\"ADMIN\"}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(get(PROTECTED, "Bearer " + parts[0] + "." + adminPayload + "." + parts[2])
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    void anUnsignedTokenIs401() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String unsigned = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString("{\"sub\":\"1\",\"email\":\"a@example.com\",\"role\":\"ADMIN\"}"
                        .getBytes(StandardCharsets.UTF_8))
                + ".";

        assertThat(get(PROTECTED, "Bearer " + unsigned).statusCode()).isEqualTo(401);
    }

    @Test
    void aSignedTokenWithAnUnknownRoleOrBadSubjectIs401() throws Exception {
        String key = jwtProperties.secret();
        String unknownRole = tokenBuilder("1", Role.STUDENT)
                .claim("role", "SUPERUSER")
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8)))
                .compact();
        String badSubject = tokenBuilder("not-a-number", Role.STUDENT)
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(get(PROTECTED, "Bearer " + unknownRole).statusCode()).isEqualTo(401);
        assertThat(get(PROTECTED, "Bearer " + badSubject).statusCode()).isEqualTo(401);
    }

    @Test
    void garbageAndOtherSchemesAre401() throws Exception {
        for (String header : new String[] {
            "Bearer",
            "Bearer ",
            "Bearer not.a.jwt",
            "Bearer " + "x".repeat(5000),
            "Basic YWRhQGV4YW1wbGUuY29tOnNlY3JldA==",
            "Token " + tokenFor(Role.STUDENT)
        }) {
            HttpResponse<String> response = get(PROTECTED, header);

            assertThat(response.statusCode())
                    .as(header.substring(0, Math.min(20, header.length())))
                    .isEqualTo(401);
        }
    }

    @Test
    void aBadTokenDoesNotBlockThePublicEndpoints() throws Exception {
        HttpResponse<String> health = get("/health", "Bearer not.a.jwt");
        HttpResponse<String> signin = post("/auth/signin", "{\"email\":\"nobody@example.com\",\"password\":\"x\"}");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(signin.statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenFromSigninWorksOnAProtectedPath() throws Exception {
        post("/auth/signup", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}");
        String body = post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}")
                .body();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        assertThat(get(PROTECTED, "Bearer " + token).statusCode()).isEqualTo(404);
    }

    @Test
    void noSessionOrCookieIsEverCreated() throws Exception {
        HttpResponse<String> response = get(PROTECTED, "Bearer " + tokenFor(Role.STUDENT));

        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void aPostWithoutACsrfTokenIsNotRejectedForThat() throws Exception {
        // CSRF is off (tokens travel only in a header), so an authenticated write reaches routing
        // (404 here) instead of being refused with 403.
        HttpResponse<String> response = post(PROTECTED, "{}", "Bearer " + tokenFor(Role.STUDENT));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void theLoginAndLogoutPagesDoNotExist() throws Exception {
        assertThat(get("/login", null).statusCode()).isEqualTo(401);
        assertThat(get("/logout", null).statusCode()).isEqualTo(401);
        assertThat(get("/login", "Bearer " + tokenFor(Role.STUDENT)).statusCode())
                .isEqualTo(404);
    }

    // --- helpers ---------------------------------------------------------------------------

    private String tokenFor(Role role) {
        User user = users.save(new User(role.name().toLowerCase() + "@example.com", "hash", "Name", role));
        return jwtService.issue(user).value();
    }

    private static io.jsonwebtoken.JwtBuilder tokenBuilder(String subject, Role role) {
        return Jwts.builder().subject(subject).claim("email", "a@example.com").claim("role", role.name());
    }

    private HttpResponse<String> get(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return post(path, json, null);
    }

    private HttpResponse<String> post(String path, String json, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
