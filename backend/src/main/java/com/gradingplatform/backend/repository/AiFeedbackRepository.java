package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.AiFeedback;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    /** How many hints the submission has used (7.2a): the row count, there is no counter. */
    long countBySubmissionId(Long submissionId);

    /** The hint stored for this level of the submission, if any. */
    Optional<AiFeedback> findBySubmissionIdAndHintLevel(Long submissionId, int hintLevel);

    /** The submission's hints, level 1 first. */
    List<AiFeedback> findBySubmissionIdOrderByHintLevelAsc(Long submissionId);

    /**
     * Flags the hint as reported (7.4b), only if it is not yet: one conditional update, so the first
     * report keeps its reason and time even when two arrive at once. Touches the report columns only,
     * never the hint itself. Returns 1 for the report that counted, 0 when it was already reported.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiFeedback h set h.reported = true, h.reportReason = :reason, h.reportedAt = :reportedAt"
            + " where h.id = :id and h.reported = false")
    int report(@Param("id") long id, @Param("reason") String reason, @Param("reportedAt") Instant reportedAt);
}
