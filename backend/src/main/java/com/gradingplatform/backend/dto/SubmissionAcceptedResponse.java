package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import java.time.Instant;

/**
 * The answer to a new submission (5.1a, `202 Accepted`): which submission it is and that it is waiting
 * to be graded. It never carries a result; the result is read later (5.5a).
 */
public record SubmissionAcceptedResponse(long id, int attemptNo, SubmissionStatus status, Instant submittedAt) {

    public static SubmissionAcceptedResponse from(Submission submission) {
        return new SubmissionAcceptedResponse(
                submission.getId(), submission.getAttemptNo(), submission.getStatus(), submission.getSubmittedAt());
    }
}
