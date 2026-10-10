package com.gradingplatform.backend.service;

import com.gradingplatform.backend.config.AssignmentProperties;
import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.AssignmentRepository;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {

    /** The foreign key `assignments.course_id` -> `courses.id` (Postgres default name for V4's inline `REFERENCES`). */
    static final String COURSE_FOREIGN_KEY = "assignments_course_id_fkey";

    private final AssignmentRepository assignments;
    private final CourseAccess access;
    private final AssignmentProperties properties;
    private final Clock clock;

    public AssignmentService(
            AssignmentRepository assignments, CourseAccess access, AssignmentProperties properties, Clock clock) {
        this.assignments = assignments;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates an unpublished assignment in the course. The language is stored upper-cased, as the
     * list has it.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     * @throws AccessRefusedException if the user can see the course but may not change it
     * @throws UnsupportedLanguageException if the language is not in the supported list
     * @throws DeadlineNotInFutureException if the deadline is not after the current time
     */
    @Transactional
    public Assignment create(long userId, Role role, long courseId, AssignmentRequest request) {
        // Access first: a caller who cannot see the course learns nothing from the other checks.
        access.requireManageable(userId, role, courseId);
        if (!properties.supports(request.language())) {
            throw new UnsupportedLanguageException();
        }
        if (!request.deadline().isAfter(clock.instant())) {
            throw new DeadlineNotInFutureException();
        }
        Assignment assignment = new Assignment(
                courseId,
                request.title(),
                request.description(),
                AssignmentProperties.normalize(request.language()),
                request.deadline(),
                request.maxAttempts(),
                request.timeLimitMs(),
                request.memoryLimitMb(),
                request.starterCode());
        try {
            return assignments.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, COURSE_FOREIGN_KEY)) {
                // The course was deleted between the access check and the insert.
                throw new CourseNotFoundException();
            }
            throw e;
        }
    }

    /**
     * One page of a course's assignments, in id order: all of them for a teacher or admin who can
     * see the course, only the published ones for a student.
     *
     * @throws CourseNotFoundException if there is no such course or the user may not see it
     */
    @Transactional(readOnly = true)
    public Page<Assignment> listIn(long userId, Role role, long courseId, Pageable pageable) {
        access.requireViewable(userId, role, courseId);
        return role == Role.STUDENT
                ? assignments.findByCourseIdAndPublishedTrue(courseId, pageable)
                : assignments.findByCourseId(courseId, pageable);
    }

    /**
     * One assignment, if this user may see it: its course must be visible to them and, for a
     * student, it must be published.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional(readOnly = true)
    public Assignment getVisibleTo(long userId, Role role, long assignmentId) {
        return assignments
                .findById(assignmentId)
                .filter(a -> access.canView(userId, role, a.getCourseId()))
                .filter(a -> access.canViewAssignment(role, a))
                .orElseThrow(AssignmentNotFoundException::new);
    }
}
