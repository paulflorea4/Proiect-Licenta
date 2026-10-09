package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.EnrollmentId;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.CourseRepository;
import com.gradingplatform.backend.repository.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that answers "may this user see this course?" (3.1c). Every later endpoint that
 * touches something inside a course (assignments, tests, submissions) asks here instead of
 * repeating the rule, so the rule cannot drift between endpoints.
 *
 * <p>The rule, by the caller's role (the token's, 2.4b): a teacher sees the courses they own, a
 * student the courses they are enrolled in, an admin every course. {@code CourseService.listVisibleTo}
 * is the same rule written as a query; a test keeps the two in step.
 *
 * <p>A course the caller may not see is reported exactly like one that does not exist
 * ({@link CourseNotFoundException}, 404), so a caller cannot find out which ids are taken.
 */
@Service
public class CourseAccess {

    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;

    public CourseAccess(CourseRepository courses, EnrollmentRepository enrollments) {
        this.courses = courses;
        this.enrollments = enrollments;
    }

    /** True if this user may see the course. */
    @Transactional(readOnly = true)
    public boolean canView(long userId, Role role, Course course) {
        return switch (role) {
            case ADMIN -> true;
            case TEACHER -> course.getTeacherId() == userId;
            case STUDENT -> enrollments.existsById(new EnrollmentId(course.getId(), userId));
        };
    }

    /**
     * True if this user may change or delete the course: the teacher who owns it, or an admin. A
     * student never may. Anyone who may manage a course may also see it.
     */
    public boolean canManage(long userId, Role role, Course course) {
        return switch (role) {
            case ADMIN -> true;
            case TEACHER -> course.getTeacherId() == userId;
            case STUDENT -> false;
        };
    }

    /**
     * The course with this id, if this user may change it.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not even see it
     * @throws AccessRefusedException if the user can see the course but may not change it
     */
    @Transactional(readOnly = true)
    public Course requireManageable(long userId, Role role, long courseId) {
        Course course = requireViewable(userId, role, courseId);
        if (!canManage(userId, role, course)) {
            throw new AccessRefusedException();
        }
        return course;
    }

    /**
     * The course with this id, if this user may see it.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     */
    @Transactional(readOnly = true)
    public Course requireViewable(long userId, Role role, long courseId) {
        return courses.findById(courseId)
                .filter(course -> canView(userId, role, course))
                .orElseThrow(CourseNotFoundException::new);
    }
}
