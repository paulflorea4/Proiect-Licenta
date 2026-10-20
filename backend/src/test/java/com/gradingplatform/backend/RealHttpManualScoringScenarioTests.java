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
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.6e: manual scoring as a teacher and a student live it, over real HTTP. The single rules are in
 * {@code RealHttpManualScoreTests} (5.4d: validation, access) and {@code
 * RealHttpManualRecomputeTests} (5.4e: recompute, the flag); this adds what only a scenario shows:
 * the range is each criterion's <i>own</i> weight, the total is the sum of the <i>rounded</i>
 * scores, a lowered score lowers the total and the best attempt follows, every view of the grade
 * (the submission, both lists, {@code my-grade}) agrees after each save, and saves that arrive
 * together are all counted. Every expected number is worked out by hand in a comment.
 *
 * <p>The rubric: A ({@code TESTS}, 60) with t1 (weight 1) and t2 (weight 3); B ({@code MANUAL},
 * 30); C ({@code MANUAL}, 10). A graded submission with t1 passed and t2 failed has A = 60 × 1/4 =
 * 15.00, with t2 passed and t1 failed A = 60 × 3/4 = 45.00.
 */
class RealHttpManualScoringScenarioTests extends RealHttpTestBase {

    @Autowired
    private SubmissionResultWriter writer;

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
        assignment = publishedAssignment("Sorting");
        RubricCriterion a = rubricCriteria.save(new RubricCriterion(assignment.getId(), "A", CriterionType.TESTS, 60));
        b = rubricCriteria.save(new RubricCriterion(assignment.getId(), "B", CriterionType.MANUAL, 30));
        c = rubricCriteria.save(new RubricCriterion(assignment.getId(), "C", CriterionType.MANUAL, 10));
        t1 = testCases.save(new TestCase(assignment.getId(), a.getId(), "t1", "1", "1", TestVisibility.PUBLIC, 1, 1));
        t2 = testCases.save(new TestCase(assignment.getId(), a.getId(), "t2", "2", "2", TestVisibility.HIDDEN, 3, 2));
    }

    private Assignment publishedAssignment(String title) {
        Assignment saved = assignments.save(new Assignment(
                course.getId(),
                title,
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

    /** A submission of the first assignment graded as the worker grades it. */
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
                "{\"score\":" + points + ",\"comment\":\"c" + points + "\"}");
    }

    private BigDecimal total(long submissionId) {
        return jdbc.queryForObject(
                "select total_score from grades where submission_id = ?", BigDecimal.class, submissionId);
    }

    private boolean provisional(long submissionId) {
        return jdbc.queryForObject(
                "select provisional from grades where submission_id = ?", Boolean.class, submissionId);
    }

    private static BigDecimal number(String json, String path) {
        return new BigDecimal(JsonPath.<Number>read(json, path).toString());
    }

    // ---- range validation ----

    @Test
    void theRangeIsTheCriterionsOwnWeightNotAnotherCriterionsOrTheRubricsLargest() throws Exception {
        long id = graded(1, TestOutcome.PASSED, TestOutcome.FAILED); // A = 15.00

        // B (weight 30): 15 is fine and 30 is the top. C (weight 10): 15 is fine for B but not for C.
        assertThat(score(id, b, "15").statusCode()).isEqualTo(200);
        HttpResponse<String> tooMuchForC = score(id, c, "15");
        assertThat(tooMuchForC.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tooMuchForC.body(), "$.code")).isEqualTo("MANUAL_SCORE_OUT_OF_RANGE");
        assertThat(score(id, c, "10.01").statusCode())
                .as("one hundredth over C's weight")
                .isEqualTo(400);
        assertThat(score(id, b, "30.01").statusCode())
                .as("one hundredth over B's weight")
                .isEqualTo(400);
        assertThat(score(id, c, "-0.01").statusCode()).as("below zero").isEqualTo(400);

        // Nothing refused counted: A 15.00 + B 15.00, C still pending = 30.00 of 100.00, provisional.
        assertThat(total(id)).isEqualByComparingTo("30.00");
        assertThat(provisional(id)).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from criterion_scores where submission_id = ?", Integer.class, id))
                .as("A and B only")
                .isEqualTo(2);

        // The tops of both ranges are accepted: 15.00 + 30.00 + 10.00 = 55.00, nothing pending.
        assertThat(score(id, b, "30").statusCode()).isEqualTo(200);
        assertThat(score(id, c, "10").statusCode()).isEqualTo(200);
        assertThat(total(id)).isEqualByComparingTo("55.00");
        assertThat(provisional(id)).isFalse();
    }

    // ---- recompute ----

    @Test
    void theTotalIsTheSumOfTheRoundedScoresAlsoWhenATeacherAddsOne() throws Exception {
        // A (25) and D (25) are TESTS criteria of three equal tests with one passed: 25 × 1/3 = 8.333...
        // = 8.33 each. M (50) is MANUAL. The teacher gives M 0.01.
        // Sum of the rounded scores: 8.33 + 8.33 + 0.01 = 16.67 (rounding the exact sum, 16.6767, would
        // give 16.68).
        Assignment second = publishedAssignment("Searching");
        RubricCriterion tests1 = rubricCriteria.save(new RubricCriterion(second.getId(), "A", CriterionType.TESTS, 25));
        RubricCriterion tests2 = rubricCriteria.save(new RubricCriterion(second.getId(), "D", CriterionType.TESTS, 25));
        RubricCriterion manual =
                rubricCriteria.save(new RubricCriterion(second.getId(), "M", CriterionType.MANUAL, 50));
        List<TestCase> cases = new ArrayList<>();
        List<TestResult> results = new ArrayList<>();
        long id = submission(second, 1, "RUNNING");
        for (int i = 0; i < 6; i++) {
            RubricCriterion criterion = i < 3 ? tests1 : tests2;
            TestCase test = testCases.save(
                    new TestCase(second.getId(), criterion.getId(), "t" + i, "", "", TestVisibility.PUBLIC, 1, i));
            cases.add(test);
            results.add(
                    new TestResult(id, test.getId(), i % 3 == 0 ? TestOutcome.PASSED : TestOutcome.FAILED, "", "", 1));
        }
        writer.store(id, second.getId(), cases, results);
        jdbc.update("update submissions set status = 'GRADED' where id = ?", id);
        assertThat(total(id)).as("before the teacher: 8.33 + 8.33").isEqualByComparingTo("16.66");

        HttpResponse<String> response = send(
                "PUT", "/submissions/" + id + "/manual-scores/" + manual.getId(), tokenFor(owner), "{\"score\":0.01}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(number(response.body(), "$.grade.totalScore")).isEqualByComparingTo("16.67");
        assertThat(total(id)).isEqualByComparingTo("16.67");
        assertThat(provisional(id)).isFalse();
        assertThat(jdbc.queryForObject(
                        "select sum(score) from criterion_scores where submission_id = ?", BigDecimal.class, id))
                .as("the total is the sum of the stored rows")
                .isEqualByComparingTo("16.67");
    }

    @Test
    void loweringAScoreLowersTheTotalAndTheBestAttemptFollows() throws Exception {
        long first = graded(1, TestOutcome.PASSED, TestOutcome.FAILED); // A = 15.00
        long second = graded(2, TestOutcome.FAILED, TestOutcome.PASSED); // A = 45.00, B and C pending
        score(first, b, "30");
        score(first, c, "10");
        // #1: 15.00 + 30.00 + 10.00 = 55.00, above #2's 45.00.
        assertThat(total(first)).isEqualByComparingTo("55.00");
        assertThat(bestSubmission()).isEqualTo(first);

        // The teacher lowers B to 12: #1 is 15.00 + 12.00 + 10.00 = 37.00, below #2's 45.00.
        HttpResponse<String> lowered = score(first, b, "12");

        assertThat(number(lowered.body(), "$.grade.totalScore")).isEqualByComparingTo("37.00");
        assertThat(total(first)).isEqualByComparingTo("37.00");
        assertThat(provisional(first)).as("a lower score is still a score").isFalse();
        assertThat(bestSubmission()).as("the best attempt is now #2").isEqualTo(second);
        assertThat(provisional(second))
                .as("#2 still waits for its manual scores")
                .isTrue();
    }

    private long bestSubmission() throws Exception {
        HttpResponse<String> response = get("/assignments/" + assignment.getId() + "/my-grade", tokenFor(student));
        return JsonPath.<Number>read(response.body(), "$.best.submissionId").longValue();
    }

    // ---- every view agrees ----

    @Test
    void everyViewOfTheGradeAgreesBeforeAndAfterTheLastCriterionIsScored() throws Exception {
        long id = graded(1, TestOutcome.PASSED, TestOutcome.FAILED); // A = 15.00

        // B 30: 15.00 + 30.00 = 45.00, C pending.
        score(id, b, "30");
        assertViews(id, "45.00", true, "30.00", null);

        // C 10: 15.00 + 30.00 + 10.00 = 55.00, nothing pending.
        score(id, c, "10");
        assertViews(id, "55.00", false, "30.00", "10.00");
    }

    /** The submission, both lists and my-grade all say the same total and flag. */
    private void assertViews(long id, String total, boolean provisional, String bScore, String cScore)
            throws Exception {
        String studentToken = tokenFor(student);
        String ownerToken = tokenFor(owner);
        String view = get("/submissions/" + id, studentToken).body();
        String mine = get("/assignments/" + assignment.getId() + "/my-submissions", studentToken)
                .body();
        String teacherList = get("/assignments/" + assignment.getId() + "/submissions", ownerToken)
                .body();
        String myGrade = get("/assignments/" + assignment.getId() + "/my-grade", studentToken)
                .body();
        String teacherView = get("/submissions/" + id, ownerToken).body();

        for (Map.Entry<String, String> grade : Map.of(
                        "the student's submission", view,
                        "the student's list", mine,
                        "the teacher's list", teacherList,
                        "my-grade", myGrade,
                        "the teacher's submission", teacherView)
                .entrySet()) {
            String prefix = grade.getKey().contains("list")
                    ? "$.items[0].grade"
                    : grade.getKey().equals("my-grade") ? "$.best" : "$.grade";
            assertThat(number(grade.getValue(), prefix + ".totalScore"))
                    .as("%s: total", grade.getKey())
                    .isEqualByComparingTo(total);
            assertThat(JsonPath.<Boolean>read(grade.getValue(), prefix + ".provisional"))
                    .as("%s: provisional", grade.getKey())
                    .isEqualTo(provisional);
        }
        // The criteria breakdown the student sees: A 15.00 from the tests, then the teacher's scores and
        // comments (the comment is "c" + the points sent), null while a criterion is pending.
        assertThat(JsonPath.<List<String>>read(view, "$.criteria[*].name")).containsExactly("A", "B", "C");
        assertThat(number(view, "$.criteria[0].score")).isEqualByComparingTo("15.00");
        assertThat(number(view, "$.criteria[1].score")).isEqualByComparingTo(bScore);
        assertThat(JsonPath.<String>read(view, "$.criteria[1].comment")).isEqualTo("c30");
        if (cScore == null) {
            assertThat(JsonPath.<Object>read(view, "$.criteria[2].score")).isNull();
        } else {
            assertThat(number(view, "$.criteria[2].score")).isEqualByComparingTo(cScore);
        }
    }

    // ---- saves that arrive together ----

    @Test
    void savesForDifferentCriteriaThatArriveTogetherAreAllCounted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 15; round++) {
                long id = graded(round, TestOutcome.PASSED, TestOutcome.FAILED); // A = 15.00
                CountDownLatch go = new CountDownLatch(1);
                CompletableFuture<HttpResponse<String>> savingB =
                        CompletableFuture.supplyAsync(() -> scoreWhenReleased(go, id, b, "30"), pool);
                CompletableFuture<HttpResponse<String>> savingC =
                        CompletableFuture.supplyAsync(() -> scoreWhenReleased(go, id, c, "10"), pool);
                go.countDown();

                assertThat(savingB.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(savingC.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

                // 15.00 + 30.00 + 10.00 = 55.00: neither save may have totalled without the other.
                assertThat(total(id)).as("round %d", round).isEqualByComparingTo("55.00");
                assertThat(provisional(id)).as("round %d", round).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> scoreWhenReleased(
            CountDownLatch go, long id, RubricCriterion criterion, String points) {
        try {
            go.await();
            return score(id, criterion, points);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
