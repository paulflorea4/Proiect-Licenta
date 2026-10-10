package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Assignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /** Every assignment of a course, published or not (for the people who run it). */
    Page<Assignment> findByCourseId(Long courseId, Pageable pageable);

    /** Only the published assignments of a course (for students). */
    Page<Assignment> findByCourseIdAndPublishedTrue(Long courseId, Pageable pageable);
}
