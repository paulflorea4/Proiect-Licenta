package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    /** The unique constraint on `courses.enroll_code` (Postgres' default name for V2's `UNIQUE`). */
    static final String ENROLL_CODE_UNIQUE_CONSTRAINT = "courses_enroll_code_key";

    /** The foreign key `courses.teacher_id` -> `users.id` (Postgres' default name for V2's inline `REFERENCES`). */
    static final String TEACHER_FOREIGN_KEY = "courses_teacher_id_fkey";

    /** The foreign key `assignments.course_id` -> `courses.id` (Postgres' default name for V4's inline `REFERENCES`). */
    static final String ASSIGNMENTS_COURSE_FOREIGN_KEY = "assignments_course_id_fkey";

    /**
     * How many codes are tried before giving up. A collision has probability about 2^-40 per
     * existing course, so a second attempt is already unlikely; this only stops a loop that can
     * never succeed (a broken generator) from running forever.
     */
    static final int MAX_CODE_ATTEMPTS = 5;

    private final CourseRepository courses;
    private final EnrollCodeGenerator codeGenerator;
    private final CourseAccess access;
    private final EnrollmentRepository enrollments;

    public CourseService(
            CourseRepository courses,
            EnrollCodeGenerator codeGenerator,
            CourseAccess access,
            EnrollmentRepository enrollments) {
        this.courses = courses;
        this.codeGenerator = codeGenerator;
        this.access = access;
        this.enrollments = enrollments;
    }

    /**
     * Replaces the course's title and description. The owner and the enrollment code do not change.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     * @throws AccessRefusedException if the user can see the course but may not change it
     */
    @Transactional
    public Course update(long userId, Role role, long courseId, CourseRequest request) {
        Course course = access.requireManageable(userId, role, courseId);
        course.update(request.title(), request.description());
        return courses.saveAndFlush(course);
    }

    /**
     * Deletes the course and its enrollments (membership only: no student work hangs off them).
     * A course that still has assignments is refused: their submissions and grades must never go
     * with it silently. The foreign key from `assignments` decides, not a lookup beforehand, so an
     * assignment created at the same moment cannot slip through; the refusal rolls back the
     * enrollment deletion too, leaving the course exactly as it was.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     * @throws AccessRefusedException if the user can see the course but may not delete it
     * @throws CourseHasAssignmentsException if the course still has assignments
     */
    @Transactional
    public void delete(long userId, Role role, long courseId) {
        Course course = access.requireManageable(userId, role, courseId);
        enrollments.deleteByCourseId(courseId);
        try {
            courses.delete(course);
            courses.flush();
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, ASSIGNMENTS_COURSE_FOREIGN_KEY)) {
                throw new CourseHasAssignmentsException();
            }
            throw e;
        }
    }

    /**
     * One course, if this user may see it (see {@link CourseAccess}).
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     */
    public Course getVisibleTo(long userId, Role role, long courseId) {
        return access.requireViewable(userId, role, courseId);
    }

    /**
     * The courses this user can see, one page of them: a teacher's own, a student's enrolled
     * courses, every course for an admin. Which of the three follows from the role, so the caller
     * cannot ask for another user's list. The role is the token's (2.4b), so a promotion applies
     * after the next signin.
     */
    @Transactional(readOnly = true)
    public Page<Course> listVisibleTo(long userId, Role role, Pageable pageable) {
        return switch (role) {
            case TEACHER -> courses.findByTeacherId(userId, pageable);
            case STUDENT -> courses.findEnrolledBy(userId, pageable);
            case ADMIN -> courses.findAll(pageable);
        };
    }

    /**
     * Creates a course owned by {@code teacherId} with a freshly generated enrollment code.
     *
     * <p>Deliberately not {@code @Transactional}: Postgres aborts a transaction at the first
     * constraint error, so a retry must start a new one. Each attempt is the repository's own
     * transaction, and the unique constraint, not a lookup beforehand, decides whether a code is
     * free (it also holds when two courses are created at the same moment).
     *
     * @throws AccountNoLongerExistsException if the teacher's account has been deleted since the token was issued
     * @throws IllegalStateException if {@value #MAX_CODE_ATTEMPTS} codes in a row were already taken
     */
    public Course create(long teacherId, CourseRequest request) {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            Course course = new Course(request.title(), request.description(), teacherId, codeGenerator.next());
            try {
                return courses.saveAndFlush(course);
            } catch (DataIntegrityViolationException e) {
                if (Constraints.isViolationOf(e, ENROLL_CODE_UNIQUE_CONSTRAINT)) {
                    continue; // taken: generate another
                }
                if (Constraints.isViolationOf(e, TEACHER_FOREIGN_KEY)) {
                    throw new AccountNoLongerExistsException();
                }
                throw e;
            }
        }
        throw new IllegalStateException("No free enrollment code after " + MAX_CODE_ATTEMPTS
                + " attempts; the generator is not producing distinct codes");
    }
}
