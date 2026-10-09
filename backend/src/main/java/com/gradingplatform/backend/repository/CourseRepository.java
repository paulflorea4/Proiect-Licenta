package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /** The courses a teacher owns. */
    Page<Course> findByTeacherId(Long teacherId, Pageable pageable);

    /** The courses a student is enrolled in. */
    @Query(
            "select c from Course c where c.id in (select e.id.courseId from Enrollment e where e.id.studentId = :studentId)")
    Page<Course> findEnrolledBy(@Param("studentId") Long studentId, Pageable pageable);
}
