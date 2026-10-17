package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A row of `criterion_scores` (V9): one criterion's score for one submission, in points out of the
 * criterion's weight (5.4b). A criterion without a row is pending, not zero. {@code TESTS} rows are
 * computed by grading and replaced whenever it runs again; other rows are entered by a teacher
 * (5.4d) and grading never touches them.
 */
@Entity
@Table(name = "criterion_scores")
public class CriterionScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private Long submissionId;

    @Column(name = "criterion_id", nullable = false, updatable = false)
    private Long criterionId;

    @Column(name = "score", nullable = false, precision = 7, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxScore;

    /** The teacher's note on a manually graded criterion (5.4d); null otherwise. */
    @Column(name = "comment")
    private String comment;

    /** For JPA only. */
    protected CriterionScore() {}

    public CriterionScore(Long submissionId, Long criterionId, BigDecimal score, BigDecimal maxScore, String comment) {
        this.submissionId = submissionId;
        this.criterionId = criterionId;
        this.score = score;
        this.maxScore = maxScore;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public Long getCriterionId() {
        return criterionId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public String getComment() {
        return comment;
    }
}
