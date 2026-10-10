package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.RubricCriterion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RubricCriterionRepository extends JpaRepository<RubricCriterion, Long> {

    /** An assignment's criteria in creation (id) order. */
    List<RubricCriterion> findByAssignmentIdOrderByIdAsc(Long assignmentId);

    /** The criterion, only if it belongs to this assignment. */
    Optional<RubricCriterion> findByIdAndAssignmentId(Long id, Long assignmentId);

    /**
     * How many `TESTS` criteria of the assignment have no test case at all (a native `NOT EXISTS`
     * query: this repository has no `TestCase` relation to join). Each of them would score 0 / 0.
     */
    @Query(
            value = "select count(*) from rubric_criteria c where c.assignment_id = :assignmentId "
                    + "and c.type = 'TESTS' and not exists (select 1 from test_cases t where t.criterion_id = c.id)",
            nativeQuery = true)
    int countTestsCriteriaWithoutTests(@Param("assignmentId") Long assignmentId);
}
