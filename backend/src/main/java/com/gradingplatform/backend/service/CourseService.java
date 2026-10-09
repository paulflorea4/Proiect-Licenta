package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.CourseRepository;
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

    /**
     * How many codes are tried before giving up. A collision has probability about 2^-40 per
     * existing course, so a second attempt is already unlikely; this only stops a loop that can
     * never succeed (a broken generator) from running forever.
     */
    static final int MAX_CODE_ATTEMPTS = 5;

    private final CourseRepository courses;
    private final EnrollCodeGenerator codeGenerator;

    public CourseService(CourseRepository courses, EnrollCodeGenerator codeGenerator) {
        this.courses = courses;
        this.codeGenerator = codeGenerator;
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
