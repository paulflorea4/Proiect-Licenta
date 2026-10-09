package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 3.1a: `POST /courses` over a real server and database. */
class RealHttpCourseCreateTests extends RealHttpTestBase {

    private static final String CODE_PATTERN = "[A-HJ-NP-Z2-9]{8}";

    private User teacher;
    private String teacherToken;

    private void signedInTeacher() {
        teacher = saved("teacher@example.com", Role.TEACHER);
        teacherToken = tokenFor(teacher);
    }

    // --- the happy path ----------------------------------------------------------------------

    @Test
    void aTeacherCreatesACourseAndGetsItsCode() throws Exception {
        signedInTeacher();

        HttpResponse<String> response =
                post("/courses", "{\"title\":\"Algorithms\",\"description\":\"Sorting and graphs\"}", teacherToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(response.body(), "$.id")).isPositive();
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algorithms");
        assertThat(JsonPath.<String>read(response.body(), "$.description")).isEqualTo("Sorting and graphs");
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(teacher.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.enrollCode")).matches(CODE_PATTERN);
        assertThat(JsonPath.<String>read(response.body(), "$.createdAt")).isNotBlank();
    }

    @Test
    void whatIsReturnedIsWhatIsStored() throws Exception {
        signedInTeacher();

        HttpResponse<String> response = post("/courses", "{\"title\":\"Algorithms\"}", teacherToken);

        long id = JsonPath.<Integer>read(response.body(), "$.id");
        Course stored = courses.findById(id).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Algorithms");
        assertThat(stored.getTeacherId()).isEqualTo(teacher.getId());
        assertThat(stored.getEnrollCode()).isEqualTo(JsonPath.<String>read(response.body(), "$.enrollCode"));
        assertThat(courses.count()).isEqualTo(1);
    }

    @Test
    void theDescriptionIsOptional() throws Exception {
        signedInTeacher();

        HttpResponse<String> absent = post("/courses", "{\"title\":\"A\"}", teacherToken);
        HttpResponse<String> nullValue = post("/courses", "{\"title\":\"B\",\"description\":null}", teacherToken);
        HttpResponse<String> blank = post("/courses", "{\"title\":\"C\",\"description\":\"   \"}", teacherToken);

        for (HttpResponse<String> response : List.of(absent, nullValue, blank)) {
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(JsonPath.<Object>read(response.body(), "$.description")).isNull();
        }
    }

    @Test
    void theTitleIsStoredWithoutSurroundingSpaces() throws Exception {
        signedInTeacher();

        HttpResponse<String> response = post("/courses", "{\"title\":\"  Algorithms \\n\"}", teacherToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algorithms");
    }

    @Test
    void titleAndDescriptionAtTheirLimitsAreAccepted() throws Exception {
        signedInTeacher();
        String body = "{\"title\":\"" + "t".repeat(255) + "\",\"description\":\"" + "d".repeat(5000) + "\"}";

        assertThat(post("/courses", body, teacherToken).statusCode()).isEqualTo(201);
    }

    @Test
    void nonAsciiTextSurvivesTheRoundTrip() throws Exception {
        signedInTeacher();

        HttpResponse<String> response =
                post("/courses", "{\"title\":\"Algoritmi și structuri\",\"description\":\"日本語 ✓\"}", teacherToken);

        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Algoritmi și structuri");
        assertThat(courses.findAll().get(0).getDescription()).isEqualTo("日本語 ✓");
    }

    // --- the client cannot choose the owner or the code ---------------------------------------

    @Test
    void anOwnerOrCodeSentByTheClientIsIgnored() throws Exception {
        signedInTeacher();
        User other = saved("other@example.com", Role.TEACHER);
        String body = "{\"title\":\"Mine\",\"teacherId\":" + other.getId() + ",\"teacher_id\":" + other.getId()
                + ",\"enrollCode\":\"AAAAAAAA\",\"enroll_code\":\"AAAAAAAA\",\"id\":42,\"createdAt\":\"2000-01-01T00:00:00Z\"}";

        HttpResponse<String> response = post("/courses", body, teacherToken);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(response.body(), "$.teacherId"))
                .isEqualTo(teacher.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.enrollCode")).isNotEqualTo("AAAAAAAA");
        assertThat(JsonPath.<Integer>read(response.body(), "$.id")).isNotEqualTo(42);
        assertThat(JsonPath.<String>read(response.body(), "$.createdAt")).doesNotStartWith("2000");
    }

    @Test
    void eachTeacherOwnsTheirOwnCourses() throws Exception {
        signedInTeacher();
        User other = saved("other@example.com", Role.TEACHER);

        post("/courses", "{\"title\":\"First\"}", teacherToken);
        post("/courses", "{\"title\":\"Second\"}", tokenFor(other));

        assertThat(courses.findAll())
                .extracting(Course::getTitle, Course::getTeacherId)
                .containsExactlyInAnyOrder(tuple("First", teacher.getId()), tuple("Second", other.getId()));
    }

    // --- codes -------------------------------------------------------------------------------

    @Test
    void everyCourseGetsADifferentCode() throws Exception {
        signedInTeacher();
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < 40; i++) {
            HttpResponse<String> response = post("/courses", "{\"title\":\"Course " + i + "\"}", teacherToken);
            assertThat(response.statusCode()).isEqualTo(201);
            codes.add(JsonPath.read(response.body(), "$.enrollCode"));
        }

        assertThat(codes).hasSize(40).allMatch(code -> code.matches(CODE_PATTERN));
        assertThat(courses.count()).isEqualTo(40);
    }

    // --- invalid input -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"description\":\"no title\"}",
                "{\"title\":null}",
                "{\"title\":\"\"}",
                "{\"title\":\"   \"}",
                "{\"title\":\"\\t\\n\"}",
            })
    void aMissingOrBlankTitleIsRefused(String body) throws Exception {
        signedInTeacher();

        HttpResponse<String> response = post("/courses", body, teacherToken);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("title");
        assertThat(courses.count()).isZero();
    }

    @Test
    void aTitleOverTheLimitIsRefusedNotAServerError() throws Exception {
        signedInTeacher();

        HttpResponse<String> response = post("/courses", "{\"title\":\"" + "t".repeat(256) + "\"}", teacherToken);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("title");
        assertThat(response.body()).doesNotContain("ttttttttt"); // the rejected value is never echoed
    }

    @Test
    void aDescriptionOverTheLimitIsRefused() throws Exception {
        signedInTeacher();

        HttpResponse<String> response =
                post("/courses", "{\"title\":\"A\",\"description\":\"" + "d".repeat(5001) + "\"}", teacherToken);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("description");
        assertThat(courses.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "\"title\"", "{\"title\":{\"a\":1}}", "{\"title\":["})
    void aBodyThatIsNotACourseIsAMalformedRequest(String body) throws Exception {
        signedInTeacher();

        HttpResponse<String> response = post("/courses", body, teacherToken);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(courses.count()).isZero();
    }

    @Test
    void aMissingBodyIsAMalformedRequest() throws Exception {
        signedInTeacher();

        HttpResponse<String> response = send("POST", "/courses", teacherToken, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    // --- who may call it ---------------------------------------------------------------------

    @Test
    void aStudentAnAdminAndAnonymousCannotCreateCourses() throws Exception {
        String body = "{\"title\":\"Algorithms\"}";

        HttpResponse<String> student = post("/courses", body, tokenFor(saved("s@example.com", Role.STUDENT)));
        HttpResponse<String> admin = post("/courses", body, tokenFor(saved("a@example.com", Role.ADMIN)));
        HttpResponse<String> anonymous = post("/courses", body, null);
        HttpResponse<String> badToken = post("/courses", body, "not.a.jwt");

        assertThat(student.statusCode()).isEqualTo(403);
        assertThat(admin.statusCode()).isEqualTo(403);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(badToken.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(student.body(), "$.code")).isEqualTo("ACCESS_DENIED");
        assertThat(courses.count()).isZero();
    }

    @Test
    void aTeacherWhoseAccountWasDeletedIsToldToSignInAgain() throws Exception {
        signedInTeacher();
        users.deleteAll(); // the token is still valid for 24h

        HttpResponse<String> response = post("/courses", "{\"title\":\"Algorithms\"}", teacherToken);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("ACCOUNT_NO_LONGER_EXISTS");
        assertThat(courses.count()).isZero();
    }

    @Test
    void aPromotedTeacherCanCreateCoursesAfterSigningInAgain() throws Exception {
        post(
                "/auth/signup",
                "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}",
                null);
        User ada = users.findByEmail("ada@example.com").orElseThrow();
        String studentToken = signinToken("ada@example.com");
        assertThat(post("/courses", "{\"title\":\"A\"}", studentToken).statusCode())
                .isEqualTo(403);

        patch(
                "/admin/users/" + ada.getId() + "/role",
                "{\"role\":\"TEACHER\"}",
                tokenFor(saved("root@example.com", Role.ADMIN)));
        String teacherToken = signinToken("ada@example.com");

        assertThat(post("/courses", "{\"title\":\"A\"}", teacherToken).statusCode())
                .isEqualTo(201);
    }

    @Test
    void otherMethodsOnTheCollectionAreNotAllowedYet() throws Exception {
        signedInTeacher();

        // GET /courses arrives in 3.1b; until then the path answers 405 and creates nothing.
        assertThat(send("PUT", "/courses", teacherToken, "{\"title\":\"A\"}").statusCode())
                .isEqualTo(405);
        assertThat(send("DELETE", "/courses", teacherToken, null).statusCode()).isEqualTo(405);
        assertThat(courses.count()).isZero();
    }

    private String signinToken(String email) throws Exception {
        return JsonPath.read(
                post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"correct horse\"}", null)
                        .body(),
                "$.token");
    }
}
