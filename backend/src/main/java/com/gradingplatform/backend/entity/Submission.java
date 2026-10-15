package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A row of `submissions` (V7): one attempt of a student at an assignment, with the code as sent. The
 * language is copied from the assignment when it is submitted, so the row says what the code was run
 * as even though the assignment's language is locked from then on anyway.
 */
@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    /** The assignment's language id at submission time (`JAVA`, `PYTHON`). */
    @Column(name = "language", nullable = false)
    private String language;

    /** The code exactly as sent. */
    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status;

    /** 1-based, per student and assignment. */
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    /** Set by the database default (`NOW()`); read back after the insert, never written by us. */
    @Generated(event = EventType.INSERT)
    @Column(name = "submitted_at", insertable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** For JPA only. */
    protected Submission() {}

    /** A new submission, waiting for the worker. */
    public Submission(Long assignmentId, Long studentId, String language, String sourceCode, int attemptNo) {
        this.assignmentId = assignmentId;
        this.studentId = studentId;
        this.language = language;
        this.sourceCode = sourceCode;
        this.attemptNo = attemptNo;
        this.status = SubmissionStatus.QUEUED;
    }

    public Long getId() {
        return id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getLanguage() {
        return language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
