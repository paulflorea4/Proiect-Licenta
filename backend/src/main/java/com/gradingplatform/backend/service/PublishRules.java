package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import org.springframework.stereotype.Component;

/**
 * What an assignment must have before students can see it (3.4b): a rubric whose weights add up to
 * 100, and at least one test case in every `TESTS` criterion (otherwise that criterion's score,
 * passed weight over total weight, would be 0 / 0). A draft may break both while it is being
 * written; only publishing checks.
 *
 * <p>Call it with the assignment's row lock held ({@link AssignmentService#lockManageable}), so a
 * rubric edit cannot slip in between the check and the publication.
 */
@Component
public class PublishRules {

    /** The rubric weights of a publishable assignment add up to exactly this (spec: "weights sum to 100"). */
    public static final int REQUIRED_TOTAL_WEIGHT = 100;

    private final RubricCriterionRepository criteria;

    public PublishRules(RubricCriterionRepository criteria) {
        this.criteria = criteria;
    }

    /**
     * @throws RubricWeightsInvalidException if the weights do not add up to {@value #REQUIRED_TOTAL_WEIGHT}
     *     (an assignment with no criteria adds up to 0)
     * @throws CriterionHasNoTestsException if a `TESTS` criterion has no test case
     */
    public void requirePublishable(long assignmentId) {
        int total = criteria.findByAssignmentIdOrderByIdAsc(assignmentId).stream()
                .mapToInt(RubricCriterion::getWeight)
                .sum();
        if (total != REQUIRED_TOTAL_WEIGHT) {
            throw new RubricWeightsInvalidException();
        }
        if (criteria.countTestsCriteriaWithoutTests(assignmentId) > 0) {
            throw new CriterionHasNoTestsException();
        }
    }
}
