package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    /** The foreign key `enrollments.student_id` -> `users.id` (Postgres' default name for V3's inline `REFERENCES`). */
    static final String STUDENT_FOREIGN_KEY = "enrollments_student_id_fkey";

    /** The foreign key `enrollments.course_id` -> `courses.id`. */
    static final String COURSE_FOREIGN_KEY = "enrollments_course_id_fkey";

    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;

    public EnrollmentService(CourseRepository courses, EnrollmentRepository enrollments) {
        this.courses = courses;
        this.enrollments = enrollments;
    }

    /**
     * Puts the student in the course that has this code, and returns the course. Joining a course
     * you are already in changes nothing and is not an error: same course, same answer.
     *
     * @param typedCode what the student typed; case and surrounding spaces do not matter
     * @throws EnrollCodeNotFoundException if no course has this code
     * @throws AccountNoLongerExistsException if the student's account was deleted since the token was issued
     */
    @Transactional
    public Course enroll(long studentId, String typedCode) {
        Course course = courses.findByEnrollCode(EnrollCodeGenerator.normalize(typedCode))
                .orElseThrow(EnrollCodeNotFoundException::new);
        try {
            enrollments.insertIfAbsent(course.getId(), studentId);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, STUDENT_FOREIGN_KEY)) {
                throw new AccountNoLongerExistsException();
            }
            if (Constraints.isViolationOf(e, COURSE_FOREIGN_KEY)) {
                // The course was deleted between the lookup and the insert: for the student, a
                // code that no longer matches anything.
                throw new EnrollCodeNotFoundException();
            }
            throw e;
        }
        return course;
    }
}
