package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.security.JwtService;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 2.4c: `GET /auth/me` over a real HTTP connection. It is the first endpoint behind the JWT filter,
 * so it also proves signup → signin → token → protected call end to end.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpMeTests {

    private static final String PASSWORD = "correct horse";

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void emptyUsersTable() {
        users.deleteAll();
    }

    @Test
    void returnsTheSignedInUserFromTheTokenIssuedBySignin() throws Exception {
        post(
                "/auth/signup",
                "{\"email\":\"ada@example.com\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Ada L\"}");
        String signin = post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .body();
        String token = signin.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> me = get("/auth/me", "Bearer " + token);

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
                .contains("\"email\":\"ada@example.com\"")
                .contains("\"fullName\":\"Ada L\"")
                .contains("\"role\":\"STUDENT\"")
                .contains("\"id\":");
    }

    @Test
    void neverExposesThePasswordHashOrTheToken() throws Exception {
        HttpResponse<String> me = get("/auth/me", "Bearer " + tokenFor(saved("ada@example.com", Role.STUDENT)));

        assertThat(me.body()).doesNotContain("assword").doesNotContain("$2a$").doesNotContain("hash");
        assertThat(me.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void withoutATokenIs401() throws Exception {
        assertThat(get("/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    void aBadTokenIs401() throws Exception {
        assertThat(get("/auth/me", "Bearer not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenOfADeletedUserIs401() throws Exception {
        User user = saved("ada@example.com", Role.STUDENT);
        String token = tokenFor(user);
        users.deleteAll();

        HttpResponse<String> me = get("/auth/me", "Bearer " + token);

        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.body()).doesNotContain("Exception").doesNotContain("at com.gradingplatform");
    }

    @Test
    void showsTheCurrentRoleNotTheOneInTheToken() throws Exception {
        User user = saved("ada@example.com", Role.STUDENT);
        String token = tokenFor(user);
        jdbc.update("UPDATE users SET role = 'TEACHER' WHERE id = ?", user.getId());

        assertThat(get("/auth/me", "Bearer " + token).body()).contains("\"role\":\"TEACHER\"");
    }

    @Test
    void answersEachUserTheirOwnAccount() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));
        String grace = tokenFor(saved("grace@example.com", Role.ADMIN));

        assertThat(get("/auth/me", "Bearer " + ada).body()).contains("ada@example.com");
        assertThat(get("/auth/me", "Bearer " + grace).body())
                .contains("grace@example.com")
                .contains("\"role\":\"ADMIN\"");
    }

    @Test
    void onlyGetIsServed() throws Exception {
        HttpResponse<String> response =
                post("/auth/me", "{}", "Bearer " + tokenFor(saved("a@example.com", Role.STUDENT)));

        assertThat(response.statusCode()).isEqualTo(405);
    }

    // --- helpers ---------------------------------------------------------------------------

    private User saved(String email, Role role) {
        return users.save(new User(email, "hash", "Name", role));
    }

    private String tokenFor(User user) {
        return jwtService.issue(user).value();
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
