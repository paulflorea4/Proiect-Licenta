package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.gradingplatform.backend.MutableClock;
import com.gradingplatform.backend.TestcontainersConfiguration;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.AssignmentRepository;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.CriterionScoreRepository;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import com.gradingplatform.backend.repository.SubmissionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import com.gradingplatform.backend.repository.TestResultRepository;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.sandbox.TestOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 5.4b: what grading stores, against the real database: test results, the {@code TESTS} criterion
 * scores and the grade, together, replacing an earlier run and keeping entered scores. Expected
 * values are worked out by hand in the comments.
 *
 * <p>The rubric: A ({@code TESTS}, 60) with tests a1 (weight 1) and a2 (weight 3); B ({@code TESTS},
 * 10) with test b1 (weight 1); C ({@code MANUAL}, 30), entered by a teacher or pending.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubmissionResultWriterTests {

    private static final Instant NOW = Instant.parse("2030-03-04T05:06:07.123456Z");

    @Autowired
    SubmissionResultWriter writer;

    @Autowired
    UserRepository users;

    @Autowired
    CourseRepository courses;

    @Autowired
    AssignmentRepository assignments;

    @Autowired
    RubricCriterionRepository rubricCriteria;

    @Autowired
    TestCaseRepository testCases;

    @Autowired
    SubmissionRepository submissions;

    @Autowired
    TestResultRepository testResults;

    @Autowired
    CriterionScoreRepository criterionScores;

    @Autowired
    GradeRepository grades;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private long assignment;
    private long submission;
    private RubricCriterion a;
    private RubricCriterion b;
    private RubricCriterion c;
    private TestCase a1;
    private TestCase a2;
    private TestCase b1;

    @BeforeEach
    void world() {
        cleanUp();
        clock.set(NOW);
        User teacher = users.save(new User("teacher@example.com", "hash", "Teacher", Role.TEACHER));
        User student = users.save(new User("student@example.com", "hash", "Student", Role.STUDENT));
        Course course = courses.save(new Course("Algorithms", null, teacher.getId(), "ABCD2345"));
        assignment = assignments
                .save(new Assignment(
                        course.getId(),
                        "Sum",
                        "d",
                        "PYTHON",
                        Instant.parse("2031-01-01T00:00:00Z"),
                        null,
                        2000,
                        128,
                        null))
                .getId();
        a = rubricCriteria.save(new RubricCriterion(assignment, "Correct", CriterionType.TESTS, 60));
        b = rubricCriteria.save(new RubricCriterion(assignment, "Edge cases", CriterionType.TESTS, 10));
        c = rubricCriteria.save(new RubricCriterion(assignment, "Readability", CriterionType.MANUAL, 30));
        a1 = testCases.save(new TestCase(assignment, a.getId(), "a1", "1", "1", TestVisibility.PUBLIC, 1, 1));
        a2 = testCases.save(new TestCase(assignment, a.getId(), "a2", "2", "2", TestVisibility.HIDDEN, 3, 2));
        b1 = testCases.save(new TestCase(assignment, b.getId(), "b1", "3", "3", TestVisibility.PUBLIC, 1, 3));
        submission = submissions
                .save(new Submission(assignment, student.getId(), "PYTHON", "print(1)", 1, NOW))
                .getId();
        // RUNNING, as when the worker grades it; a QUEUED row could be picked up by this context's worker.
        jdbc.update("update submissions set status = 'RUNNING' where id = ?", submission);
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        jdbc.update("delete from test_results");
        jdbc.update("delete from criterion_scores");
        jdbc.update("delete from grades");
        jdbc.update("delete from submissions");
        jdbc.update("delete from test_cases");
        jdbc.update("delete from rubric_criteria");
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    private TestResult result(TestCase test, TestOutcome outcome) {
        return new TestResult(submission, test.getId(), outcome, "out", "", 5);
    }

    private List<TestCase> tests() {
        return List.of(a1, a2, b1);
    }

    private static BigDecimal points(String value) {
        return new BigDecimal(value);
    }

    private List<CriterionScore> scores() {
        return criterionScores.findBySubmissionIdOrderByCriterionIdAsc(submission);
    }

    @Test
    void theResultsTheComputedScoresAndTheGradeAreStoredTogether() {
        // A: a1 passed (1 of 1 + 3) = 60 × 1/4 = 15.00. B: b1 passed = 10 × 1/1 = 10.00. C pending: no row.
        // Total 15.00 + 10.00 = 25.00 of 100.00.
        writer.store(
                submission,
                assignment,
                tests(),
                List.of(
                        result(a1, TestOutcome.PASSED),
                        result(a2, TestOutcome.FAILED),
                        result(b1, TestOutcome.PASSED)));

        assertThat(testResults.findBySubmissionIdOrderByIdAsc(submission))
                .extracting(TestResult::getTestCaseId, TestResult::getStatus)
                .containsExactly(
                        tuple(a1.getId(), TestOutcome.PASSED),
                        tuple(a2.getId(), TestOutcome.FAILED),
                        tuple(b1.getId(), TestOutcome.PASSED));
        assertThat(scores())
                .extracting(CriterionScore::getCriterionId, CriterionScore::getScore, CriterionScore::getMaxScore)
                .containsExactly(
                        tuple(a.getId(), points("15.00"), points("60.00")),
                        tuple(b.getId(), points("10.00"), points("10.00")));
        Grade grade = grades.findBySubmissionId(submission).orElseThrow();
        assertThat(grade.getTotalScore()).isEqualTo(points("25.00"));
        assertThat(grade.getMaxScore()).isEqualTo(points("100.00"));
        assertThat(grade.getGradedAt()).isEqualTo(NOW);
    }

    @Test
    void gradingAgainReplacesTheResultsTheComputedScoresAndTheGrade() {
        writer.store(
                submission,
                assignment,
                tests(),
                List.of(
                        result(a1, TestOutcome.FAILED),
                        result(a2, TestOutcome.FAILED),
                        result(b1, TestOutcome.FAILED)));
        Instant later = NOW.plusSeconds(60);
        clock.set(later);

        // Second run, everything passed: A 60.00, B 10.00, total 70.00.
        writer.store(
                submission,
                assignment,
                tests(),
                List.of(
                        result(a1, TestOutcome.PASSED),
                        result(a2, TestOutcome.PASSED),
                        result(b1, TestOutcome.PASSED)));

        assertThat(testResults.findBySubmissionIdOrderByIdAsc(submission))
                .extracting(TestResult::getStatus)
                .containsExactly(TestOutcome.PASSED, TestOutcome.PASSED, TestOutcome.PASSED);
        assertThat(scores()).extracting(CriterionScore::getScore).containsExactly(points("60.00"), points("10.00"));
        assertThat(grades.count()).isEqualTo(1);
        Grade grade = grades.findBySubmissionId(submission).orElseThrow();
        assertThat(grade.getTotalScore()).isEqualTo(points("70.00"));
        assertThat(grade.getGradedAt()).isEqualTo(later);
    }

    @Test
    void anEnteredScoreIsKeptAndCountedInTheTotal() {
        criterionScores.save(new CriterionScore(submission, c.getId(), points("12.50"), points("30.00"), "clear"));

        // A: a2 passed = 60 × 3/4 = 45.00. B: failed = 0.00. C: 12.50 as entered. Total 57.50 of 100.00.
        writer.store(
                submission,
                assignment,
                tests(),
                List.of(
                        result(a1, TestOutcome.FAILED),
                        result(a2, TestOutcome.PASSED),
                        result(b1, TestOutcome.COMPILE_ERROR)));

        assertThat(scores())
                .extracting(CriterionScore::getCriterionId, CriterionScore::getScore, CriterionScore::getComment)
                .containsExactly(
                        tuple(a.getId(), points("45.00"), null),
                        tuple(b.getId(), points("0.00"), null),
                        tuple(c.getId(), points("12.50"), "clear"));
        assertThat(grades.findBySubmissionId(submission).orElseThrow().getTotalScore())
                .isEqualTo(points("57.50"));
    }

    @Test
    void aTestWithoutAResultIsNotPassed() {
        // Only a1 has a result (passed): A = 60 × 1/4 = 15.00, B = 0.00, total 15.00.
        writer.store(submission, assignment, tests(), List.of(result(a1, TestOutcome.PASSED)));

        assertThat(grades.findBySubmissionId(submission).orElseThrow().getTotalScore())
                .isEqualTo(points("15.00"));
    }

    @Test
    void whenScoringIsRefusedNothingChangesNotEvenTheEarlierGrade() {
        writer.store(
                submission,
                assignment,
                tests(),
                List.of(
                        result(a1, TestOutcome.PASSED),
                        result(a2, TestOutcome.FAILED),
                        result(b1, TestOutcome.PASSED)));
        // A test whose criterion is the MANUAL one contradicts the rubric: RubricScoring refuses it.
        TestCase stray =
                testCases.save(new TestCase(assignment, c.getId(), "stray", "", "", TestVisibility.PUBLIC, 1, 4));

        assertThatThrownBy(() -> writer.store(
                        submission,
                        assignment,
                        List.of(a1, a2, b1, stray),
                        List.of(
                                result(a1, TestOutcome.PASSED),
                                result(a2, TestOutcome.PASSED),
                                result(b1, TestOutcome.PASSED),
                                result(stray, TestOutcome.PASSED))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(testResults.findBySubmissionIdOrderByIdAsc(submission))
                .extracting(TestResult::getStatus)
                .as("the first run's results, untouched")
                .containsExactly(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.PASSED);
        assertThat(scores()).extracting(CriterionScore::getScore).containsExactly(points("15.00"), points("10.00"));
        assertThat(grades.findBySubmissionId(submission).orElseThrow().getTotalScore())
                .isEqualTo(points("25.00"));
    }
}
