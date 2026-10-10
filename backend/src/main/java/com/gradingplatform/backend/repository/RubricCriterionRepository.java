package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.RubricCriterion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RubricCriterionRepository extends JpaRepository<RubricCriterion, Long> {

    /** An assignment's criteria in creation (id) order. */
    List<RubricCriterion> findByAssignmentIdOrderByIdAsc(Long assignmentId);

    /** The criterion, only if it belongs to this assignment. */
    Optional<RubricCriterion> findByIdAndAssignmentId(Long id, Long assignmentId);
}
