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
 * 5.5a: `GET /submissions/{id}`, a student's view of their own submission, over real HTTP. Graded
 * through {@link SubmissionResultWriter#store}, as the worker does, from results written here.
 *
 * <p>The rubric: A ({@code TESTS}, 70) with a public test p1 and a hidden test h1 (weight 1 each);
 * M ({@code MANUAL}, 30). Every string of h1 and of its result is a secret that must not appear in
 * the student's JSON.
 */
class RealHttpStudentSubmissionViewTests extends RealHttpTestBase {

    private static final String[] SECRETS = {
        "SECRET-H-NAME", "SECRET-H-INPUT", "SECRET-H-EXPECTED", "SECRET-H-ACTUAL", "SECRET-H-STDERR"
    };

    @Autowired
    private SubmissionResultWriter writer;

    private User owner;
    private User student;
    private User classmate;
    private Course course;
    private Assignment assignment;
    private RubricCriterion manual;
    private TestCase p1;
    private TestCase h1;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        classmate = saved("classmate@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        enrollments.save(new Enrollment(course.getId(), classmate.getId()));
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
        RubricCriterion tests =
                rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 70));
        manual = rubricCriteria.save(new RubricCriterion(assignment.getId(), "M", CriterionType.MANUAL, 30));
        p1 = testCases.save(
                new TestCase(assignment.getId(), tests.getId(), "adds two", "1 2", "3", TestVisibility.PUBLIC, 1, 1));
        h1 = testCases.save(new TestCase(
                assignment.getId(), tests.getId(), SECRETS[0], SECRETS[1], SECRETS[2], TestVisibility.HIDDEN, 1, 2));
    }

    private long submission(User by, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(sum(map(int, input().split())))', 1, ?) returning id",
                Long.class,
                assignment.getId(),
                by.getId(),
                status);
    }

    /** Graded as the worker grades it: p1 passed, h1 failed with output and stderr of its own. */
    private long graded(User by) {
        long id = submission(by, "RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "a warning", 12),
                        new TestResult(id, h1.getId(), TestOutcome.FAILED, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }

    private HttpResponse<String> view(User as, long submissionId) throws Exception {
        return get("/submissions/" + submissionId, as == null ? null : tokenFor(as));
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    // --- the student's view ------------------------------------------------------------------

    @Test
    void theStudentSeesTheirGradedSubmissionWithHiddenTestsReducedToPassOrFail() throws Exception {
        long id = graded(student);

        HttpResponse<String> response = view(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        Map<String, Object> top = JsonPath.read(body, "$");
        assertThat(top)
                .containsOnlyKeys(
                        "id",
                        "assignmentId",
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
                .containsEntry("attemptNo", 1)
                .containsEntry("language", "PYTHON")
                .containsEntry("sourceCode", "print(sum(map(int, input().split())))")
                .containsEntry("compilerOutput", null);
        // A = 70 × 1/2 = 35.00; M pending. 35.00 of 100.00, provisional.
        assertThat(body).contains("\"grade\":{\"totalScore\":35.00,\"maxScore\":100.00,\"provisional\":true,");
        assertThat(body)
                .contains("\"criteria\":[{\"name\":\"A\",\"type\":\"TESTS\",\"score\":35.00,\"maxScore\":70.00,"
                        + "\"comment\":null},{\"name\":\"M\",\"type\":\"MANUAL\",\"score\":null,\"maxScore\":30.00,"
                        + "\"comment\":null}]");

        Map<String, Object> visible = JsonPath.read(body, "$.tests[0]");
        assertThat(visible)
                .containsOnlyKeys(
                        "id",
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
                .containsEntry("name", "adds two")
                .containsEntry("input", "1 2")
                .containsEntry("expectedOutput", "3")
                .containsEntry("status", "PASSED")
                .containsEntry("actualOutput", "3\n")
                .containsEntry("stderr", "a warning")
                .containsEntry("runtimeMs", 12);
        Map<String, Object> hidden = JsonPath.read(body, "$.tests[1]");
        assertThat(hidden)
                .containsOnlyKeys("id", "name", "visibility", "position", "passed")
                .containsEntry("name", "Hidden test 1")
                .containsEntry("visibility", "HIDDEN")
                .containsEntry("passed", false);
        assertThat(JsonPath.<List<Object>>read(body, "$.tests")).hasSize(2);
        assertThat(body).doesNotContain(SECRETS);
    }

    @Test
    void aHiddenTestThatPassedSaysSoAndNothingMore() throws Exception {
        long id = submission(student, "RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.FAILED, "4\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.PASSED, SECRETS[3], SECRETS[4], 13)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);

        String body = view(student, id).body();

        assertThat(JsonPath.<Boolean>read(body, "$.tests[1].passed")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.tests[0].status")).isEqualTo("FAILED");
        assertThat(body).doesNotContain(SECRETS);
    }

    @Test
    void aTeachersManualScoreShowsWithItsComment() throws Exception {
        long id = graded(student);
        send(
                "PUT",
                "/submissions/" + id + "/manual-scores/" + manual.getId(),
                tokenFor(owner),
                "{\"score\":24,\"comment\":\"Clear names.\"}");

        String body = view(student, id).body();

        // 35.00 + 24.00 = 59.00, nothing pending.
        assertThat(body).contains("\"grade\":{\"totalScore\":59.00,\"maxScore\":100.00,\"provisional\":false,");
        assertThat(body)
                .contains("{\"name\":\"M\",\"type\":\"MANUAL\",\"score\":24.00,\"maxScore\":30.00,"
                        + "\"comment\":\"Clear names.\"}");
    }

    @Test
    void aCompileErrorShowsTheCompilerOutputOnce() throws Exception {
        long id = submission(student, "RUNNING");
        String compiler = "Main.java:3: error: ';' expected";
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.COMPILE_ERROR, null, compiler, null),
                        new TestResult(id, h1.getId(), TestOutcome.COMPILE_ERROR, null, compiler, null)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);

        String body = view(student, id).body();

        assertThat(JsonPath.<String>read(body, "$.compilerOutput")).isEqualTo(compiler);
        assertThat(JsonPath.<String>read(body, "$.tests[0].status")).isEqualTo("COMPILE_ERROR");
        assertThat(JsonPath.<Object>read(body, "$.tests[0].actualOutput")).isNull();
        assertThat(JsonPath.<Boolean>read(body, "$.tests[1].passed")).isFalse();
        assertThat(body).contains("\"totalScore\":0.00");
    }

    @Test
    void untilItIsGradedThereIsNoResult() throws Exception {
        for (String status : new String[] {"RUNNING", "ERROR"}) {
            long id = submission(student, status);

            HttpResponse<String> response = view(student, id);

            assertThat(response.statusCode()).as(status).isEqualTo(200);
            Map<String, Object> top = JsonPath.read(response.body(), "$");
            assertThat(top)
                    .as(status)
                    .containsEntry("status", status)
                    .containsEntry("grade", null)
                    .containsEntry("compilerOutput", null)
                    .containsEntry("criteria", List.of())
                    .containsEntry("tests", List.of());
            jdbc.update("delete from submissions where id = ?", id);
        }
    }

    @Test
    void storedResultsShowOnlyOnceTheSubmissionIsGraded() throws Exception {
        // The worker stores results and grade, then marks the submission GRADED (5.2b): in between
        // it is still RUNNING with everything stored. Nothing shows until the status says so.
        long id = submission(student, "RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(p1, h1),
                List.of(
                        new TestResult(id, p1.getId(), TestOutcome.PASSED, "3\n", "", 12),
                        new TestResult(id, h1.getId(), TestOutcome.PASSED, SECRETS[3], SECRETS[4], 13)));

        Map<String, Object> top = JsonPath.read(view(student, id).body(), "$");

        assertThat(top)
                .containsEntry("status", "RUNNING")
                .containsEntry("grade", null)
                .containsEntry("criteria", List.of())
                .containsEntry("tests", List.of());
    }

    @Test
    void aGradedSubmissionWithoutAGradeShowsItsCriteriaPending() throws Exception {
        // Graded with nothing stored (no tests run, 5.3a), before any teacher's score.
        long id = submission(student, "GRADED");

        String body = view(student, id).body();

        assertThat(JsonPath.<Object>read(body, "$.grade")).isNull();
        assertThat(JsonPath.<List<Object>>read(body, "$.criteria[*].score")).containsExactly(null, null);
        assertThat(JsonPath.<List<Object>>read(body, "$.tests")).isEmpty();
    }

    @Test
    void theStudentStillSeesTheirOwnWorkAfterLeavingTheCourseOrAnUnpublish() throws Exception {
        long id = graded(student);
        jdbc.update("delete from enrollments where course_id = ? and student_id = ?", course.getId(), student.getId());
        jdbc.update("update assignments set published = false where id = ?", assignment.getId());

        HttpResponse<String> response = view(student, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(SECRETS);
    }

    // --- who may read it ---------------------------------------------------------------------

    @Test
    void anotherStudentsSubmissionIsA403AndAMissingOneA404() throws Exception {
        long id = graded(student);

        HttpResponse<String> other = view(classmate, id);
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(code(other)).isEqualTo("ACCESS_DENIED");
        assertThat(other.body()).doesNotContain(SECRETS).doesNotContain("print(sum");

        HttpResponse<String> missing = view(student, 987654321L);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("SUBMISSION_NOT_FOUND");
    }

    @Test
    void nobodyIsAskedToSignIn() throws Exception {
        // The people who run the course get their own view since 5.5b (RealHttpTeacherSubmissionViewTests).
        long id = graded(student);

        HttpResponse<String> anonymous = view(null, id);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(code(anonymous)).isEqualTo("UNAUTHENTICATED");
    }
}
