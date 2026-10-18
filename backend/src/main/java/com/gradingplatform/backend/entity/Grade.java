package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of `grades` (V9): a submission's total, one per submission (5.4b). Always the exact sum of
 * its graded `criterion_scores`, as {@code RubricScoring} computes it; never patched by hand. It is
 * provisional (V11, 5.4e) while a criterion that tests do not score is still pending.
 */
@Entity
@Table(name = "grades")
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

    @Column(name = "total_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalScore;

    /** The sum of every criterion's weight, pending criteria included. */
    @Column(name = "max_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxScore;

    /** When it was computed, by the application's clock. */
    @Column(name = "graded_at", nullable = false)
    private Instant gradedAt;

    /** True while a criterion is pending (a {@code MANUAL} one not yet scored by the teacher). */
    @Column(name = "provisional", nullable = false)
    private boolean provisional;

    /** For JPA only. */
    protected Grade() {}

    public Grade(Long submissionId, BigDecimal totalScore, BigDecimal maxScore, Instant gradedAt, boolean provisional) {
        this.submissionId = submissionId;
        this.totalScore = totalScore;
        this.maxScore = maxScore;
        this.gradedAt = gradedAt;
        this.provisional = provisional;
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public Instant getGradedAt() {
        return gradedAt;
    }

    public boolean isProvisional() {
        return provisional;
    }
}
