package com.gradingplatform.backend;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.security.JwtService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Base of the integration tests that talk to a real server over HTTP (2.7a): the whole application
 * on a random port, with its own empty database from the shared Postgres
 * ({@link TestcontainersConfiguration}). It is what 2.7b and the tests of every later phase extend.
 *
 * <p>What it gives a test: {@link #port}, the repositories and token service, empty `users` and
 * `courses` and `enrollments` tables before and after every test, {@link #saved} and {@link #tokenFor} to create a caller, and
 * {@link #send} (with {@link #get}, {@link #post}, {@link #patch}) to make a request as that caller.
 * A subclass that needs another configuration repeats {@code @SpringBootTest} with its own
 * properties; Spring builds (and caches) a separate context for it, still on the shared container.
 *
 * <p>MockMvc tests do not use this class: they `@Import` {@link TestcontainersConfiguration}
 * directly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class RealHttpTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected CourseRepository courses;

    @Autowired
    protected EnrollmentRepository enrollments;

    @Autowired
    protected JwtService jwtService;

    protected final HttpClient http = HttpClient.newHttpClient();

    /**
     * Tests in one context share a database, so each starts and ends without users or courses.
     * Rows that reference another table go first (a course references its teacher); a phase that
     * adds a table referencing these must delete from it here, ahead of the tables it references.
     */
    @BeforeEach
    @AfterEach
    void emptyTables() {
        enrollments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    /** A stored user with a placeholder password hash (they cannot sign in; use signup for that). */
    protected User saved(String email, Role role) {
        return users.save(new User(email, "hash", "Name", role));
    }

    /** A valid token for the user, as signin would issue it. */
    protected String tokenFor(User user) {
        return jwtService.issue(user).value();
    }

    /**
     * Sends a request to the running server.
     *
     * @param token a JWT sent as {@code Authorization: Bearer <token>}, or null for an anonymous call
     *     (any string is sent as given, so {@code "not.a.jwt"} makes a bad token)
     * @param json the body, sent as JSON; null sends none
     */
    protected HttpResponse<String> send(String method, String path, String token, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        return send("GET", path, token, null);
    }

    protected HttpResponse<String> post(String path, String json, String token)
            throws IOException, InterruptedException {
        return send("POST", path, token, json);
    }

    protected HttpResponse<String> patch(String path, String json, String token)
            throws IOException, InterruptedException {
        return send("PATCH", path, token, json);
    }
}
