package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One hint a student received for a submission (V10, 7.2a). A level is stored once per submission
 * (`UNIQUE (submission_id, hint_level)`), and the number of hints used is the row count. A fallback
 * hint (the AI service's generic safe hint, 6.4b) is never stored: it does not use up its level. The
 * report columns are written by 7.4b.
 */
@Entity
@Table(name = "ai_feedback")
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

    @Column(name = "hint_level", nullable = false, updatable = false)
    private int hintLevel;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    /** Set by the database default (`NOW()`); read back after the insert, never written by us. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reported", nullable = false)
    private boolean reported;

    @Column(name = "report_reason")
    private String reportReason;

    @Column(name = "reported_at")
    private Instant reportedAt;

    /** For JPA only. */
    protected AiFeedback() {}

    public AiFeedback(Long submissionId, int hintLevel, String content) {
        this.submissionId = submissionId;
        this.hintLevel = hintLevel;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public int getHintLevel() {
        return hintLevel;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isReported() {
        return reported;
    }

    public String getReportReason() {
        return reportReason;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }
}
