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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 2.5b: `PATCH /admin/users/{id}/role` over a real HTTP connection. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpAdminRoleTests {

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

    // --- who may call it -------------------------------------------------------------------

    @Test
    void anAdminPromotesAStudentToTeacher() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User student = saved("ada@example.com", Role.STUDENT);

        HttpResponse<String> response = setRole(student.getId(), "TEACHER", admin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":" + student.getId())
                .contains("\"email\":\"ada@example.com\"")
                .contains("\"fullName\":\"Name\"")
                .contains("\"role\":\"TEACHER\"")
                .doesNotContain("assword")
                .doesNotContain("hash");
        assertThat(roleInDatabase(student)).isEqualTo("TEACHER");
    }

    @Test
    void anAdminCanMakeAnotherAdminAndDemoteATeacher() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User student = saved("ada@example.com", Role.STUDENT);
        User teacher = saved("grace@example.com", Role.TEACHER);

        assertThat(setRole(student.getId(), "ADMIN", admin).statusCode()).isEqualTo(200);
        assertThat(setRole(teacher.getId(), "STUDENT", admin).statusCode()).isEqualTo(200);

        assertThat(roleInDatabase(student)).isEqualTo("ADMIN");
        assertThat(roleInDatabase(teacher)).isEqualTo("STUDENT");
    }

    @Test
    void settingTheRoleTheUserAlreadyHasSucceedsAndChangesNothing() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User teacher = saved("grace@example.com", Role.TEACHER);

        HttpResponse<String> response = setRole(teacher.getId(), "TEACHER", admin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(roleInDatabase(teacher)).isEqualTo("TEACHER");
    }

    @Test
    void aStudentAndATeacherAreRefusedAndNothingChanges() throws Exception {
        User student = saved("ada@example.com", Role.STUDENT);
        User teacher = saved("grace@example.com", Role.TEACHER);

        // A student tries to promote themselves; a teacher tries to promote a student to admin.
        assertThat(setRole(student.getId(), "ADMIN", tokenFor(student)).statusCode())
                .isEqualTo(403);
        assertThat(setRole(student.getId(), "ADMIN", tokenFor(teacher)).statusCode())
                .isEqualTo(403);
        assertThat(setRole(teacher.getId(), "STUDENT", tokenFor(teacher)).statusCode())
                .isEqualTo(403);

        assertThat(roleInDatabase(student)).isEqualTo("STUDENT");
        assertThat(roleInDatabase(teacher)).isEqualTo("TEACHER");
    }

    @Test
    void anAnonymousCallerAndABadTokenAre401() throws Exception {
        User student = saved("ada@example.com", Role.STUDENT);

        assertThat(setRole(student.getId(), "TEACHER", null).statusCode()).isEqualTo(401);
        assertThat(setRole(student.getId(), "TEACHER", "not.a.jwt").statusCode())
                .isEqualTo(401);
        assertThat(roleInDatabase(student)).isEqualTo("STUDENT");
    }

    // --- bad requests ----------------------------------------------------------------------

    @Test
    void anUnknownUserIs404() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));

        assertThat(setRole(987654321L, "TEACHER", admin).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"role\":null}",
                "{\"role\":\"SUPERUSER\"}",
                "{\"role\":\"teacher\"}",
                "{\"role\":\"\"}",
                "{\"role\":7}",
                "not json",
                ""
            })
    void aMissingOrUnknownRoleIs400AndNothingChanges(String body) throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User student = saved("ada@example.com", Role.STUDENT);

        HttpResponse<String> response = patch("/admin/users/" + student.getId() + "/role", body, admin);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(roleInDatabase(student)).isEqualTo("STUDENT");
    }

    @Test
    void aNonNumericIdIs400() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));

        assertThat(patch("/admin/users/abc/role", "{\"role\":\"TEACHER\"}", admin)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void onlyPatchIsServed() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        User student = saved("ada@example.com", Role.STUDENT);

        for (String method : List.of("PUT", "POST", "DELETE")) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/admin/users/" + student.getId() + "/role"))
                    .header("Authorization", "Bearer " + admin)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString("{\"role\":\"TEACHER\"}"))
                    .build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(method)
                    .isEqualTo(405);
        }
        assertThat(roleInDatabase(student)).isEqualTo("STUDENT");
    }

    // --- the last admin --------------------------------------------------------------------

    @Test
    void theOnlyAdminCannotBeDemoted() throws Exception {
        User admin = saved("root@example.com", Role.ADMIN);
        saved("grace@example.com", Role.TEACHER);

        HttpResponse<String> response = setRole(admin.getId(), "TEACHER", tokenFor(admin));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at com.gradingplatform");
        assertThat(roleInDatabase(admin)).isEqualTo("ADMIN");
    }

    @Test
    void anAdminCanDemoteThemselvesWhileAnotherAdminRemains() throws Exception {
        User first = saved("root@example.com", Role.ADMIN);
        User second = saved("second@example.com", Role.ADMIN);

        assertThat(setRole(first.getId(), "STUDENT", tokenFor(first)).statusCode())
                .isEqualTo(200);

        assertThat(roleInDatabase(first)).isEqualTo("STUDENT");
        // The one left is now the last, whoever asks.
        assertThat(setRole(second.getId(), "STUDENT", tokenFor(second)).statusCode())
                .isEqualTo(409);
        assertThat(roleInDatabase(second)).isEqualTo("ADMIN");
    }

    @Test
    void theLastAdminKeepingTheirRoleIsFine() throws Exception {
        User admin = saved("root@example.com", Role.ADMIN);

        assertThat(setRole(admin.getId(), "ADMIN", tokenFor(admin)).statusCode())
                .isEqualTo(200);
    }

    @RepeatedTest(5)
    void twoAdminsDemotingEachOtherAtOnceLeaveExactlyOne() throws Exception {
        User first = saved("first@example.com", Role.ADMIN);
        User second = saved("second@example.com", Role.ADMIN);
        String firstToken = tokenFor(first);
        String secondToken = tokenFor(second);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstDemotesSecond = pool.submit(() -> {
                ready.countDown();
                go.await();
                return setRole(second.getId(), "TEACHER", firstToken).statusCode();
            });
            Future<Integer> secondDemotesFirst = pool.submit(() -> {
                ready.countDown();
                go.await();
                return setRole(first.getId(), "TEACHER", secondToken).statusCode();
            });
            ready.await();
            go.countDown();

            List<Integer> statuses = new ArrayList<>(List.of(firstDemotesSecond.get(), secondDemotesFirst.get()));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    // --- what the user sees afterwards -----------------------------------------------------

    @Test
    void thePromotedUserSeesTheNewRoleAtOnceInMeAndInTheNextSigninButTheOldTokenKeepsItsClaim() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        post("/auth/signup", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada L\"}");
        String oldToken =
                tokenFrom(post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}")
                        .body());
        long adaId = users.findByEmail("ada@example.com").orElseThrow().getId();

        assertThat(setRole(adaId, "ADMIN", admin).statusCode()).isEqualTo(200);

        // /auth/me reads the database, so it shows the new role with the old token...
        assertThat(get("/auth/me", oldToken).body()).contains("\"role\":\"ADMIN\"");
        // ...but the token's own claim still says STUDENT until the next signin (2.4b).
        assertThat(setRole(adaId, "TEACHER", oldToken).statusCode()).isEqualTo(403);
        HttpResponse<String> signin =
                post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}");
        assertThat(signin.body()).contains("\"role\":\"ADMIN\"");
        assertThat(setRole(adaId, "TEACHER", tokenFrom(signin.body())).statusCode())
                .isEqualTo(200);
    }

    // --- helpers ---------------------------------------------------------------------------

    private User saved(String email, Role role) {
        return users.save(new User(email, "hash", "Name", role));
    }

    private String tokenFor(User user) {
        return jwtService.issue(user).value();
    }

    private static String tokenFrom(String signinBody) {
        return signinBody.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private String roleInDatabase(User user) {
        return jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, user.getId());
    }

    private HttpResponse<String> setRole(long id, String role, String token) throws IOException, InterruptedException {
        return patch("/admin/users/" + id + "/role", "{\"role\":\"" + role + "\"}", token);
    }

    private HttpResponse<String> patch(String path, String json, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
