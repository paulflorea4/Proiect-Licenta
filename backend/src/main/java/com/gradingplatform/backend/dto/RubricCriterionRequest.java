package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /assignments/{id}/rubric` and `PUT /assignments/{id}/rubric/{criterionId}`. The
 * type must be spelled exactly as in {@link CriterionType} (an unknown one is a 400); a known type
 * that no phase has enabled yet is refused by the service with its own error code.
 *
 * <p>A single weight is 1 to 100 whole points. That all weights add up to 100 is <b>not</b>
 * checked here: a draft rubric may be incomplete, and the rule applies when the assignment is
 * published (3.4b).
 */
public record RubricCriterionRequest(
        @NotBlank @Size(max = RubricCriterionRequest.NAME_MAX)
        String name,

        @NotNull CriterionType type,

        @NotNull @Min(1) @Max(RubricCriterionRequest.WEIGHT_MAX)
        Integer weight) {

    public static final int NAME_MAX = 255;
    public static final int WEIGHT_MAX = 100;

    /** Surrounding whitespace on the name is dropped. */
    public RubricCriterionRequest {
        if (name != null) {
            name = name.trim();
        }
    }
}
