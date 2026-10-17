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
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a graded submission (5.4b): its {@code test_results}, the {@code criterion_scores} of its
 * {@code TESTS} criteria and its {@code grades} row, all in one transaction, so a submission has
 * the whole result or none of it. The scores come from {@link RubricScoring}, never computed here.
 *
 * <p>Storing <b>replaces</b>: a submission graded again (after a restart, 5.2c) loses its earlier
 * results, computed scores and grade first. Scores entered for other criteria ({@code MANUAL},
 * 5.4d) are kept and fed to the scoring as entered, so the new total includes them. A criterion
 * still pending gets no row (V9: no row means pending, not zero).
 */
@Service
public class SubmissionResultWriter {

    private final RubricCriterionRepository rubricCriteria;
    private final TestResultRepository testResults;
    private final CriterionScoreRepository criterionScores;
    private final GradeRepository grades;
    private final Clock clock;

    public SubmissionResultWriter(
            RubricCriterionRepository rubricCriteria,
            TestResultRepository testResults,
            CriterionScoreRepository criterionScores,
            GradeRepository grades,
            Clock clock) {
        this.rubricCriteria = rubricCriteria;
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
        List<RubricCriterion> rubric = rubricCriteria.findByAssignmentIdOrderByIdAsc(assignmentId);
        Set<Long> computed = rubric.stream()
                .filter(c -> c.getType() == CriterionType.TESTS)
                .map(RubricCriterion::getId)
                .collect(Collectors.toSet());

        testResults.deleteBySubmissionId(submissionId);
        grades.deleteBySubmissionId(submissionId);
        if (!computed.isEmpty()) {
            criterionScores.deleteBySubmissionIdAndCriterionIdIn(submissionId, computed);
        }
        testResults.saveAll(results);

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
                submissionId, score.total(), score.max(), clock.instant().truncatedTo(ChronoUnit.MICROS)));
        return score;
    }
}
