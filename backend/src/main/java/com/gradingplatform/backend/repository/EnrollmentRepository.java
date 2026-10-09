package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, EnrollmentId> {

    /** Removes every enrollment of a course (used when the course itself is deleted). */
    @Modifying
    @Query("delete from Enrollment e where e.id.courseId = :courseId")
    int deleteByCourseId(@Param("courseId") Long courseId);
}
