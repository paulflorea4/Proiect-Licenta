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
 * {@code expectedOutput} are kept as sent, whitespace included, and may be empty (a program that
 * reads nothing or prints nothing); the name is trimmed. One exception (4.7e, the human's decision):
 * Windows line endings in {@code input} become {@code \n}, because the input reaches the program's
 * stdin byte for byte and Python's {@code input()} on Linux would keep the {@code \r}, failing a
 * correct program only because the test was typed on Windows. A lone {@code \r} is kept. The expected
 * output needs no such change: the comparison already ignores line endings (4.5b). The size cap
 * applies to the text as stored.
 *
 * <p>The size caps and the maximum weight are server-side placeholders pending the human's call
 * (flagged in the PR); the name limit matches its column.
 *
 * @param position run order; null = after the last existing test
 */
public record TestCaseRequest(
        @NotNull Long criterionId,

        @NotBlank @Size(max = TestCaseRequest.NAME_MAX) @NoNul
        String name,

        @NotNull @Size(max = TestCaseRequest.TEXT_MAX) @NoNul
        String input,

        @NotNull @Size(max = TestCaseRequest.TEXT_MAX) @NoNul
        String expectedOutput,

        @NotNull TestVisibility visibility,
        @NotNull @Min(1) @Max(TestCaseRequest.WEIGHT_MAX) Integer weight,
        @Min(0) @Max(TestCaseRequest.POSITION_MAX) Integer position) {

    public static final int NAME_MAX = 255;

    // Placeholders pending the human's decision.
    public static final int TEXT_MAX = 100_000;
    public static final int WEIGHT_MAX = 100;
    public static final int POSITION_MAX = 100_000;

    /** Surrounding whitespace on the name is dropped; CRLF in the input becomes LF. */
    public TestCaseRequest {
        if (name != null) {
            name = name.trim();
        }
        if (input != null) {
            input = input.replace("\r\n", "\n");
        }
    }
}
