package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of `PUT /submissions/{id}/manual-scores/{criterionId}` (5.4d): the points a teacher gives a
 * {@code MANUAL} criterion, and an optional note. The score has at most two decimals, the scale it
 * is stored at ({@code NUMERIC(7, 2)}), so it is never rounded; that it lies between 0 and the
 * criterion's weight is checked against the criterion. A criterion's weight is at most 100, so three
 * integer digits are enough.
 */
public record ManualScoreRequest(
        @NotNull @Digits(integer = 3, fraction = 2) BigDecimal score,
        @Size(max = ManualScoreRequest.COMMENT_MAX) @NoNul String comment) {

    /** A placeholder cap pending the human's call (flagged in the PR), as for a course description. */
    public static final int COMMENT_MAX = 5000;

    /** A blank comment is the same as none, so a stored comment is never an empty string. */
    public ManualScoreRequest {
        if (comment != null && comment.isBlank()) {
            comment = null;
        }
    }
}
