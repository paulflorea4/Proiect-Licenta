package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.repository.UserRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Status codes over a real HTTP connection to a running server. `MockMvc` never performs the
 * container's error dispatch to `/error`, so it cannot see a security rule that turns an error
 * (400, 401, 409) into a 403 on the way out; found by hand in 2.4a, pinned here.
 *
 * <p>Not `@Transactional` (the server handles requests on its own threads), so the `users` table is
 * emptied before and after each test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpAuthTests {

    private static final String PASSWORD = "correct horse";

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void emptyUsersTable() {
        users.deleteAll();
    }

    @Test
    void signupThenSigninWorksEndToEnd() throws Exception {
        assertThat(signup("ada@example.com", PASSWORD).statusCode()).isEqualTo(201);

        HttpResponse<String> signin = signin("ada@example.com", PASSWORD);

        assertThat(signin.statusCode()).isEqualTo(200);
        assertThat(signin.body()).contains("\"token\":\"").contains("\"tokenType\":\"Bearer\"");
    }

    @Test
    void aWrongPasswordIs401NotAForbidden() throws Exception {
        signup("ada@example.com", PASSWORD);

        assertThat(signin("ada@example.com", "wrong password").statusCode()).isEqualTo(401);
    }

    @Test
    void anUnknownEmailIs401() throws Exception {
        assertThat(signin("nobody@example.com", PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void aDuplicateEmailIs409() throws Exception {
        signup("ada@example.com", PASSWORD);

        assertThat(signup("ada@example.com", PASSWORD).statusCode()).isEqualTo(409);
    }

    @Test
    void invalidSignupInputIs400() throws Exception {
        assertThat(signup("ada@example.com", "short").statusCode()).isEqualTo(400);
        assertThat(signup("not-an-email", PASSWORD).statusCode()).isEqualTo(400);
        assertThat(post("/auth/signup", "{\"email\":\"ada@example.com\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void invalidSigninInputIs400() throws Exception {
        assertThat(post("/auth/signin", "{\"email\":\"ada@example.com\"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/auth/signin", "not json").statusCode()).isEqualTo(400);
    }

    @Test
    void errorBodiesDoNotLeakInternals() throws Exception {
        signup("ada@example.com", PASSWORD);

        for (HttpResponse<String> response :
                new HttpResponse[] {signup("ada@example.com", PASSWORD), signin("ada@example.com", "wrong password")}) {
            assertThat(response.body())
                    .doesNotContain("Exception")
                    .doesNotContain("users_email_key")
                    .doesNotContain("at com.gradingplatform")
                    .doesNotContain("$2a$");
        }
    }

    @Test
    void aProtectedPathWithoutATokenIs401() throws Exception {
        assertThat(get("/courses").statusCode()).isEqualTo(401);
    }

    @Test
    void signinSetsNoCookieAndNoSession() throws Exception {
        signup("ada@example.com", PASSWORD);

        HttpResponse<String> signin = signin("ada@example.com", PASSWORD);

        assertThat(signin.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void socialLoginPathsAreNeitherServedNorRedirected() throws Exception {
        for (String path : new String[] {
            "/oauth2/authorization/google",
            "/oauth2/authorization/github",
            "/login/oauth2/code/google",
            "/login/oauth2/code/github"
        }) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).as(path).isGreaterThanOrEqualTo(400);
            assertThat(response.headers().firstValue("Location")).as(path).isEmpty();
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private HttpResponse<String> signup(String email, String password) throws IOException, InterruptedException {
        return post(
                "/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"Ada\"}");
    }

    private HttpResponse<String> signin(String email, String password) throws IOException, InterruptedException {
        return post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
