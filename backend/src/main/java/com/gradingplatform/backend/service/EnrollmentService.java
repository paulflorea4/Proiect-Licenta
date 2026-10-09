package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import com.gradingplatform.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final UserRepository users;
    private final CourseAccess access;

    public EnrollmentService(
            CourseRepository courses, EnrollmentRepository enrollments, UserRepository users, CourseAccess access) {
        this.courses = courses;
        this.enrollments = enrollments;
        this.users = users;
        this.access = access;
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

    /**
     * Takes the student out of the course. Only the membership goes: what the student submitted
     * stays (nothing in a submission references the enrollment), they just lose access to the
     * course. One delete statement decides, so a student who is not in the course (or a course
     * that does not exist) is the same 404 as any course the caller cannot see, and leaving twice
     * is a 404 the second time.
     *
     * @throws CourseNotFoundException if the student is not enrolled in this course
     */
    @Transactional
    public void leave(long studentId, long courseId) {
        if (enrollments.deleteByCourseIdAndStudentId(courseId, studentId) == 0) {
            throw new CourseNotFoundException();
        }
    }

    /**
     * One page of the students enrolled in a course, for the teacher who owns it or an admin.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     * @throws AccessRefusedException if the user can see the course but may not manage it
     */
    @Transactional(readOnly = true)
    public Page<User> listStudents(long userId, Role role, long courseId, Pageable pageable) {
        access.requireManageable(userId, role, courseId);
        return users.findEnrolledIn(courseId, pageable);
    }
}
