package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.5b: `GET /submissions/{id}` for the people who run the course (owner, admin): everything,
 * hidden tests included. Same URL as the student's view (5.5a), another shape picked by role.
 * Graded through {@link SubmissionResultWriter#store}, as the worker does.
 *
 * <p>The rubric: A ({@code TESTS}, 70) with a public test p1 and a hidden test h1 (weight 1 each);
 * M ({@code MANUAL}, 30).
 */
class RealHttpTeacherSubmissionViewTests extends RealHttpTestBase {

    private static final String[] SECRETS = {
        "SECRET-H-NAME", "SECRET-H-INPUT", "SECRET-H-EXPECTED", "SECRET-H-ACTUAL", "SECRET-H-STDERR"
    };

    @Autowired
    private SubmissionResultWriter writer;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Assignment assignment;
    private RubricCriterion tests;
    private RubricCriterion manual;
    private TestCase p1;
    private TestCase h1;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = assignments.save(new Assignment(
                course.getId(),
                "Sum",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", assignment.getId());
        tests = rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 70));
        manual = rubricCriteria.save(new RubricCriterion(assignment.getId(), "M", CriterionType.MANUAL, 30));
        p1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
        h1 = testCases.save(new TestCase(
                assignment.getId(), tests.getId(), SECRETS[0], SECRETS[1], SECRETS[2], TestVisibility.HIDDEN, 1, 2));
    }

    private long submission(String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(3)', 1, ?) returning id",
                Long.class,
                assignment.getId(),
                student.getId(),
                status);
    }

    /** Stores p1 passed and h1 failed with output and stderr of its own; the status is left as given. */
    private long withResults(String status) {
        long id = submission("RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.FAILED, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = ? where id = ?", status, id);
        return id;
    }

    private HttpResponse<String> view(User as, long submissionId) throws Exception {
        return get("/submissions/" + submissionId, tokenFor(as));
    }

    // --- what the people who run the course see ----------------------------------------------

    @Test
    void theOwnerSeesEverythingHiddenTestsIncluded() throws Exception {
        long id = withResults("GRADED");

        HttpResponse<String> response = view(owner, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        Map<String, Object> top = JsonPath.read(body, "$");
        assertThat(top)
                .containsOnlyKeys(
                        "id",
                        "assignmentId",
                        "studentId",
                        "attemptNo",
                        "status",
                        "language",
                        "sourceCode",
                        "submittedAt",
                        "startedAt",
                        "finishedAt",
                        "compilerOutput",
                        "grade",
                        "criteria",
                        "tests",
                        "hintsUsed")
                .containsEntry("status", "GRADED")
                .containsEntry("sourceCode", "print(3)");
        assertThat(JsonPath.<Number>read(body, "$.studentId").longValue()).isEqualTo(student.getId());
        // A = 70 × 1/2 = 35.00; M pending: 35.00 of 100.00, provisional.
        assertThat(body).contains("\"grade\":{\"totalScore\":35.00,\"maxScore\":100.00,\"provisional\":true,");
        assertThat(body)
                .contains("{\"id\":" + manual.getId() + ",\"name\":\"M\",\"type\":\"MANUAL\",\"weight\":30,"
                        + "\"score\":null,\"maxScore\":30.00,\"comment\":null}");

        Map<String, Object> hidden = JsonPath.read(body, "$.tests[1]");
        assertThat(hidden)
                .containsOnlyKeys(
                        "id",
                        "criterionId",
                        "name",
                        "input",
                        "expectedOutput",
                        "visibility",
                        "weight",
                        "position",
                        "status",
                        "actualOutput",
                        "stderr",
                        "runtimeMs")
                .containsEntry("name", SECRETS[0])
                .containsEntry("input", SECRETS[1])
                .containsEntry("expectedOutput", SECRETS[2])
                .containsEntry("visibility", "HIDDEN")
                .containsEntry("status", "FAILED")
                .containsEntry("actualOutput", SECRETS[3])
                .containsEntry("stderr", SECRETS[4])
                .containsEntry("runtimeMs", 13);
        assertThat(JsonPath.<Number>read(body, "$.tests[1].criterionId").longValue())
                .isEqualTo(tests.getId());
        assertThat(JsonPath.<String>read(body, "$.tests[0].name")).isEqualTo("adds two");
    }

    @Test
    void anAdminSeesExactlyWhatTheOwnerSees() throws Exception {
        long id = withResults("GRADED");

        HttpResponse<String> asAdmin = view(admin, id);

        assertThat(asAdmin.statusCode()).isEqualTo(200);
        assertThat(asAdmin.body()).isEqualTo(view(owner, id).body());
    }

    @Test
    void theCriterionIdsInTheViewAreTheOnesAManualScoreTakes() throws Exception {
        long id = withResults("GRADED");
        long criterionId = JsonPath.<Number>read(view(owner, id).body(), "$.criteria[1].id")
                .longValue();

        HttpResponse<String> saved = send(
                "PUT",
                "/submissions/" + id + "/manual-scores/" + criterionId,
                tokenFor(owner),
                "{\"score\":24,\"comment\":\"Clear names.\"}");

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        // 35.00 + 24.00 = 59.00, nothing pending.
        assertThat(view(owner, id).body())
                .contains("\"totalScore\":59.00", "\"provisional\":false", "\"comment\":\"Clear names.\"");
    }

    @Test
    void storedResultsShowOnlyOnceTheSubmissionIsGradedForTheTeacherToo() throws Exception {
        long id = withResults("RUNNING");

        Map<String, Object> top = JsonPath.read(view(owner, id).body(), "$");

        assertThat(top)
                .containsEntry("status", "RUNNING")
                .containsEntry("grade", null)
                .containsEntry("criteria", List.of())
                .containsEntry("tests", List.of());
    }

    // --- one URL, one authorization path, a shape per role ------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsAMissingSubmission() throws Exception {
        long id = withResults("GRADED");

        HttpResponse<String> other = view(otherTeacher, id);
        HttpResponse<String> missing = view(owner, 987654321L);

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(other.body(), "$.code")).isEqualTo("SUBMISSION_NOT_FOUND");
        assertThat(other.body()).isEqualTo(missing.body()).doesNotContain(SECRETS);
    }

    @Test
    void theStudentGetsTheirOwnShapeOnTheSameUrl() throws Exception {
        long id = withResults("GRADED");

        String body = view(student, id).body();

        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKey("studentId");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.tests[1]"))
                .containsOnlyKeys("id", "name", "visibility", "position", "passed");
        assertThat(body).doesNotContain(SECRETS);
    }
}
