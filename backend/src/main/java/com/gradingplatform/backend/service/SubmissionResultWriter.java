package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.repository.CriterionScoreRepository;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import com.gradingplatform.backend.repository.TestResultRepository;
import com.gradingplatform.backend.sandbox.TestOutcome;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a graded submission (5.4b): its {@code test_results}, the {@code criterion_scores} of its
 * {@code TESTS} criteria and its {@code grades} row, all in one transaction, so a submission has
 * the whole result or none of it. The scores come from {@link RubricScoring}, never computed here.
 *
 * <p>Storing <b>replaces</b>: a submission graded again (after a restart, 5.2c) loses its earlier
 * results, computed scores and grade first. Scores entered for other criteria ({@code MANUAL},
 * 5.4d) are kept and fed to the scoring as entered, so the new total includes them. A criterion
 * still pending gets no row (V9: no row means pending, not zero), and makes the grade provisional.
 *
 * <p>When a teacher enters a score (5.4d), {@link #rescore} recomputes the same way from what is
 * stored (5.4e), so a total is only ever written here, by one code path, from {@link RubricScoring}.
 */
@Service
public class SubmissionResultWriter {

    private final RubricCriterionRepository rubricCriteria;
    private final TestCaseRepository testCases;
    private final TestResultRepository testResults;
    private final CriterionScoreRepository criterionScores;
    private final GradeRepository grades;
    private final Clock clock;

    public SubmissionResultWriter(
            RubricCriterionRepository rubricCriteria,
            TestCaseRepository testCases,
            TestResultRepository testResults,
            CriterionScoreRepository criterionScores,
            GradeRepository grades,
            Clock clock) {
        this.rubricCriteria = rubricCriteria;
        this.testCases = testCases;
        this.testResults = testResults;
        this.criterionScores = criterionScores;
        this.grades = grades;
        this.clock = clock;
    }

    /**
     * @param assignmentId the submission's assignment (its rubric is read here, in the transaction)
     * @param tests the assignment's test cases, as run
     * @param results one row per test, in run order
     * @return the score that was stored
     */
    @Transactional
    public RubricScoring.Result store(
            long submissionId, long assignmentId, List<TestCase> tests, List<TestResult> results) {
        testResults.deleteBySubmissionId(submissionId);
        testResults.saveAll(results);
        return scoreAndStore(submissionId, assignmentId, tests, results);
    }

    /**
     * Recomputes a graded submission's score from what is stored: its test results, the
     * assignment's tests and the scores entered so far (5.4e, after a manual score is saved). The
     * {@code TESTS} rows and the grade are replaced, or the grade created if there was none (an
     * assignment without tests stores none when graded, 5.3a; the human's choice after 5.4d). Runs
     * in the caller's transaction, which must hold the submission's row lock, so two saves for the
     * same submission are scored one after the other and each sees the other's score.
     *
     * @return the score that was stored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RubricScoring.Result rescore(long submissionId, long assignmentId) {
        return scoreAndStore(
                submissionId,
                assignmentId,
                testCases.findByAssignmentIdOrderByPositionAscIdAsc(assignmentId),
                testResults.findBySubmissionIdOrderByIdAsc(submissionId));
    }

    private RubricScoring.Result scoreAndStore(
            long submissionId, long assignmentId, List<TestCase> tests, List<TestResult> results) {
        List<RubricCriterion> rubric = rubricCriteria.findByAssignmentIdOrderByIdAsc(assignmentId);
        Set<Long> computed = rubric.stream()
                .filter(c -> c.getType() == CriterionType.TESTS)
                .map(RubricCriterion::getId)
                .collect(Collectors.toSet());

        grades.deleteBySubmissionId(submissionId);
        if (!computed.isEmpty()) {
            criterionScores.deleteBySubmissionIdAndCriterionIdIn(submissionId, computed);
        }

        Map<Long, BigDecimal> entered = new HashMap<>();
        for (CriterionScore score : criterionScores.findBySubmissionIdOrderByCriterionIdAsc(submissionId)) {
            entered.put(score.getCriterionId(), score.getScore());
        }
        Map<Long, Boolean> passed = new HashMap<>();
        for (TestResult result : results) {
            passed.put(result.getTestCaseId(), result.getStatus() == TestOutcome.PASSED);
        }
        RubricScoring.Result score = RubricScoring.score(
                rubric.stream()
                        .map(c -> new RubricScoring.Criterion(c.getId(), c.getType(), c.getWeight()))
                        .toList(),
                tests.stream()
                        .map(t -> new RubricScoring.ScoredTest(
                                t.getId(),
                                Objects.requireNonNull(t.getCriterionId(), "a test case without a criterion"),
                                t.getWeight(),
                                passed.getOrDefault(t.getId(), false)))
                        .toList(),
                entered);

        for (RubricScoring.CriterionResult criterion : score.criteria()) {
            if (computed.contains(criterion.criterionId())) {
                criterionScores.save(new CriterionScore(
                        submissionId, criterion.criterionId(), criterion.score(), criterion.max(), null));
            }
        }
        grades.save(new Grade(
                submissionId,
                score.total(),
                score.max(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                score.hasPending()));
        return score;
    }
}
