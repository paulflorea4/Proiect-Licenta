package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.repository.GradeRepository.GradedAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * A student's grade for an assignment (5.4c): the best of their graded attempts, and which submission
 * produced it. {@code best} is null (the key is still sent) while no attempt is graded. A provisional
 * best (5.4e) still waits for a teacher's score on some criterion; it counts as it stands.
 */
public record MyGradeResponse(long assignmentId, Best best) {

    /** The attempt whose score counts. */
    public record Best(
            long submissionId,
            int attemptNo,
            BigDecimal totalScore,
            BigDecimal maxScore,
            Instant gradedAt,
            boolean provisional) {}

    public static MyGradeResponse of(long assignmentId, Optional<GradedAttempt> attempt) {
        return new MyGradeResponse(
                assignmentId,
                attempt.map(a -> new Best(
                                a.submissionId(),
                                a.attemptNo(),
                                a.totalScore(),
                                a.maxScore(),
                                a.gradedAt(),
                                a.provisional()))
                        .orElse(null));
    }
}
