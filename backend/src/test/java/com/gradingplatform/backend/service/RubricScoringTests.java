package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.service.RubricScoring.Criterion;
import com.gradingplatform.backend.service.RubricScoring.CriterionResult;
import com.gradingplatform.backend.service.RubricScoring.Result;
import com.gradingplatform.backend.service.RubricScoring.ScoredTest;
import com.gradingplatform.backend.service.RubricScoring.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 5.4a: the scoring function. Every expected value is worked out by hand in the comment above it,
 * never by running the code (5.6a adds more such cases).
 */
class RubricScoringTests {

    private static Criterion tests(long id, int weight) {
        return new Criterion(id, CriterionType.TESTS, weight);
    }

    private static Criterion manual(long id, int weight) {
        return new Criterion(id, CriterionType.MANUAL, weight);
    }

    private static ScoredTest passed(long testId, long criterionId, int weight) {
        return new ScoredTest(testId, criterionId, weight, true);
    }

    private static ScoredTest failed(long testId, long criterionId, int weight) {
        return new ScoredTest(testId, criterionId, weight, false);
    }

    private static BigDecimal points(String value) {
        return new BigDecimal(value);
    }

    private static Result score(List<Criterion> criteria, List<ScoredTest> tests) {
        return RubricScoring.score(criteria, tests, Map.of());
    }

    @Test
    void aTestsCriterionScoresItsWeightTimesTheFractionOfTestsPassed() {
        // 3 of 4 equal tests: 100 × 3/4 = 75.00.
        Result result = score(
                List.of(tests(1, 100)),
                List.of(passed(11, 1, 1), passed(12, 1, 1), passed(13, 1, 1), failed(14, 1, 1)));

        assertThat(result.criteria())
                .containsExactly(
                        new CriterionResult(1, CriterionType.TESTS, Status.GRADED, points("75.00"), points("100.00")));
        assertThat(result.total()).isEqualTo(points("75.00"));
        assertThat(result.max()).isEqualTo(points("100.00"));
        assertThat(result.hasPending()).isFalse();
    }

    @Test
    void testWeightsDecideTheFraction() {
        // Weights 1, 2, 3; passed the 1 and the 3: 4/6 of 100 = 66.666... = 66.67.
        Result result = score(List.of(tests(1, 100)), List.of(passed(11, 1, 1), failed(12, 1, 2), passed(13, 1, 3)));

        assertThat(result.total()).isEqualTo(points("66.67"));
    }

    @Test
    void criterionWeightsDecideHowMuchEachCriterionAdds() {
        // A (60): 1 of 2 equal tests = 30.00. B (40): weights 2 and 3, passed the 3 = 40 × 3/5 = 24.00.
        // Total 30.00 + 24.00 = 54.00.
        Result result = score(
                List.of(tests(1, 60), tests(2, 40)),
                List.of(passed(11, 1, 1), failed(12, 1, 1), failed(21, 2, 2), passed(22, 2, 3)));

        assertThat(result.criteria())
                .extracting(CriterionResult::score)
                .containsExactly(points("30.00"), points("24.00"));
        assertThat(result.total()).isEqualTo(points("54.00"));
        assertThat(result.max()).isEqualTo(points("100.00"));
    }

    @Test
    void theTotalIsTheSumOfTheRoundedCriterionScores() {
        // Each: 50 × 1/3 = 16.666... = 16.67. Total 16.67 + 16.67 = 33.34, not the exact 33.333... = 33.33.
        Result result = score(
                List.of(tests(1, 50), tests(2, 50)),
                List.of(
                        passed(11, 1, 1),
                        failed(12, 1, 1),
                        failed(13, 1, 1),
                        passed(21, 2, 1),
                        failed(22, 2, 1),
                        failed(23, 2, 1)));

        assertThat(result.criteria())
                .extracting(CriterionResult::score)
                .containsExactly(points("16.67"), points("16.67"));
        assertThat(result.total()).isEqualTo(points("33.34"));
    }

    @Test
    void aHalfIsRoundedAwayFromZero() {
        // A (1): 1 of 8 = 0.125, half up 0.13 (half even would give 0.12).
        // B (99): 5 of 8 = 61.875, half up 61.88 (half even 61.88 too); total 0.13 + 61.88 = 62.01.
        List<ScoredTest> outcomes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            outcomes.add(new ScoredTest(10 + i, 1, 1, i < 1));
            outcomes.add(new ScoredTest(20 + i, 2, 1, i < 5));
        }

        Result result = score(List.of(tests(1, 1), tests(2, 99)), outcomes);

