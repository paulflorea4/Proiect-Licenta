package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Course;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /** The course with this enrollment code (already normalised, see `EnrollCodeGenerator.normalize`). */
    Optional<Course> findByEnrollCode(String enrollCode);

    /** The courses a teacher owns. */
    Page<Course> findByTeacherId(Long teacherId, Pageable pageable);

    /** The courses a student is enrolled in. */
    @Query(
            "select c from Course c where c.id in (select e.id.courseId from Enrollment e where e.id.studentId = :studentId)")
    Page<Course> findEnrolledBy(@Param("studentId") Long studentId, Pageable pageable);
}
