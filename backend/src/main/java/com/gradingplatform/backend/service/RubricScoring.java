package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.CriterionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rubric scoring (5.4a), the spec's "Grading & Scoring System": a pure function of the rubric, the
 * submission's test outcomes and the per-criterion scores entered so far. No database, no clock,
 * no state; the same inputs always give the same scores, so 5.6a can check it by hand and 5.4e can
 * recompute whenever an input changes.
 *
 * <ul>
 *   <li>A {@code TESTS} criterion scores {@code weight × (sum of passed test weights ÷ sum of all its
 *       test weights)}. Only {@code PASSED} counts (a compile error passes nothing, 5.3b). With no
 *       tests, or a total test weight of zero, it scores 0: never a division by zero.
 *   <li>Any other type ({@code MANUAL} from 5.4d, {@code STATIC_ANALYSIS}, {@code OPEN_ANSWER}) takes
 *       the score entered for it, in points from 0 to its weight. Until one is entered it is
 *       {@link Status#PENDING}: reported as not graded and adding nothing to the total, never a
 *       silent zero.
 *   <li>Each criterion score is computed exactly and rounded once, to {@link #SCALE} decimals with
 *       {@link #ROUNDING} (the scale of the {@code NUMERIC(7, 2)} columns, V9). The total is the exact
 *       sum of those rounded criterion scores, so the total shown can never disagree with the
 *       criteria shown.
 * </ul>
 */
public final class RubricScoring {

    /** Decimal places of every score, as stored ({@code NUMERIC(7, 2)}, V9). */
    public static final int SCALE = 2;

    /** How a criterion score is rounded to {@link #SCALE}: half away from zero, as grades usually are. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private RubricScoring() {}

    /** A rubric criterion: its id, what it measures and its weight (points out of the rubric's 100). */
    public record Criterion(long id, CriterionType type, int weight) {
        public Criterion {
            Objects.requireNonNull(type, "type");
            if (weight < 0) {
                throw new IllegalArgumentException("weight must not be negative");
            }
        }
    }

    /** One test case of a {@code TESTS} criterion, with whether the submission passed it. */
    public record ScoredTest(long testCaseId, long criterionId, int weight, boolean passed) {
        public ScoredTest {
            if (weight < 0) {
                throw new IllegalArgumentException("weight must not be negative");
            }
        }
    }

    public enum Status {
        /** Scored: tests were judged, or a score was entered. */
        GRADED,
        /** Not scored yet: no score entered for a criterion that needs one. */
        PENDING
    }

    /** One criterion's result. {@code score} is null exactly when {@code PENDING}. */
    public record CriterionResult(
            long criterionId, CriterionType type, Status status, BigDecimal score, BigDecimal max) {}

    /**
     * The whole result. {@code total} adds up the graded criteria only; {@code max} is the sum of
     * every criterion's weight, pending or not.
     */
    public record Result(List<CriterionResult> criteria, BigDecimal total, BigDecimal max) {

        /** True while some criterion is still waiting for a score (5.4e calls this provisional). */
        public boolean hasPending() {
            return criteria.stream().anyMatch(c -> c.status() == Status.PENDING);
        }
    }

    /**
     * Scores a submission.
     *
     * @param criteria the rubric, in the order the result should list it
     * @param tests every test case of the {@code TESTS} criteria, with the submission's outcome (a
     *     test without a result is simply not passed)
     * @param entered the scores entered for non-{@code TESTS} criteria, by criterion id, in points
     * @throws IllegalArgumentException if the inputs contradict each other: a test of a criterion
     *     that is not a {@code TESTS} criterion of this rubric, a score entered for a {@code TESTS}
     *     criterion or an unknown one, a score outside 0 to the criterion's weight, or a repeated id
     */
    public static Result score(List<Criterion> criteria, List<ScoredTest> tests, Map<Long, BigDecimal> entered) {
        Map<Long, Criterion> byId = new HashMap<>();
        for (Criterion criterion : criteria) {
            if (byId.put(criterion.id(), criterion) != null) {
                throw new IllegalArgumentException("criterion " + criterion.id() + " is listed twice");
            }
        }
        Map<Long, long[]> passedAndTotal = new HashMap<>();
        Set<Long> testIds = new HashSet<>();
        for (ScoredTest test : tests) {
            Criterion criterion = byId.get(test.criterionId());
            if (criterion == null || criterion.type() != CriterionType.TESTS) {
                throw new IllegalArgumentException(
                        "test " + test.testCaseId() + " does not belong to a TESTS criterion of this rubric");
            }
            if (!testIds.add(test.testCaseId())) {
                throw new IllegalArgumentException("test " + test.testCaseId() + " is listed twice");
            }
            long[] sums = passedAndTotal.computeIfAbsent(test.criterionId(), id -> new long[2]);
            sums[0] += test.passed() ? test.weight() : 0;
            sums[1] += test.weight();
        }
        for (Map.Entry<Long, BigDecimal> score : entered.entrySet()) {
            Criterion criterion = byId.get(score.getKey());
            if (criterion == null || criterion.type() == CriterionType.TESTS) {
                throw new IllegalArgumentException(
                        "a score was entered for criterion " + score.getKey() + ", which takes none");
            }
            BigDecimal value = Objects.requireNonNull(score.getValue(), "entered score");
            if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(criterion.weight())) > 0) {
                throw new IllegalArgumentException("the score of criterion " + score.getKey() + " is out of range");
            }
        }

        List<CriterionResult> results = new ArrayList<>(criteria.size());
        BigDecimal total = zero();
        BigDecimal max = zero();
        for (Criterion criterion : criteria) {
            BigDecimal weight = BigDecimal.valueOf(criterion.weight()).setScale(SCALE);
            max = max.add(weight);
            BigDecimal score;
            if (criterion.type() == CriterionType.TESTS) {
                long[] sums = passedAndTotal.getOrDefault(criterion.id(), new long[2]);
                score = testsScore(criterion.weight(), sums[0], sums[1]);
            } else {
                BigDecimal value = entered.get(criterion.id());
                score = value == null ? null : value.setScale(SCALE, ROUNDING);
            }
            if (score == null) {
                results.add(new CriterionResult(criterion.id(), criterion.type(), Status.PENDING, null, weight));
            } else {
                results.add(new CriterionResult(criterion.id(), criterion.type(), Status.GRADED, score, weight));
                total = total.add(score);
            }
        }
        return new Result(List.copyOf(results), total, max);
    }

    /** {@code weight × passed ÷ total}, rounded once; 0 when there is nothing to divide by. */
    private static BigDecimal testsScore(int weight, long passedWeight, long totalWeight) {
        if (totalWeight == 0) {
            return zero();
        }
        return BigDecimal.valueOf(weight)
                .multiply(BigDecimal.valueOf(passedWeight))
                .divide(BigDecimal.valueOf(totalWeight), SCALE, ROUNDING);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }
}
