package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, EnrollmentId> {

    /**
     * Adds the student to the course unless they are already in it. The primary key decides, in
     * one statement, so two simultaneous joins both succeed and leave one row (no exception, which
     * would also abort the surrounding transaction).
     *
     * @return 1 if the enrollment was created, 0 if it already existed
     */
    @Modifying
    @Query(
            value = "INSERT INTO enrollments (course_id, student_id) VALUES (:courseId, :studentId)"
                    + " ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("courseId") Long courseId, @Param("studentId") Long studentId);

    /** Removes every enrollment of a course (used when the course itself is deleted). */
    @Modifying
    @Query("delete from Enrollment e where e.id.courseId = :courseId")
    int deleteByCourseId(@Param("courseId") Long courseId);

    /**
     * Removes one student from one course, in one statement.
     *
     * @return 1 if the student was enrolled, 0 if there was nothing to remove
     */
    @Modifying
    @Query("delete from Enrollment e where e.id.courseId = :courseId and e.id.studentId = :studentId")
    int deleteByCourseIdAndStudentId(@Param("courseId") Long courseId, @Param("studentId") Long studentId);
}
