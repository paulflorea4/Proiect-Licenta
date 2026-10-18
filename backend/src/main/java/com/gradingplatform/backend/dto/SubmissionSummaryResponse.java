package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.repository.SubmissionRepository.SubmissionRow;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of an assignment's submission list for the people who run the course (5.5c): who
 * submitted, which attempt, its status and, once {@code GRADED}, its grade. No code and no result;
 * those are in `GET /submissions/{id}`.
 */
public record SubmissionSummaryResponse(
        long id,
        StudentSummaryResponse student,
        int attemptNo,
        SubmissionStatus status,
        Instant submittedAt,
        Instant finishedAt,
        GradeSummary grade) {

    /** The grade of a {@code GRADED} submission; null otherwise, and while it has none. */
    public record GradeSummary(BigDecimal totalScore, BigDecimal maxScore, boolean provisional) {

        static GradeSummary of(SubmissionRow row) {
            return row.status() == SubmissionStatus.GRADED && row.totalScore() != null
                    ? new GradeSummary(row.totalScore(), row.maxScore(), row.provisional())
                    : null;
        }
    }

    public static SubmissionSummaryResponse from(SubmissionRow row) {
        return new SubmissionSummaryResponse(
                row.id(),
                new StudentSummaryResponse(row.studentId(), row.studentEmail(), row.studentName()),
                row.attemptNo(),
                row.status(),
                row.submittedAt(),
                row.finishedAt(),
                GradeSummary.of(row));
    }
}
