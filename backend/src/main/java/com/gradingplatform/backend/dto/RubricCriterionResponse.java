package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.RubricCriterion;

/** A rubric criterion as its teacher sees it. */
public record RubricCriterionResponse(Long id, Long assignmentId, String name, CriterionType type, int weight) {

    public static RubricCriterionResponse from(RubricCriterion c) {
        return new RubricCriterionResponse(c.getId(), c.getAssignmentId(), c.getName(), c.getType(), c.getWeight());
    }
}
