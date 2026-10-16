package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Submission;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /** The attempt number the student's next submission to this assignment gets (1 for the first). */
    @Query("select coalesce(max(s.attemptNo), 0) + 1 from Submission s"
            + " where s.assignmentId = :assignmentId and s.studentId = :studentId")
    int nextAttemptNo(@Param("assignmentId") Long assignmentId, @Param("studentId") Long studentId);

    /**
     * How many of the student's attempts at this assignment count against its {@code max_attempts}:
     * every submission except those that ended in {@code ERROR}, because a platform failure gives the
     * attempt back (5.1d, the human's decision). The attempt number ({@link #nextAttemptNo}) still
     * counts every submission, so numbers stay unique.
     */
    @Query("select count(s) from Submission s where s.assignmentId = :assignmentId and s.studentId = :studentId"
            + " and s.status <> com.gradingplatform.backend.entity.SubmissionStatus.ERROR")
    long countAttemptsUsed(@Param("assignmentId") Long assignmentId, @Param("studentId") Long studentId);

    /**
     * A worker takes the submission (5.2a): {@code QUEUED} becomes {@code RUNNING} with
     * {@code started_at}, in one statement that only matches a {@code QUEUED} row, so two workers
     * can never both take it. Returns 1 when this call took it, 0 when it was not {@code QUEUED}
     * (taken, finished, or deleted).
     */
    @Transactional
    @Modifying
    @Query(
            value = "update submissions set status = 'RUNNING', started_at = :startedAt"
                    + " where id = :id and status = 'QUEUED'",
            nativeQuery = true)
    int claim(@Param("id") long id, @Param("startedAt") Instant startedAt);

    /** The ids of up to {@code limit} {@code QUEUED} submissions, oldest (lowest id) first. */
    @Query(value = "select id from submissions where status = 'QUEUED' order by id limit :limit", nativeQuery = true)
    List<Long> findQueuedIds(@Param("limit") int limit);
}
