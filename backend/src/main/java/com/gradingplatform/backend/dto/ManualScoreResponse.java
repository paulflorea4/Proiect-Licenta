package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.service.GradeService.SavedManualScore;
import java.math.BigDecimal;

/**
 * A manual criterion's score as stored (5.4d), out of {@code maxScore}, the criterion's weight, and
 * the submission's grade as recomputed from it (5.4e).
 */
public record ManualScoreResponse(
        long submissionId,
        long criterionId,
        BigDecimal score,
        BigDecimal maxScore,
        String comment,
        GradeSummary grade) {

    /** The submission's total after the save; provisional while a criterion is still pending. */
    public record GradeSummary(BigDecimal totalScore, BigDecimal maxScore, boolean provisional) {

        static GradeSummary from(Grade grade) {
            return new GradeSummary(grade.getTotalScore(), grade.getMaxScore(), grade.isProvisional());
        }
    }

    public static ManualScoreResponse from(SavedManualScore saved) {
        CriterionScore score = saved.score();
        return new ManualScoreResponse(
                score.getSubmissionId(),
                score.getCriterionId(),
                score.getScore(),
                score.getMaxScore(),
                score.getComment(),
                GradeSummary.from(saved.grade()));
    }
}
