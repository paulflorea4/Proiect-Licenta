package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /assignments/{id}/tests` and `PUT /assignments/{id}/tests/{testId}`. The criterion
 * must be a `TESTS` criterion of the same assignment (checked by the service). {@code input} and
 * {@code expectedOutput} are kept exactly as sent, whitespace included, and may be empty (a
 * program that reads nothing or prints nothing); only the name is trimmed.
 *
 * <p>The size caps and the maximum weight are server-side placeholders pending the human's call
 * (flagged in the PR); the name limit matches its column.
 *
 * @param position run order; null = after the last existing test
 */
public record TestCaseRequest(
        @NotNull Long criterionId,
        @NotBlank @Size(max = TestCaseRequest.NAME_MAX) String name,
        @NotNull @Size(max = TestCaseRequest.TEXT_MAX) String input,
        @NotNull @Size(max = TestCaseRequest.TEXT_MAX) String expectedOutput,
        @NotNull TestVisibility visibility,
        @NotNull @Min(1) @Max(TestCaseRequest.WEIGHT_MAX) Integer weight,
        @Min(0) @Max(TestCaseRequest.POSITION_MAX) Integer position) {

    public static final int NAME_MAX = 255;

    // Placeholders pending the human's decision.
    public static final int TEXT_MAX = 100_000;
    public static final int WEIGHT_MAX = 100;
    public static final int POSITION_MAX = 100_000;

    /** Surrounding whitespace on the name is dropped. */
    public TestCaseRequest {
        if (name != null) {
            name = name.trim();
        }
    }
}
