package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.dto.SubmissionSummaryResponse.GradeSummary;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.repository.SubmissionRepository.SubmissionRow;
import java.time.Instant;

/**
 * A row of a student's own submission history for an assignment (5.5c): which attempt, its status
 * and, once {@code GRADED}, its grade. The student is the caller, so it is not repeated; the code
 * and result are in `GET /submissions/{id}`.
 */
public record MySubmissionResponse(
        long id, int attemptNo, SubmissionStatus status, Instant submittedAt, Instant finishedAt, GradeSummary grade) {

    public static MySubmissionResponse from(SubmissionRow row) {
        return new MySubmissionResponse(
                row.id(), row.attemptNo(), row.status(), row.submittedAt(), row.finishedAt(), GradeSummary.of(row));
    }
}
