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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 2.5a: the role convention (`@PreAuthorize` on controller methods) over a real HTTP connection.
 * No production endpoint is role-restricted yet, so a probe controller that exists only in this
 * test stands in for the first one; it shows the convention for Phase 3 onward to copy.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpRoleSecurityTests {

    /**
     * Nested in a test class, so component scanning skips it (Spring Boot's test type filter
     * excludes classes of a test class); it is registered only by {@link ProbeConfig}. Spring MVC
     * needs `@Controller` here: a bare type-level `@RequestMapping` no longer makes a handler.
     */
    @Controller
    @RequestMapping("/test-roles")
    public static class RoleProbe {

        @GetMapping("/admin")
        @ResponseBody
        @PreAuthorize("hasRole('ADMIN')")
        public String adminOnly() {
            return "secret-for-admin";
        }

        @GetMapping("/teacher-or-admin")
        @ResponseBody
        @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
        public String teacherOrAdmin() {
            return "secret-for-staff";
        }

        /** No annotation: the chain's default applies, any signed-in user. */
        @GetMapping("/signed-in")
        @ResponseBody
        public String anySignedIn() {
            return "hello";
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        RoleProbe roleProbe() {
            return new RoleProbe();
        }
    }

    // Path -> the roles that may call it.
    private static final Map<String, Set<Role>> ALLOWED = Map.of(
            "/test-roles/admin", Set.of(Role.ADMIN),
            "/test-roles/teacher-or-admin", Set.of(Role.TEACHER, Role.ADMIN),
            "/test-roles/signed-in", Set.of(Role.STUDENT, Role.TEACHER, Role.ADMIN));

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void emptyUsersTable() {
        users.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void eachRoleReachesExactlyTheEndpointsListedForIt(Role role) throws Exception {
        String token = tokenFor(role);

        for (var rule : ALLOWED.entrySet()) {
            HttpResponse<String> response = get(rule.getKey(), "Bearer " + token);

            int expected = rule.getValue().contains(role) ? 200 : 403;
            assertThat(response.statusCode())
                    .as("%s calling %s", role, rule.getKey())
                    .isEqualTo(expected);
        }
    }

    @Test
    void anAnonymousCallerIs401OnEveryEndpointIncludingTheRoleRestrictedOnes() throws Exception {
        for (String path : ALLOWED.keySet()) {
            assertThat(get(path, null).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void aBadTokenIs401NotARoleDecision() throws Exception {
        assertThat(get("/test-roles/admin", "Bearer not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void aRefusedCallerLearnsNothingAndGetsNoChallenge() throws Exception {
        HttpResponse<String> response = get("/test-roles/admin", "Bearer " + tokenFor(Role.STUDENT));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .doesNotContain("secret-for-admin")
                .doesNotContain("Exception")
                .doesNotContain("ADMIN")
                .doesNotContain("at com.gradingplatform");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void theAllowedRoleReceivesTheEndpointsOwnResponse() throws Exception {
        assertThat(get("/test-roles/admin", "Bearer " + tokenFor(Role.ADMIN)).body())
                .isEqualTo("secret-for-admin");
    }

    // --- helpers ---------------------------------------------------------------------------

    private String tokenFor(Role role) {
        User user = users.save(new User(role.name().toLowerCase() + "@example.com", "hash", "Name", role));
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
}
