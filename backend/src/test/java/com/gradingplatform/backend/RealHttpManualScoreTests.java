package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.CriterionScoreRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.4d: `PUT /submissions/{id}/manual-scores/{criterionId}`, a teacher's score for a {@code MANUAL}
 * criterion, over real HTTP. Submissions are inserted by SQL with their final status, so no worker
 * grades them.
 */
class RealHttpManualScoreTests extends RealHttpTestBase {

    @Autowired
    private CriterionScoreRepository criterionScores;

    private User owner;
    private User otherTeacher;
    private User admin;
    private User student;
    private Assignment assignment;
    private RubricCriterion tests;
    private RubricCriterion manual;
    private RubricCriterion otherManual;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        otherTeacher = saved("other@example.com", Role.TEACHER);
        admin = saved("root@example.com", Role.ADMIN);
        student = saved("student@example.com", Role.STUDENT);
        Course course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = newAssignment(course);
        tests = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Tests", CriterionType.TESTS, 80));
        manual = rubricCriteria.save(new RubricCriterion(assignment.getId(), "Readability", CriterionType.MANUAL, 20));
        Assignment other = newAssignment(course);
        otherManual = rubricCriteria.save(new RubricCriterion(other.getId(), "Design", CriterionType.MANUAL, 20));
    }

    private Assignment newAssignment(Course course) {
        Assignment saved = assignments.save(new Assignment(
                course.getId(),
                "Sorting",
                "d",
                "PYTHON",
                Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                null,
                2000,
                256,
                null));
        jdbc.update("update assignments set published = true where id = ?", saved.getId());
        return saved;
    }

    /** The student's submission, already in its final {@code status}; returns its id. */
    private long submission(int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', ?, ?) returning id",
                Long.class,
                assignment.getId(),
                student.getId(),
                attemptNo,
                status);
    }

    private long graded() {
        return submission(1, "GRADED");
    }

    private HttpResponse<String> score(User as, long submissionId, long criterionId, String json) throws Exception {
        return send(
                "PUT",
                "/submissions/" + submissionId + "/manual-scores/" + criterionId,
                as == null ? null : tokenFor(as),
                json);
    }

    private HttpResponse<String> score(User as, long submissionId, String json) throws Exception {
        return score(as, submissionId, manual.getId(), json);
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private CriterionScore stored(long submissionId) {
        return criterionScores
                .findBySubmissionIdAndCriterionId(submissionId, manual.getId())
                .orElseThrow();
    }

    // --- stored ------------------------------------------------------------------------------

    @Test
    void theOwnerScoresAManualCriterionWithAComment() throws Exception {
        long id = graded();

        HttpResponse<String> response = score(owner, id, "{\"score\":12.5,\"comment\":\"Clear names, long main.\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body)
                .containsOnlyKeys("submissionId", "criterionId", "score", "maxScore", "comment")
                .containsEntry("comment", "Clear names, long main.");
        assertThat(JsonPath.<Number>read(response.body(), "$.submissionId").longValue())
                .isEqualTo(id);
        assertThat(JsonPath.<Number>read(response.body(), "$.criterionId").longValue())
                .isEqualTo(manual.getId());
        assertThat(response.body()).contains("\"score\":12.50", "\"maxScore\":20.00");
        CriterionScore row = stored(id);
        assertThat(row.getScore()).isEqualTo(new BigDecimal("12.50"));
        assertThat(row.getMaxScore()).as("the criterion's weight").isEqualTo(new BigDecimal("20.00"));
        assertThat(row.getComment()).isEqualTo("Clear names, long main.");
        assertThat(criterionScores.findAll()).hasSize(1);
    }

    @Test
    void savingAgainReplacesTheScoreAndItsComment() throws Exception {
        long id = graded();
        assertThat(score(owner, id, "{\"score\":12.5,\"comment\":\"first\"}").statusCode())
                .isEqualTo(200);

        HttpResponse<String> response = score(owner, id, "{\"score\":15}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<Object>read(response.body(), "$.comment")).isNull();
        assertThat(criterionScores.findAll()).hasSize(1);
        assertThat(stored(id).getScore()).isEqualTo(new BigDecimal("15.00"));
        assertThat(stored(id).getComment()).isNull();
    }

    @Test
    void aBlankCommentIsStoredAsNone() throws Exception {
        long id = graded();

        assertThat(score(owner, id, "{\"score\":3,\"comment\":\"  \\n \"}").statusCode())
                .isEqualTo(200);

        assertThat(stored(id).getComment()).isNull();
    }

    @Test
    void anAdminMayScoreToo() throws Exception {
        long id = graded();

        assertThat(score(admin, id, "{\"score\":7}").statusCode()).isEqualTo(200);
        assertThat(stored(id).getScore()).isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void anyAttemptMayBeScoredNotOnlyTheBest() throws Exception {
        long best = submission(1, "GRADED");
        long weaker = submission(2, "GRADED");
        jdbc.update("insert into grades (submission_id, total_score, max_score) values (?, 80.00, 100.00)", best);
        jdbc.update("insert into grades (submission_id, total_score, max_score) values (?, 10.00, 100.00)", weaker);

        assertThat(score(owner, weaker, "{\"score\":5}").statusCode()).isEqualTo(200);
        assertThat(stored(weaker).getScore()).isEqualTo(new BigDecimal("5.00"));
    }

    // --- the score's range -------------------------------------------------------------------

    @Test
    void zeroAndTheWeightAreTheBounds() throws Exception {
        long id = graded();

        assertThat(score(owner, id, "{\"score\":0}").statusCode()).isEqualTo(200);
        assertThat(stored(id).getScore()).isEqualTo(new BigDecimal("0.00"));
        assertThat(score(owner, id, "{\"score\":20.00}").statusCode()).isEqualTo(200);
        assertThat(stored(id).getScore()).isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    void aScoreOutsideTheRangeIsRefusedAndChangesNothing() throws Exception {
        long id = graded();
        assertThat(score(owner, id, "{\"score\":10,\"comment\":\"kept\"}").statusCode())
                .isEqualTo(200);

        for (String outside : new String[] {"-0.01", "20.01", "100"}) {
            HttpResponse<String> response = score(owner, id, "{\"score\":" + outside + ",\"comment\":\"new\"}");
            assertThat(response.statusCode()).as(outside).isEqualTo(400);
            assertThat(code(response)).as(outside).isEqualTo("MANUAL_SCORE_OUT_OF_RANGE");
        }

        assertThat(stored(id).getScore()).isEqualTo(new BigDecimal("10.00"));
        assertThat(stored(id).getComment()).isEqualTo("kept");
    }

    @Test
    void malformedScoresAndCommentsAreValidationErrors() throws Exception {
        long id = graded();
        String tooLong = "x".repeat(5001);

        for (String body : new String[] {
            "{\"score\":1.005}", "{}", "{\"score\":null}", "{\"score\":1,\"comment\":\"" + tooLong + "\"}"
        }) {
            HttpResponse<String> response = score(owner, id, body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(code(response)).as(body).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(score(owner, id, "not json").statusCode()).isEqualTo(400);
        assertThat(score(owner, id, "{\"score\":1,\"comment\":\"" + "x".repeat(5000) + "\"}")
                        .statusCode())
                .as("exactly the maximum")
                .isEqualTo(200);
    }

    // --- what can be scored ------------------------------------------------------------------

    @Test
    void aTestsCriterionIsNotScoredByHand() throws Exception {
        long id = graded();

        HttpResponse<String> response = score(owner, id, tests.getId(), "{\"score\":1}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("CRITERION_NOT_MANUAL");
        assertThat(criterionScores.findAll()).isEmpty();
    }

    @Test
    void aCriterionOfAnotherAssignmentIsTheSame404AsAMissingOne() throws Exception {
        long id = graded();

        HttpResponse<String> elsewhere = score(owner, id, otherManual.getId(), "{\"score\":1}");
        HttpResponse<String> missing = score(owner, id, 987654321L, "{\"score\":1}");

        assertThat(elsewhere.statusCode()).isEqualTo(404);
        assertThat(code(elsewhere)).isEqualTo("CRITERION_NOT_FOUND");
        assertThat(elsewhere.body()).isEqualTo(missing.body());
        assertThat(criterionScores.findAll()).isEmpty();
    }

    @Test
    void aSubmissionThatIsNotGradedCannotBeScored() throws Exception {
        int attempt = 1;
        for (String status : new String[] {"QUEUED", "RUNNING", "ERROR"}) {
            long id = submission(attempt++, status);

            HttpResponse<String> response = score(owner, id, "{\"score\":1}");

            assertThat(response.statusCode()).as(status).isEqualTo(409);
            assertThat(code(response)).as(status).isEqualTo("SUBMISSION_NOT_GRADED");
        }
        assertThat(criterionScores.findAll()).isEmpty();
    }

    // --- who may score -----------------------------------------------------------------------

    @Test
    void anotherTeacherGetsTheSame404AsAMissingSubmission() throws Exception {
        long id = graded();
        HttpResponse<String> missing = score(owner, 987654321L, "{\"score\":1}");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("SUBMISSION_NOT_FOUND");

        // Whatever the criterion or score, nothing about the submission shows.
        assertThat(score(otherTeacher, id, "{\"score\":1}").body()).isEqualTo(missing.body());
        assertThat(score(otherTeacher, id, "{\"score\":99}").body()).isEqualTo(missing.body());
        assertThat(score(otherTeacher, id, tests.getId(), "{\"score\":1}").body())
                .isEqualTo(missing.body());
        assertThat(criterionScores.findAll()).isEmpty();
    }

    @Test
    void theStudentIsRefusedByRoleAndNobodyIsAskedToSignIn() throws Exception {
        long id = graded();

        HttpResponse<String> own = score(student, id, "{\"score\":20}");
        assertThat(own.statusCode()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("ACCESS_DENIED");
        HttpResponse<String> anonymous = score(null, id, "{\"score\":20}");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(code(anonymous)).isEqualTo("UNAUTHENTICATED");
        assertThat(criterionScores.findAll()).isEmpty();
    }
}
