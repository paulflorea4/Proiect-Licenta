package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.TestResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    /** A submission's results, in the order they were stored (the tests' run order). */
    List<TestResult> findBySubmissionIdOrderByIdAsc(Long submissionId);

    /** Removes the submission's results, so a new run replaces them (5.4b). Needs a transaction. */
    @Modifying
    @Query("delete from TestResult r where r.submissionId = :submissionId")
    int deleteBySubmissionId(@Param("submissionId") Long submissionId);
}
