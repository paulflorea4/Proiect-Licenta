package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Grade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GradeRepository extends JpaRepository<Grade, Long> {

    Optional<Grade> findBySubmissionId(Long submissionId);

    /** Removes the submission's grade, if any, so a new one can take its place. Needs a transaction. */
    @Modifying
    @Query("delete from Grade g where g.submissionId = :submissionId")
    int deleteBySubmissionId(@Param("submissionId") Long submissionId);

    /** A graded attempt with its grade, as {@link #findGradedAttemptsBestFirst} reads it. */
    record GradedAttempt(
            long submissionId, int attemptNo, BigDecimal totalScore, BigDecimal maxScore, Instant gradedAt) {}

    /**
     * The student's graded attempts at the assignment, best first: highest total, and of equal totals
     * the earliest attempt (5.4c). Only a {@code GRADED} submission counts, even when it has a grade
     * row: a {@code QUEUED} or {@code RUNNING} one is being graded again (5.2c) and an {@code ERROR}
     * one may keep the grade of a run that recovery cut short. A submission without a grade (no
     * tests, 5.3a) is not listed.
     */
    @Query("select new com.gradingplatform.backend.repository.GradeRepository$GradedAttempt("
            + "s.id, s.attemptNo, g.totalScore, g.maxScore, g.gradedAt)"
            + " from Grade g join Submission s on s.id = g.submissionId"
            + " where s.assignmentId = :assignmentId and s.studentId = :studentId"
            + " and s.status = com.gradingplatform.backend.entity.SubmissionStatus.GRADED"
            + " order by g.totalScore desc, s.attemptNo asc")
    List<GradedAttempt> findGradedAttemptsBestFirst(
            @Param("assignmentId") Long assignmentId, @Param("studentId") Long studentId, Limit limit);
}
