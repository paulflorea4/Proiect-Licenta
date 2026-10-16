package com.gradingplatform.backend.repository;

import com.gradingplatform.backend.entity.Enrollment;
import com.gradingplatform.backend.entity.EnrollmentId;
import java.util.Optional;
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

    /**
     * Locks the student's enrollment in the course until the transaction ends ({@code FOR UPDATE}), so
     * one student's submissions are made one at a time: the attempt number and the attempt limit are
     * then counted exactly (5.1c). It also waits for the student leaving the course, and a leave that
     * comes later waits for the submission. Empty when the student is not enrolled.
     */
    @Query(
            value = "select student_id from enrollments where course_id = :courseId and student_id = :studentId"
                    + " for update",
            nativeQuery = true)
    Optional<Long> lockByCourseIdAndStudentId(@Param("courseId") Long courseId, @Param("studentId") Long studentId);

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
