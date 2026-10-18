package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.CriterionScore;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CriterionScoreRepository extends JpaRepository<CriterionScore, Long> {

    /** A submission's criterion scores, by criterion id. */
    List<CriterionScore> findBySubmissionIdOrderByCriterionIdAsc(Long submissionId);

    Optional<CriterionScore> findBySubmissionIdAndCriterionId(Long submissionId, Long criterionId);

    /**
     * Stores an entered score (5.4d), replacing the criterion's earlier one for this submission. One
     * statement, decided by the {@code (submission_id, criterion_id)} unique constraint, so two
     * teachers saving at the same moment cannot produce an error: the later one wins. Needs a
     * transaction.
     */
    @Modifying
    @Query(
            value = "insert into criterion_scores (submission_id, criterion_id, score, max_score, comment)"
                    + " values (:submissionId, :criterionId, :score, :maxScore, cast(:comment as text))"
                    + " on conflict (submission_id, criterion_id) do update"
                    + " set score = excluded.score, max_score = excluded.max_score, comment = excluded.comment",
            nativeQuery = true)
    int upsert(
            @Param("submissionId") long submissionId,
            @Param("criterionId") long criterionId,
            @Param("score") BigDecimal score,
            @Param("maxScore") BigDecimal maxScore,
            @Param("comment") String comment);

    /**
     * Removes the submission's scores for these criteria only (grading replaces its {@code TESTS}
     * rows and leaves entered ones alone). Needs a transaction.
     */
    @Modifying
    @Query("delete from CriterionScore c where c.submissionId = :submissionId and c.criterionId in :criterionIds")
    int deleteBySubmissionIdAndCriterionIdIn(
            @Param("submissionId") Long submissionId, @Param("criterionIds") Collection<Long> criterionIds);
}
