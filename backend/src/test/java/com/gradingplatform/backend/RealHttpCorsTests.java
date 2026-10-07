package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 2.4d: CORS over a real HTTP connection, through the whole security chain. A preflight carries no
 * token, so these also prove CORS is answered ahead of authentication.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cors.allowed-origins=http://localhost:5173,https://app.example.com")
class RealHttpCorsTests extends RealHttpTestBase {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String ALLOWED_TOO = "https://app.example.com";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    // --- preflight -------------------------------------------------------------------------

    @Test
    void aPreflightFromTheFrontendIsAnsweredWithoutAToken() throws Exception {
        HttpResponse<String> response = preflight("/auth/me", ALLOWED, "GET", "authorization");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(header(response, "Access-Control-Allow-Methods")).contains("GET");
        assertThat(header(response, "Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
        assertThat(header(response, "Access-Control-Max-Age")).isEqualTo("3600");
    }

    @Test
    void aPreflightForTheJsonSigninCallIsAllowed() throws Exception {
        HttpResponse<String> response = preflight("/auth/signin", ALLOWED, "POST", "content-type");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(header(response, "Access-Control-Allow-Methods")).contains("POST");
    }

    @Test
    void everyConfiguredOriginIsAllowed() throws Exception {
        assertThat(header(preflight("/auth/me", ALLOWED_TOO, "GET", "authorization"), ALLOW_ORIGIN))
                .isEqualTo(ALLOWED_TOO);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.example",
                "http://localhost:5174",
                "https://localhost:5173",
                "http://localhost:5173.evil.example",
                "http://evil.example/http://localhost:5173",
                "null"
            })
    void aPreflightFromAnyOtherOriginIsRefused(String origin) throws Exception {
        HttpResponse<String> response = preflight("/auth/me", origin, "GET", "authorization");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue(ALLOW_ORIGIN)).isEmpty();
    }

    @Test
    void aPreflightForAMethodOrHeaderThatIsNotListedIsRefused() throws Exception {
        assertThat(preflight("/auth/me", ALLOWED, "TRACE", "authorization").statusCode())
                .isEqualTo(403);
        assertThat(preflight("/auth/me", ALLOWED, "GET", "x-something-else").statusCode())
                .isEqualTo(403);
    }

    // --- actual requests ---------------------------------------------------------------------

    @Test
    void aCallFromTheFrontendWithATokenGetsTheAllowOriginHeader() throws Exception {
        HttpResponse<String> response = get("/auth/me", ALLOWED, "Bearer " + tokenFor("ada@example.com"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(header(response, "Vary")).containsIgnoringCase("Origin");
    }

    @Test
    void credentialsAreNeverAllowed() throws Exception {
        HttpResponse<String> response = get("/auth/me", ALLOWED, "Bearer " + tokenFor("ada@example.com"));

        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .isEmpty();
        assertThat(header(response, ALLOW_ORIGIN)).isNotEqualTo("*");
        assertThat(preflight("/auth/me", ALLOWED, "GET", "authorization")
                        .headers()
                        .firstValue("Access-Control-Allow-Credentials"))
                .isEmpty();
    }

    @Test
    void a401FromTheFrontendStillCarriesTheHeaderSoTheBrowserCanReadIt() throws Exception {
        HttpResponse<String> response = get("/auth/me", ALLOWED, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(header(response, ALLOW_ORIGIN)).isEqualTo(ALLOWED);
    }

    @Test
    void aPublicEndpointAnswersTheFrontendToo() throws Exception {
        HttpResponse<String> response = get("/health", ALLOWED, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, ALLOW_ORIGIN)).isEqualTo(ALLOWED);
    }

    @Test
    void aCallFromAnotherOriginIsRefusedEvenWithAValidToken() throws Exception {
        HttpResponse<String> response =
                get("/auth/me", "https://evil.example", "Bearer " + tokenFor("ada@example.com"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue(ALLOW_ORIGIN)).isEmpty();
        assertThat(response.body()).doesNotContain("ada@example.com");
    }

    @Test
    void aCallWithoutAnOriginHeaderIsUnaffected() throws Exception {
        HttpResponse<String> response = get("/auth/me", null, "Bearer " + tokenFor("ada@example.com"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(ALLOW_ORIGIN)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------

    private String tokenFor(String email) {
        return jwtService
                .issue(users.save(new User(email, "hash", "Name", Role.STUDENT)))
                .value();
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private HttpResponse<String> preflight(String path, String origin, String method, String headers)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String origin, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
