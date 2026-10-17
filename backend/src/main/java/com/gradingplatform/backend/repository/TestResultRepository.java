package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.TestResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    /** A submission's results, in the order they were stored (the tests' run order). */
    List<TestResult> findBySubmissionIdOrderByIdAsc(Long submissionId);
}
