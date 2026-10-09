package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AssignmentRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 3.3a: `POST /courses/{id}/assignments`. */
class RealHttpAssignmentCreateTests extends RealHttpTestBase {

    @Autowired
    AssignmentRepository assignmentRepository;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Course course;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
    }

    private String path() {
        return "/courses/" + course.getId() + "/assignments";
    }

    private static String deadline() {
        return Instant.now()
                .plus(7, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    /** A valid body; each test overrides what it is about. */
    private static String body(String... overrides) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("title", "\"Sorting\"");
        fields.put("description", "\"Sort the numbers\"");
        fields.put("language", "\"JAVA\"");
        fields.put("deadline", "\"" + deadline() + "\"");
        fields.put("maxAttempts", "3");
        fields.put("timeLimitMs", "2000");
        fields.put("memoryLimitMb", "256");
        fields.put("starterCode", "\"class Main {}\"");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        StringBuilder json = new StringBuilder("{");
        fields.forEach((k, v) -> {
            if (v != null) {
                json.append(json.length() > 1 ? "," : "")
                        .append('"')
                        .append(k)
                        .append("\":")
                        .append(v);
            }
        });
        return json.append('}').toString();
    }

    private HttpResponse<String> create(User as, String json) throws Exception {
        return post(path(), json, tokenFor(as));
    }

    // --- creating ----------------------------------------------------------------------------

    @Test
    void theOwnerCreatesAnUnpublishedAssignment() throws Exception {
        HttpResponse<String> response = create(owner, body());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(response.body(), "$.courseId"))
                .isEqualTo(course.getId().intValue());
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Sorting");
        assertThat(JsonPath.<String>read(response.body(), "$.language")).isEqualTo("JAVA");
        assertThat(JsonPath.<Integer>read(response.body(), "$.maxAttempts")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(response.body(), "$.timeLimitMs")).isEqualTo(2000);
        assertThat(JsonPath.<Integer>read(response.body(), "$.memoryLimitMb")).isEqualTo(256);
        assertThat(JsonPath.<String>read(response.body(), "$.starterCode")).isEqualTo("class Main {}");
        assertThat(JsonPath.<Boolean>read(response.body(), "$.published")).isFalse();
        assertThat(JsonPath.<String>read(response.body(), "$.createdAt")).isNotBlank();
        assertThat(JsonPath.<String>read(response.body(), "$.updatedAt")).isNotBlank();

        List<Assignment> stored = assignmentRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCourseId()).isEqualTo(course.getId());
        assertThat(stored.get(0).isPublished()).isFalse();
        assertThat(stored.get(0).getId())
                .isEqualTo(JsonPath.<Integer>read(response.body(), "$.id").longValue());
    }

    @Test
    void theStoredDeadlineIsTheOneSent() throws Exception {
        String sent = deadline();

        create(owner, body("deadline", "\"" + sent + "\""));

        assertThat(assignmentRepository.findAll().get(0).getDeadline()).isEqualTo(Instant.parse(sent));
    }

    @Test
    void attemptsAndStarterCodeAreOptional() throws Exception {
        HttpResponse<String> response = create(owner, body("maxAttempts", null, "starterCode", null));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(response.body(), "$.maxAttempts")).isNull();
        assertThat(JsonPath.<Object>read(response.body(), "$.starterCode")).isNull();
    }

    @Test
    void blankStarterCodeIsNoStarterCodeAndTheTitleIsTrimmed() throws Exception {
        HttpResponse<String> response = create(owner, body("starterCode", "\"  \\n \"", "title", "\"  Sorting  \""));

        assertThat(JsonPath.<Object>read(response.body(), "$.starterCode")).isNull();
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Sorting");
    }

    @ParameterizedTest
    @ValueSource(strings = {"python", "Python", " PYTHON "})
    void theLanguageIsStoredInTheListsSpelling(String typed) throws Exception {
        HttpResponse<String> response = create(owner, body("language", "\"" + typed + "\""));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.language")).isEqualTo("PYTHON");
    }

    @Test
    void thereCanBeSeveralAssignmentsInOneCourse() throws Exception {
        create(owner, body());
        create(owner, body("title", "\"Searching\""));

        assertThat(assignmentRepository.count()).isEqualTo(2);
    }

    @Test
    void courseIdPublishedAndIdInTheBodyAreIgnored() throws Exception {
        Course other = courses.save(new Course("Other", null, owner.getId(), "WXYZ6789"));

        HttpResponse<String> response =
                create(owner, body("courseId", String.valueOf(other.getId()), "published", "true", "id", "999"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.published")).isFalse();
        assertThat(JsonPath.<Integer>read(response.body(), "$.courseId"))
                .isEqualTo(course.getId().intValue());
        assertThat(JsonPath.<Integer>read(response.body(), "$.id")).isNotEqualTo(999);
    }

    @Test
    void theLimitsAtTheirMaximumAreAccepted() throws Exception {
        HttpResponse<String> response = create(
                owner,
                body(
                        "maxAttempts", String.valueOf(AssignmentRequest.MAX_ATTEMPTS_MAX),
                        "timeLimitMs", String.valueOf(AssignmentRequest.TIME_LIMIT_MAX_MS),
                        "memoryLimitMb", String.valueOf(AssignmentRequest.MEMORY_LIMIT_MAX_MB)));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    // --- the language ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"COBOL", "C++", "JAVA 17", "J AVA", "JAVASCRIPT"})
    void anUnsupportedLanguageIsRefusedAndNotEchoed(String language) throws Exception {
        HttpResponse<String> response = create(owner, body("language", "\"" + language + "\""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("UNSUPPORTED_LANGUAGE");
        assertThat(response.body()).doesNotContain(language);
        assertThat(assignmentRepository.count()).isZero();
    }

    // --- the deadline ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "1970-01-01T00:00:00Z"})
    void aDeadlineInThePastIsRefused(String past) throws Exception {
        HttpResponse<String> response = create(owner, body("deadline", "\"" + past + "\""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("DEADLINE_NOT_IN_FUTURE");
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void aDeadlineAMomentAgoIsRefused() throws Exception {
        String justPassed = Instant.now().minusSeconds(1).toString();

        assertThat(create(owner, body("deadline", "\"" + justPassed + "\"")).statusCode())
                .isEqualTo(400);
    }

    // --- invalid input -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "title",
                "description",
                "language",
                "deadline",
                "timeLimitMs",
                "memoryLimitMb",
            })
    void aMissingRequiredFieldIsNamedInTheError(String field) throws Exception {
        HttpResponse<String> response = create(owner, body(field, null));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly(field);
        assertThat(assignmentRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "language"})
    void aBlankTextFieldIsRefused(String field) throws Exception {
        HttpResponse<String> response = create(owner, body(field, "\"   \""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly(field);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "maxAttempts:0",
                "maxAttempts:-1",
                "maxAttempts:101",
                "timeLimitMs:0",
                "timeLimitMs:-5",
                "timeLimitMs:10001",
                "memoryLimitMb:0",
                "memoryLimitMb:1025",
                "memoryLimitMb:2147483648",
            })
    void aLimitOutsideTheSaneRangeIsRefused(String fieldAndValue) throws Exception {
        String[] parts = fieldAndValue.split(":");

        HttpResponse<String> response = create(owner, body(parts[0], parts[1]));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void textOverTheCapsIsRefused() throws Exception {
        String longTitle = "\"" + "t".repeat(AssignmentRequest.TITLE_MAX + 1) + "\"";
        String longDescription = "\"" + "d".repeat(AssignmentRequest.DESCRIPTION_MAX + 1) + "\"";
        String longStarter = "\"" + "s".repeat(AssignmentRequest.STARTER_CODE_MAX + 1) + "\"";
        String longLanguage = "\"" + "L".repeat(AssignmentRequest.LANGUAGE_MAX + 1) + "\"";

        assertThat(create(owner, body("title", longTitle)).statusCode()).isEqualTo(400);
        assertThat(create(owner, body("description", longDescription)).statusCode())
                .isEqualTo(400);
        assertThat(create(owner, body("starterCode", longStarter)).statusCode()).isEqualTo(400);
        assertThat(create(owner, body("language", longLanguage)).statusCode()).isEqualTo(400);
        assertThat(assignmentRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"timeLimitMs\":\"fast\"}", "{\"deadline\":\"tomorrow\"}", "{\"x\":"})
    void aMalformedBodyIsA400(String json) throws Exception {
        assertThat(create(owner, json).statusCode()).isEqualTo(400);
        assertThat(assignmentRepository.count()).isZero();
    }

    // --- who may create ----------------------------------------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsForAMissingCourse() throws Exception {
        HttpResponse<String> notTheirs = create(otherTeacher, body());
        HttpResponse<String> missing = post("/courses/987654321/assignments", body(), tokenFor(otherTeacher));

        assertThat(notTheirs.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(notTheirs.body(), "$.code")).isEqualTo("COURSE_NOT_FOUND");
        assertThat(notTheirs.body()).isEqualTo(missing.body());
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void aCallerWhoCannotSeeTheCourseLearnsNothingFromAnInvalidBody() throws Exception {
        // Not even "unsupported language": the course check comes before the rest.
        HttpResponse<String> response = create(otherTeacher, body("language", "\"COBOL\""));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void studentsAndAdminsAreRefused() throws Exception {
        assertThat(create(student, body()).statusCode()).isEqualTo(403);
        assertThat(create(admin, body()).statusCode()).isEqualTo(403);
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void nobodyWithoutAValidTokenCanCreate() throws Exception {
        assertThat(post(path(), body(), null).statusCode()).isEqualTo(401);
        assertThat(post(path(), body(), "not.a.jwt").statusCode()).isEqualTo(401);
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void aMalformedCourseIdIsA400() throws Exception {
        assertThat(post("/courses/abc/assignments", body(), tokenFor(owner)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void theCourseCannotBeDeletedOnceItHasAnAssignment() throws Exception {
        create(owner, body());

        HttpResponse<String> response = send("DELETE", "/courses/" + course.getId(), tokenFor(owner), null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURSE_HAS_ASSIGNMENTS");
    }
}