        assertThat(result.criteria())
                .extracting(CriterionResult::score)
                .containsExactly(points("0.13"), points("61.88"));
        assertThat(result.total()).isEqualTo(points("62.01"));
    }

    @Test
    void anotherHalfIsRoundedAwayFromZeroToo() {
        // 5 of 8 of a weight of 1 = 0.625: half up 0.63, half even 0.62.
        List<ScoredTest> outcomes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            outcomes.add(new ScoredTest(10 + i, 1, 1, i < 5));
        }

        assertThat(score(List.of(tests(1, 1)), outcomes).total()).isEqualTo(points("0.63"));
    }

    @Test
    void passingEverythingScoresExactlyTheWeight() {
        // 3 of 3 = 100 × 3/3 = 100.00 exactly.
        Result result = score(List.of(tests(1, 100)), List.of(passed(11, 1, 1), passed(12, 1, 1), passed(13, 1, 1)));

        assertThat(result.total()).isEqualTo(points("100.00"));
    }

    @Test
    void aCompileErrorPassesNothingAndScoresZeroButIsGraded() {
        // Every test COMPILE_ERROR (5.3b), so none passed: 0 of 3 = 0.00, graded, not pending.
        Result result = score(List.of(tests(1, 100)), List.of(failed(11, 1, 1), failed(12, 1, 2), failed(13, 1, 3)));

        assertThat(result.criteria())
                .containsExactly(
                        new CriterionResult(1, CriterionType.TESTS, Status.GRADED, points("0.00"), points("100.00")));
        assertThat(result.total()).isEqualTo(points("0.00"));
    }

    @Test
    void aTestsCriterionWithoutTestsOrWithoutTestWeightScoresZeroWithoutDividingByZero() {
        // A (50) has no tests; B (50) has tests of weight 0. Both 0.00, total 0.00.
        Result result = score(List.of(tests(1, 50), tests(2, 50)), List.of(passed(21, 2, 0), passed(22, 2, 0)));

        assertThat(result.criteria())
                .extracting(CriterionResult::status, CriterionResult::score)
                .containsExactly(tuple(Status.GRADED, points("0.00")), tuple(Status.GRADED, points("0.00")));
        assertThat(result.total()).isEqualTo(points("0.00"));
    }

    @Test
    void aCriterionWaitingForItsScoreIsPendingAndAddsNothingButCountsInTheMaximum() {
        // Tests (80): 2 of 2 = 80.00. Manual (20): nothing entered, pending. Total 80.00 of 100.00.
        Result result = score(List.of(tests(1, 80), manual(2, 20)), List.of(passed(11, 1, 1), passed(12, 1, 1)));

        assertThat(result.criteria())
                .containsExactly(
                        new CriterionResult(1, CriterionType.TESTS, Status.GRADED, points("80.00"), points("80.00")),
                        new CriterionResult(2, CriterionType.MANUAL, Status.PENDING, null, points("20.00")));
        assertThat(result.total()).isEqualTo(points("80.00"));
        assertThat(result.max()).isEqualTo(points("100.00"));
        assertThat(result.hasPending()).isTrue();
    }

    @Test
    void anEnteredScoreIsAddedAsItIs() {
        // Tests (80): 1 of 2 = 40.00. Manual (20): 12.5 entered = 12.50. Total 52.50.
        Result result = RubricScoring.score(
                List.of(tests(1, 80), manual(2, 20)),
                List.of(passed(11, 1, 1), failed(12, 1, 1)),
                Map.of(2L, points("12.5")));

        assertThat(result.criteria().get(1))
                .isEqualTo(
                        new CriterionResult(2, CriterionType.MANUAL, Status.GRADED, points("12.50"), points("20.00")));
        assertThat(result.total()).isEqualTo(points("52.50"));
        assertThat(result.hasPending()).isFalse();
    }

    @Test
    void anEnteredScoreMayBeZeroOrTheWholeWeight() {
        // Two manual criteria of 50: 0 and 50 entered. Total 50.00, nothing pending.
        Result result = RubricScoring.score(
                List.of(manual(1, 50), manual(2, 50)), List.of(), Map.of(1L, points("0"), 2L, points("50")));

        assertThat(result.total()).isEqualTo(points("50.00"));
        assertThat(result.hasPending()).isFalse();
    }

    @Test
    void everyScoreHasTwoDecimalsAndTheRubricOrderIsKept() {
        Result result = RubricScoring.score(
                List.of(manual(3, 30), tests(1, 70)), List.of(passed(11, 1, 1)), Map.of(3L, points("7")));

        assertThat(result.criteria()).extracting(CriterionResult::criterionId).containsExactly(3L, 1L);
        assertThat(result.criteria()).allSatisfy(c -> {
            assertThat(c.score().scale()).isEqualTo(2);
            assertThat(c.max().scale()).isEqualTo(2);
        });
        assertThat(result.total().scale()).isEqualTo(2);
        assertThat(result.max().scale()).isEqualTo(2);
    }

    @Test
    void anEmptyRubricScoresZeroOfZero() {
        Result result = score(List.of(), List.of());

        assertThat(result.criteria()).isEmpty();
        assertThat(result.total()).isEqualTo(points("0.00"));
        assertThat(result.max()).isEqualTo(points("0.00"));
    }

    @Test
    void inputsThatContradictEachOtherAreRefused() {
        List<Criterion> rubric = List.of(tests(1, 80), manual(2, 20));

        assertThatThrownBy(() -> score(rubric, List.of(passed(11, 9, 1))))
                .as("a test of an unknown criterion")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> score(rubric, List.of(passed(11, 2, 1))))
                .as("a test of a manual criterion")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> score(rubric, List.of(passed(11, 1, 1), failed(11, 1, 1))))
                .as("a test listed twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> score(List.of(tests(1, 50), tests(1, 50)), List.of()))
                .as("a criterion listed twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricScoring.score(rubric, List.of(), Map.of(1L, points("10"))))
                .as("a score entered for a TESTS criterion")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricScoring.score(rubric, List.of(), Map.of(9L, points("10"))))
                .as("a score entered for an unknown criterion")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricScoring.score(rubric, List.of(), Map.of(2L, points("20.01"))))
                .as("above the weight")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricScoring.score(rubric, List.of(), Map.of(2L, points("-0.01"))))
                .as("below zero")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
