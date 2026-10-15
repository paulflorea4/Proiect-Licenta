package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /** The attempt number the student's next submission to this assignment gets (1 for the first). */
    @Query("select coalesce(max(s.attemptNo), 0) + 1 from Submission s"
            + " where s.assignmentId = :assignmentId and s.studentId = :studentId")
    int nextAttemptNo(@Param("assignmentId") Long assignmentId, @Param("studentId") Long studentId);
}
