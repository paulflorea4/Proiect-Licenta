package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.AiFeedback;
import java.time.Instant;

/**
 * The student's report on one of their hints, as stored (7.4b). After a repeat report it is still
 * the first one: its reason and time are kept.
 */
public record HintReportResponse(
        long submissionId, int level, boolean reported, String reportReason, Instant reportedAt) {

    public static HintReportResponse of(AiFeedback hint) {
        return new HintReportResponse(
                hint.getSubmissionId(),
                hint.getHintLevel(),
                hint.isReported(),
                hint.getReportReason(),
                hint.getReportedAt());
    }
}
