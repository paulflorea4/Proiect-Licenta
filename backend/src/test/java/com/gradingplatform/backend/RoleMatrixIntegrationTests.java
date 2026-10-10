package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 2.7d: the role matrix. Every endpoint of the application is called by every kind of caller
 * (nobody, a bad token, a student, a teacher, an admin) and must answer exactly as its row in
 * {@link #ENDPOINTS} says: 401 for an anonymous caller, 403 for a signed-in one with the wrong
 * role, anything but those two for a caller who may pass.
 *
 * <p><b>Every phase adds its endpoints here.</b> {@link #everyMappedEndpointHasARowInTheMatrix}
 * lists the endpoints Spring actually mapped and fails, naming the missing one, when a controller
 * method has no row. A new row says who may call it; that is all it takes to put the endpoint under
 * the whole matrix.
 *
 * <p>A row's body must be <b>valid</b> for its endpoint (the matrix would otherwise see a 400 where
 * it expects a 403, see {@link #aWrongRoleCallerMayStillSeeValidationOfAMalformedBody}), but must
 * not change anything: the rows address a user id that does not exist, so a caller who may pass is
 * answered by the endpoint itself (404, 400...) and never by security.
 */
class RoleMatrixIntegrationTests extends RealHttpTestBase {

    private static final Set<Role> ANYONE_SIGNED_IN = EnumSet.allOf(Role.class);

    /** Who may call an endpoint: everybody including anonymous, or only these roles. */
    private record Endpoint(
            String method, String pattern, String path, String body, boolean isPublic, Set<Role> roles) {

        static Endpoint publicEndpoint(String method, String path) {
            return new Endpoint(method, path, path, "{}", true, ANYONE_SIGNED_IN);
        }

        static Endpoint signedIn(String method, String path) {
            return new Endpoint(method, path, path, "{}", false, ANYONE_SIGNED_IN);
        }

        /** Any signed-in user, for a path with a variable part. */
        static Endpoint signedIn(String method, String pattern, String path) {
            return new Endpoint(method, pattern, path, "{}", false, ANYONE_SIGNED_IN);
        }

        static Endpoint rolesOnly(String method, String pattern, String path, String body, Role... roles) {
            return new Endpoint(method, pattern, path, body, false, EnumSet.copyOf(Arrays.asList(roles)));
        }

        String key() {
            return method + " " + pattern;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    private enum Caller {
        ANONYMOUS,
        BAD_TOKEN,
        STUDENT,
        TEACHER,
        ADMIN;

        Role role() {
            return Role.valueOf(name());
        }

        boolean isSignedIn() {
            return this == STUDENT || this == TEACHER || this == ADMIN;
        }
    }

    /** One row per endpoint of the application. Add the new ones here. */
    private static final List<Endpoint> ENDPOINTS = List.of(
            Endpoint.publicEndpoint("GET", "/health"),
            Endpoint.publicEndpoint("POST", "/auth/signup"),
            Endpoint.publicEndpoint("POST", "/auth/signin"),
            Endpoint.signedIn("GET", "/auth/me"),
            Endpoint.rolesOnly("GET", "/admin/users", "/admin/users", "{}", Role.ADMIN),
            Endpoint.rolesOnly(
                    "PATCH",
                    "/admin/users/{id}/role",
                    "/admin/users/987654321/role",
                    "{\"role\":\"TEACHER\"}",
                    Role.ADMIN),
            // Unlike the rows above, a teacher who passes here creates a course; the base class
            // empties `courses` after every test.
            Endpoint.rolesOnly("POST", "/courses", "/courses", "{\"title\":\"Algorithms\"}", Role.TEACHER),
            // Any signed-in user may list; what each sees depends on the role (RealHttpCourseListTests).
            Endpoint.signedIn("GET", "/courses"),
            // Who may see which course is decided by CourseAccess, tested in RealHttpCourseGetTests.
            Endpoint.signedIn("GET", "/courses/{id}", "/courses/987654321"),
            // Teachers and admins pass the role rule; ownership then decides (RealHttpCourseChangeTests).
            Endpoint.rolesOnly(
                    "PUT",
                    "/courses/{id}",
                    "/courses/987654321",
                    "{\"title\":\"Algorithms\"}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly("DELETE", "/courses/{id}", "/courses/987654321", "{}", Role.TEACHER, Role.ADMIN),
            // A student who passes here is told the code matches nothing; nothing is joined.
            Endpoint.rolesOnly("POST", "/courses/enroll", "/courses/enroll", "{\"code\":\"ZZZZZZZZ\"}", Role.STUDENT),
            // A student who passes here is told the course is not theirs; nothing is removed.
            Endpoint.rolesOnly(
                    "DELETE", "/courses/{id}/enrollment", "/courses/987654321/enrollment", "{}", Role.STUDENT),
            // Teachers and admins pass the role rule; ownership then decides (RealHttpCourseStudentsTests).
            Endpoint.rolesOnly(
                    "GET", "/courses/{id}/students", "/courses/987654321/students", "{}", Role.TEACHER, Role.ADMIN),
            // Any signed-in user may ask; CourseAccess decides what they see (RealHttpAssignmentGetTests).
            Endpoint.signedIn("GET", "/courses/{courseId}/assignments", "/courses/987654321/assignments"),
            Endpoint.signedIn("GET", "/assignments/{id}", "/assignments/987654321"),
            // Teachers and admins pass the role rule; ownership then decides (RealHttpAssignmentChangeTests).
            Endpoint.rolesOnly(
                    "PUT",
                    "/assignments/{id}",
                    "/assignments/987654321",
                    "{\"title\":\"Sorting\",\"description\":\"Sort it\",\"language\":\"JAVA\","
                            + "\"deadline\":\"2999-01-01T00:00:00Z\",\"timeLimitMs\":2000,\"memoryLimitMb\":256}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "POST",
                    "/assignments/{id}/publish",
                    "/assignments/987654321/publish",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "POST",
                    "/assignments/{id}/unpublish",
                    "/assignments/987654321/unpublish",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly("DELETE", "/assignments/{id}", "/assignments/987654321", "{}", Role.TEACHER, Role.ADMIN),
            // The rubric is for the people who run the course (RealHttpRubricTests); a student is refused
            // by role even on a published assignment.
            Endpoint.rolesOnly(
                    "GET",
                    "/assignments/{assignmentId}/rubric",
                    "/assignments/987654321/rubric",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "POST",
                    "/assignments/{assignmentId}/rubric",
                    "/assignments/987654321/rubric",
                    "{\"name\":\"Tests\",\"type\":\"TESTS\",\"weight\":100}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "PUT",
                    "/assignments/{assignmentId}/rubric/{criterionId}",
                    "/assignments/987654321/rubric/987654321",
                    "{\"name\":\"Tests\",\"type\":\"TESTS\",\"weight\":100}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "DELETE",
                    "/assignments/{assignmentId}/rubric/{criterionId}",
                    "/assignments/987654321/rubric/987654321",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            // Tests, in full including hidden ones, for the people who run the course (RealHttpTestCaseTests).
            Endpoint.rolesOnly(
                    "GET",
                    "/assignments/{assignmentId}/tests",
                    "/assignments/987654321/tests",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "POST",
                    "/assignments/{assignmentId}/tests",
                    "/assignments/987654321/tests",
                    "{\"criterionId\":1,\"name\":\"t\",\"input\":\"\",\"expectedOutput\":\"\",\"visibility\":\"PUBLIC\",\"weight\":1}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "PUT",
                    "/assignments/{assignmentId}/tests/{testId}",
                    "/assignments/987654321/tests/987654321",
                    "{\"criterionId\":1,\"name\":\"t\",\"input\":\"\",\"expectedOutput\":\"\",\"visibility\":\"PUBLIC\",\"weight\":1}",
                    Role.TEACHER,
                    Role.ADMIN),
            Endpoint.rolesOnly(
                    "DELETE",
                    "/assignments/{assignmentId}/tests/{testId}",
                    "/assignments/987654321/tests/987654321",
                    "{}",
                    Role.TEACHER,
                    Role.ADMIN),
            // Teachers only (admin excluded, as for creating a course); ownership then decides, and
            // this course does not exist, so a teacher who passes is answered by a 404.
            Endpoint.rolesOnly(
                    "POST",
                    "/courses/{courseId}/assignments",
                    "/courses/987654321/assignments",
                    "{\"title\":\"Sorting\",\"description\":\"Sort it\",\"language\":\"JAVA\","
                            + "\"deadline\":\"2999-01-01T00:00:00Z\",\"timeLimitMs\":2000,\"memoryLimitMb\":256}",
                    Role.TEACHER));

    static Stream<Arguments> endpointsAndCallers() {
        return ENDPOINTS.stream()
                .flatMap(endpoint -> Arrays.stream(Caller.values()).map(caller -> Arguments.of(endpoint, caller)));
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // --- the matrix --------------------------------------------------------------------------

    @ParameterizedTest(name = "{1} calls {0}")
    @MethodSource("endpointsAndCallers")
    void eachCallerIsAnsweredAsTheMatrixSays(Endpoint endpoint, Caller caller) throws Exception {
        String token = tokenFor(caller);

        HttpResponse<String> response = send(endpoint.method(), endpoint.path(), token, endpoint.body());

        boolean mayPass =
                endpoint.isPublic() || (caller.isSignedIn() && endpoint.roles().contains(caller.role()));
        if (mayPass) {
            // Answered by the endpoint itself (200, 400, 404...), not turned away by security.
            assertThat(response.statusCode()).isNotIn(401, 403).isLessThan(500);
        } else {
            assertThat(response.statusCode()).isEqualTo(caller.isSignedIn() ? 403 : 401);
            assertThat(JsonPath.<String>read(response.body(), "$.code"))
                    .isEqualTo(caller.isSignedIn() ? "ACCESS_DENIED" : "UNAUTHENTICATED");
        }
    }

    @Test
    void everyMappedEndpointHasARowInTheMatrix() {
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (pattern.equals("/error")) {
                    continue; // the container's error page, public by design (2.4a)
                }
                if (methods.isEmpty()) {
                    mapped.add("ANY " + pattern);
                }
                methods.forEach(method -> mapped.add(method.name() + " " + pattern));
            }
        });

        Set<String> inMatrix = new TreeSet<>();
        ENDPOINTS.forEach(endpoint -> inMatrix.add(endpoint.key()));

        assertThat(mapped)
                .as("Endpoints mapped by Spring vs rows of ENDPOINTS in RoleMatrixIntegrationTests;"
                        + " add a row for each new endpoint")
                .isEqualTo(inMatrix);
    }

    @Test
    void anUnknownPathIsAlsoTurnedAwayBeforeRoutingForAnAnonymousCaller() throws Exception {
        // 401, not 404: an anonymous caller cannot tell which protected paths exist.
        assertThat(send("GET", "/admin/does-not-exist", null, null).statusCode())
                .isEqualTo(401);
        assertThat(send("DELETE", "/admin/users", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aWrongRoleCallerLearnsNothingAboutWhichUsersExist() throws Exception {
        String path = "/admin/users/987654321/role"; // no such user
        String body = "{\"role\":\"TEACHER\"}";

        // 403 for the wrong role, whether or not the user exists: the lookup never happens.
        assertThat(send("PATCH", path, tokenFor(Caller.STUDENT), body).statusCode())
                .isEqualTo(403);
        assertThat(send("PATCH", path, tokenFor(Caller.TEACHER), body).statusCode())
                .isEqualTo(403);
        assertThat(send("PATCH", path, null, body).statusCode()).isEqualTo(401);
        // Only the admin learns that the user does not exist.
        assertThat(send("PATCH", path, tokenFor(Caller.ADMIN), body).statusCode())
                .isEqualTo(404);
    }

    /**
     * Known limit of the 2.5a convention, written down rather than hidden: `@PreAuthorize` wraps the
     * controller method, and Spring parses and validates `@RequestBody` before calling it. So a
     * signed-in caller with the wrong role who sends a malformed body gets the 400 the endpoint's
     * validation would give, not a 403. Nothing about any user is revealed (a valid body is 403, see
     * above) and an anonymous caller is still 401 from the filter chain. If this is ever closed (an
     * authorization check ahead of argument resolution), this test should flip to expect 403.
     */
    @Test
    void aWrongRoleCallerMayStillSeeValidationOfAMalformedBody() throws Exception {
        String path = "/admin/users/987654321/role";

        assertThat(send("PATCH", path, tokenFor(Caller.STUDENT), "not json").statusCode())
                .isEqualTo(400);
        assertThat(send("PATCH", path, null, "not json").statusCode()).isEqualTo(401);
    }

    // --- signup can never create anything but a student --------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"role\":\"TEACHER\"",
                "\"role\":\"ADMIN\"",
                "\"role\":\"ROLE_ADMIN\"",
                "\"Role\":\"ADMIN\"",
                "\"roles\":[\"ADMIN\"]",
                "\"authorities\":[\"ROLE_ADMIN\"]",
                "\"admin\":true",
                "\"isAdmin\":true",
                "\"user\":{\"role\":\"ADMIN\"}"
            })
    void signupCannotCreateATeacherOrAnAdmin(String extraField) throws Exception {
        String body = "{\"email\":\"mallory@example.com\",\"password\":\"correct horse\",\"fullName\":\"Mallory\","
                + extraField + "}";

        HttpResponse<String> signup = post("/auth/signup", body, null);

        assertThat(signup.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(signup.body(), "$.role")).isEqualTo("STUDENT");
        assertThat(users.findByEmail("mallory@example.com").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);

        // And the account really has a student's rights: the token it signs in with is refused
        // on every admin endpoint.
        String token = JsonPath.read(
                post("/auth/signin", "{\"email\":\"mallory@example.com\",\"password\":\"correct horse\"}", null)
                        .body(),
                "$.token");
        assertThat(JsonPath.<String>read(get("/auth/me", token).body(), "$.role"))
                .isEqualTo("STUDENT");
        assertThat(get("/admin/users", token).statusCode()).isEqualTo(403);
        assertThat(patch("/admin/users/1/role", "{\"role\":\"ADMIN\"}", token).statusCode())
                .isEqualTo(403);
    }

    @Test
    void aStudentCannotPromoteThemselvesAndOnlyAnAdminCan() throws Exception {
        post(
                "/auth/signup",
                "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}",
                null);
        long id = users.findByEmail("ada@example.com").orElseThrow().getId();
        String studentToken = signinToken("ada@example.com");

        assertThat(patch("/admin/users/" + id + "/role", "{\"role\":\"ADMIN\"}", studentToken)
                        .statusCode())
                .isEqualTo(403);
        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(Role.STUDENT);

        // The admin promotes her to teacher; after signing in again she is a teacher, and a
        // teacher is still not an admin.
        String admin = tokenFor(Caller.ADMIN);
        assertThat(patch("/admin/users/" + id + "/role", "{\"role\":\"TEACHER\"}", admin)
                        .statusCode())
                .isEqualTo(200);
        String teacherToken = signinToken("ada@example.com");

        assertThat(JsonPath.<String>read(get("/auth/me", teacherToken).body(), "$.role"))
                .isEqualTo("TEACHER");
        assertThat(get("/admin/users", teacherToken).statusCode()).isEqualTo(403);
        assertThat(patch("/admin/users/" + id + "/role", "{\"role\":\"ADMIN\"}", teacherToken)
                        .statusCode())
                .isEqualTo(403);
        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(Role.TEACHER);
    }

    // --- helpers ---------------------------------------------------------------------------

    /** A token for the caller: none for ANONYMOUS, garbage for BAD_TOKEN, else a stored user's. */
    private String tokenFor(Caller caller) {
        return switch (caller) {
            case ANONYMOUS -> null;
            case BAD_TOKEN -> "not.a.jwt";
            default -> tokenFor(saved(caller.name().toLowerCase() + "@example.com", caller.role()));
        };
    }

    private String signinToken(String email) throws Exception {
        return JsonPath.read(
                post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"correct horse\"}", null)
                        .body(),
                "$.token");
    }
}
