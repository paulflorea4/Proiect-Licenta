package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.AiFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    /** How many hints the submission has used (7.2a): the row count, there is no counter. */
    long countBySubmissionId(Long submissionId);

    /** The hint stored for this level of the submission, if any. */
    Optional<AiFeedback> findBySubmissionIdAndHintLevel(Long submissionId, int hintLevel);

    /** The submission's hints, level 1 first. */
    List<AiFeedback> findBySubmissionIdOrderByHintLevelAsc(Long submissionId);
}
