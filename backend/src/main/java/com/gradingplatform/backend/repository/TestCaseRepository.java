package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.TestCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    /** An assignment's tests in run order (position, then id). */
    List<TestCase> findByAssignmentIdOrderByPositionAscIdAsc(Long assignmentId);

    /** The test, only if it belongs to this assignment. */
    Optional<TestCase> findByIdAndAssignmentId(Long id, Long assignmentId);

    /** The position after the last test of the assignment (1 for the first). */
    @Query("select coalesce(max(t.position), 0) + 1 from TestCase t where t.assignmentId = :assignmentId")
    int nextPosition(@Param("assignmentId") Long assignmentId);
}
