package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.CriterionScore;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CriterionScoreRepository extends JpaRepository<CriterionScore, Long> {

    /** A submission's criterion scores, by criterion id. */
    List<CriterionScore> findBySubmissionIdOrderByCriterionIdAsc(Long submissionId);

    /**
     * Removes the submission's scores for these criteria only (grading replaces its {@code TESTS}
     * rows and leaves entered ones alone). Needs a transaction.
     */
    @Modifying
    @Query("delete from CriterionScore c where c.submissionId = :submissionId and c.criterionId in :criterionIds")
    int deleteBySubmissionIdAndCriterionIdIn(
            @Param("submissionId") Long submissionId, @Param("criterionIds") Collection<Long> criterionIds);
}
