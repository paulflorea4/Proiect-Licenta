package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * One submission of a list (5.5c), with its student and its grade if it has one; never its code
     * or any result. The grade fields are null without a grade row, and are only meant to be shown
     * for a {@code GRADED} submission (the caller decides, as for the submission view).
     */
    record SubmissionRow(
            long id,
            long studentId,
            String studentEmail,
            String studentName,
            int attemptNo,
            SubmissionStatus status,
            Instant submittedAt,
            Instant finishedAt,
            BigDecimal totalScore,
            BigDecimal maxScore,
            Boolean provisional) {}

    /** Every submission to the assignment, for the people who run its course (5.5c). */
    @Query(
            value = "select new com.gradingplatform.backend.repository.SubmissionRepository$SubmissionRow("
                    + "s.id, u.id, u.email, u.fullName, s.attemptNo, s.status, s.submittedAt, s.finishedAt,"
                    + " g.totalScore, g.maxScore, g.provisional)"
                    + " from Submission s join User u on u.id = s.studentId"
                    + " left join Grade g on g.submissionId = s.id"
                    + " where s.assignmentId = :assignmentId",
            countQuery = "select count(s) from Submission s where s.assignmentId = :assignmentId")
    Page<SubmissionRow> findRowsByAssignmentId(@Param("assignmentId") Long assignmentId, Pageable pageable);

    /** One student's submissions to the assignment (5.5c). */
    @Query(
            value = "select new com.gradingplatform.backend.repository.SubmissionRepository$SubmissionRow("
                    + "s.id, u.id, u.email, u.fullName, s.attemptNo, s.status, s.submittedAt, s.finishedAt,"
                    + " g.totalScore, g.maxScore, g.provisional)"
                    + " from Submission s join User u on u.id = s.studentId"
                    + " left join Grade g on g.submissionId = s.id"
                    + " where s.assignmentId = :assignmentId and s.studentId = :studentId",
            countQuery = "select count(s) from Submission s"
                    + " where s.assignmentId = :assignmentId and s.studentId = :studentId")
    Page<SubmissionRow> findRowsByAssignmentIdAndStudentId(
            @Param("assignmentId") Long assignmentId, @Param("studentId") Long studentId, Pageable pageable);

    /** Only the assignment of a submission, for the access check before anything is locked or loaded. */
    @Query("select s.assignmentId from Submission s where s.id = :id")
    Optional<Long> findAssignmentIdById(@Param("id") Long id);

    /**
     * The submission, locked until the transaction ends, so changes to its score are made one at a
     * time (5.4e: each recompute sees the scores saved before it). {@code FOR NO KEY UPDATE}, not
     * {@code FOR UPDATE}: it does not hold up rows being inserted that reference the submission
     * (their foreign key takes a key-share lock), only another lock like this one.
     */
    @Query(value = "select * from submissions where id = :id for no key update", nativeQuery = true)
    Optional<Submission> findByIdForScoreChange(@Param("id") Long id);

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

    /**
     * The worker is done with the submission (5.2b): {@code RUNNING} becomes {@code status} ({@code
     * GRADED} or {@code ERROR}, the caller's choice) with {@code finished_at}. Only a {@code RUNNING}
     * row matches, and only the status and {@code finished_at} change, so the student's code is
     * kept whatever the outcome. Returns 0 when it was not {@code RUNNING} (finished, or deleted).
     */
    @Transactional
    @Modifying
    @Query("update Submission s set s.status = :status, s.finishedAt = :finishedAt"
            + " where s.id = :id and s.status = com.gradingplatform.backend.entity.SubmissionStatus.RUNNING")
    int finish(@Param("id") long id, @Param("status") SubmissionStatus status, @Param("finishedAt") Instant finishedAt);

    /**
     * Startup recovery (5.2c), step one: deletes the {@code test_results} of every {@code RUNNING}
     * submission, the partial results of a grading a restart cut short. Needs the caller's
     * transaction, the same one as {@link #requeueRunning}.
     */
    @Modifying
    @Query(
            value = "delete from test_results"
                    + " where submission_id in (select id from submissions where status = 'RUNNING')",
            nativeQuery = true)
    int deleteTestResultsOfRunning();

    /**
     * Startup recovery (5.2c), step two: every {@code RUNNING} submission is {@code QUEUED} again,
     * without {@code started_at}, so a worker claims and grades it from the start. Returns how many.
     */
    @Modifying
    @Query(
            value = "update submissions set status = 'QUEUED', started_at = null where status = 'RUNNING'",
            nativeQuery = true)
    int requeueRunning();

    /** The ids of up to {@code limit} {@code QUEUED} submissions, oldest (lowest id) first. */
    @Query(value = "select id from submissions where status = 'QUEUED' order by id limit :limit", nativeQuery = true)
    List<Long> findQueuedIds(@Param("limit") int limit);
}
