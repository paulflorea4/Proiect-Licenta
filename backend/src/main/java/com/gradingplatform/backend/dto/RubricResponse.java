package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.RubricCriterion;
import java.util.List;

/**
 * An assignment's whole rubric (always small, so not paginated): the criteria in creation order and
 * the sum of their weights, so a client can show how far the rubric is from the 100 that publishing
 * will require (3.4b).
 */
public record RubricResponse(List<RubricCriterionResponse> criteria, int totalWeight) {

    public static RubricResponse of(List<RubricCriterion> criteria) {
        return new RubricResponse(
                criteria.stream().map(RubricCriterionResponse::from).toList(),
                criteria.stream().mapToInt(RubricCriterion::getWeight).sum());
    }
}
