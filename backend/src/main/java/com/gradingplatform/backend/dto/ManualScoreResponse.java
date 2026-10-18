package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionScore;
import java.math.BigDecimal;

/** A manual criterion's score as stored (5.4d), out of {@code maxScore}, the criterion's weight. */
public record ManualScoreResponse(
        long submissionId, long criterionId, BigDecimal score, BigDecimal maxScore, String comment) {

    public static ManualScoreResponse from(CriterionScore score) {
        return new ManualScoreResponse(
                score.getSubmissionId(),
                score.getCriterionId(),
                score.getScore(),
                score.getMaxScore(),
                score.getComment());
    }
}
