package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionResultWriter;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.4e: saving a manual score recomputes the submission's grade through the scoring function, and
 * the grade is provisional until every manual criterion is scored. The submissions are graded by
 * {@link SubmissionResultWriter#store}, as the worker does, from results written here, so no
 * sandbox runs. Every expected total is worked out by hand in a comment.
 *
 * <p>The rubric: A ({@code TESTS}, 60) with tests t1 (weight 1) and t2 (weight 3); B ({@code
 * MANUAL}, 30); C ({@code MANUAL}, 10).
 */
class RealHttpManualRecomputeTests extends RealHttpTestBase {

    private static final Instant SAVED_AT = Instant.parse("2030-05-06T07:08:09.654321Z");

    @Autowired
    private SubmissionResultWriter writer;

    @Autowired
    private GradeRepository grades;

    @Autowired
    private DataSource dataSource;

    private User owner;
    private User student;
    private Course course;
    private Assignment assignment;
    private RubricCriterion b;
    private RubricCriterion c;
    private TestCase t1;
    private TestCase t2;

    @BeforeEach
    void world() {
        owner = saved("owner@example.com", Role.TEACHER);
        student = saved("student@example.com", Role.STUDENT);
        course = courses.save(new Course("Algorithms", null, owner.getId(), "ABCD2345"));
        enrollments.save(new Enrollment(course.getId(), student.getId()));
        assignment = publishedAssignment();
        RubricCriterion a = rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 60));
        b = rubricCriteria.save(new RubricCriterion(assignment.getId(), "B", CriterionType.MANUAL, 30));
        c = rubricCriteria.save(new RubricCriterion(assignment.getId(), "C", CriterionType.MANUAL, 10));
        t1 = testCases.save(new TestCase(assignment.getId(), a.getId(), "t1", "1", "1", TestVisibility.PUBLIC, 1, 1));
        t2 = testCases.save(new TestCase(assignment.getId(), a.getId(), "t2", "2", "2", TestVisibility.HIDDEN, 3, 2));
    }

    private Assignment publishedAssignment() {
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

    private long submission(Assignment to, int attemptNo, String status) {
        return jdbc.queryForObject(
                "insert into submissions (assignment_id, student_id, language, source_code, attempt_no, status)"
                        + " values (?, ?, 'PYTHON', 'print(1)', ?, ?) returning id",
                Long.class,
                to.getId(),
                student.getId(),
                attemptNo,
                status);
    }

    /** A submission graded as the worker grades it, with these outcomes for t1 and t2. */
    private long graded(int attemptNo, TestOutcome first, TestOutcome second) {
        long id = submission(assignment, attemptNo, "RUNNING");
        writer.store(
                id,
                assignment.getId(),
                List.of(t1, t2),
                List.of(
                        new TestResult(id, t1.getId(), first, "out", "", 5),
                        new TestResult(id, t2.getId(), second, "out", "", 5)));
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        return id;
    }

    private HttpResponse<String> score(long submissionId, RubricCriterion criterion, String points) throws Exception {
        return send(
                "PUT",
                "/submissions/" + submissionId + "/manual-scores/" + criterion.getId(),
                tokenFor(owner),
                "{\"score\":" + points + "}");
    }

    /** The grade in a 200 answer to a save, as JSON text. */
    private static String gradeIn(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        int start = response.body().indexOf("\"grade\":");
        return response.body().substring(start, response.body().indexOf('}', start) + 1);
    }

    private static String grade(String total, boolean provisional) {
        return "\"grade\":{\"totalScore\":" + total + ",\"maxScore\":100.00,\"provisional\":" + provisional + "}";
    }

    private Grade stored(long submissionId) {
        return grades.findBySubmissionId(submissionId).orElseThrow();
    }

    private HttpResponse<String> myGrade() throws Exception {
        HttpResponse<String> response = get("/assignments/" + assignment.getId() + "/my-grade", tokenFor(student));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    // --- recompute ---------------------------------------------------------------------------

    @Test
    void eachSaveRecomputesTheTotalAndTheLastPendingCriterionClearsTheFlag() throws Exception {
        // Graded: A = 60 × 1/(1 + 3) = 15.00; B and C pending. 15.00 of 100.00, provisional.
        long id = graded(1, TestOutcome.PASSED, TestOutcome.FAILED);
        assertThat(stored(id).getTotalScore()).isEqualTo(new BigDecimal("15.00"));
        assertThat(stored(id).isProvisional()).isTrue();
        clock.set(SAVED_AT);

        // B 22.50: 15.00 + 22.50 = 37.50, C still pending.
        assertThat(gradeIn(score(id, b, "22.5"))).isEqualTo(grade("37.50", true));
        // C 7: 15.00 + 22.50 + 7.00 = 44.50, nothing pending.
        assertThat(gradeIn(score(id, c, "7"))).isEqualTo(grade("44.50", false));
        // B again, 30: replaces 22.50, never adds to it. 15.00 + 30.00 + 7.00 = 52.00.
        assertThat(gradeIn(score(id, b, "30"))).isEqualTo(grade("52.00", false));

        Grade grade = stored(id);
        assertThat(grade.getTotalScore()).isEqualTo(new BigDecimal("52.00"));
        assertThat(grade.isProvisional()).isFalse();
        assertThat(grade.getGradedAt()).as("the time of the last recompute").isEqualTo(SAVED_AT);
        assertThat(grades.findAll()).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "select sum(score) from criterion_scores where submission_id = ?", BigDecimal.class, id))
                .as("the total is the sum of the criterion rows")
                .isEqualTo(new BigDecimal("52.00"));
        String mine = myGrade().body();
        assertThat(mine).contains("\"totalScore\":52.00", "\"provisional\":false");
    }

    @Test
    void theBestScoreFollowsTheRecomputedTotals() throws Exception {
        // #1: t2 passed, A = 60 × 3/4 = 45.00. #2: t1 passed, A = 60 × 1/4 = 15.00.
        long first = graded(1, TestOutcome.FAILED, TestOutcome.PASSED);
        long second = graded(2, TestOutcome.PASSED, TestOutcome.FAILED);
        assertThat(JsonPath.<Number>read(myGrade().body(), "$.best.submissionId")
                        .longValue())
                .isEqualTo(first);

        // #2 gets B 30 and C 10: 15.00 + 30.00 + 10.00 = 55.00, above #1's 45.00 (still provisional).
        score(second, b, "30");
        score(second, c, "10");

        String mine = myGrade().body();
        assertThat(JsonPath.<Number>read(mine, "$.best.submissionId").longValue())
                .isEqualTo(second);
        assertThat(mine).contains("\"totalScore\":55.00", "\"provisional\":false");
    }

    @Test
    void anAllManualRubricGetsItsGradeOnTheFirstSave() throws Exception {
        // The human's choice after 5.4d: no tests, so grading stored no grade; the first save makes it.
        Assignment essay = publishedAssignment();
        RubricCriterion x = rubricCriteria.save(new RubricCriterion(essay.getId(), "X", CriterionType.MANUAL, 70));
        RubricCriterion y = rubricCriteria.save(new RubricCriterion(essay.getId(), "Y", CriterionType.MANUAL, 30));
        long id = submission(essay, 1, "GRADED");
        String path = "/assignments/" + essay.getId() + "/my-grade";
        assertThat(get(path, tokenFor(student)).body()).contains("\"best\":null");

        // X 63.50, Y pending: 63.50 of 100.00, provisional. Then Y 30: 93.50.
        assertThat(gradeIn(score(id, x, "63.5"))).isEqualTo(grade("63.50", true));
        assertThat(get(path, tokenFor(student)).body()).contains("\"totalScore\":63.50", "\"provisional\":true");
        assertThat(gradeIn(score(id, y, "30"))).isEqualTo(grade("93.50", false));
        assertThat(grades.findAll()).hasSize(1);
    }

    @Test
    void aRefusedSaveLeavesTheGradeAsItWas() throws Exception {
        long id = graded(1, TestOutcome.PASSED, TestOutcome.FAILED);
        Grade before = stored(id);
        clock.set(SAVED_AT);

        assertThat(score(id, b, "30.01").statusCode()).isEqualTo(400);
        assertThat(send(
                                "PUT",
                                "/submissions/" + id + "/manual-scores/" + t1.getCriterionId(),
                                tokenFor(owner),
                                "{\"score\":1}")
                        .statusCode())
                .isEqualTo(400);

        Grade after = stored(id);
        assertThat(after.getTotalScore()).isEqualTo(before.getTotalScore());
        assertThat(after.getGradedAt()).isEqualTo(before.getGradedAt());
        assertThat(after.isProvisional()).isTrue();
    }

    // --- one save at a time ------------------------------------------------------------------

    @Test
    void aSaveWaitsForAnotherSaveOfTheSameSubmissionAndCountsItsScore() throws Exception {
        long id = graded(1, TestOutcome.PASSED, TestOutcome.FAILED);
        // Another save is part-way through: it holds the submission's lock (as every save does) and
        // has stored C = 10, uncommitted.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from submissions where id = " + id + " for no key update");
                statement.executeUpdate("insert into criterion_scores (submission_id, criterion_id, score, max_score)"
                        + " values (" + id + ", " + c.getId() + ", 10.00, 10.00)");

                CompletableFuture<HttpResponse<String>> saving = CompletableFuture.supplyAsync(() -> {
                    try {
                        return score(id, b, "30");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                Thread.sleep(500);
                assertThat(saving)
                        .as("the save must wait, not total the scores without C")
                        .isNotDone();

                connection.commit();
                // 15.00 + 30.00 + 10.00 = 55.00: C counted, nothing pending.
                assertThat(gradeIn(saving.get(10, TimeUnit.SECONDS))).isEqualTo(grade("55.00", false));
            }
        }
        assertThat(stored(id).getTotalScore()).isEqualTo(new BigDecimal("55.00"));
    }
}
