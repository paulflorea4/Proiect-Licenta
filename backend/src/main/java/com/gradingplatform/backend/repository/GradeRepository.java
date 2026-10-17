package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Grade;
import java.util.Optional;
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
}
