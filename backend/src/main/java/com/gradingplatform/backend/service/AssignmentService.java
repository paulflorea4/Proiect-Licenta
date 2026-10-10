package com.gradingplatform.backend.service;

import com.gradingplatform.backend.config.AssignmentProperties;
import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.AssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /**
     * Replaces the title, description, language, deadline, limits and starter code. The assignment
     * must be one the user may manage (owning teacher or admin); anyone else who cannot see its
     * course gets the same "not found" as for a missing id. The language is locked once there is a
     * submission; a language or a deadline that is not being changed is not re-checked, so a
     * teacher can still fix a typo after the deadline has passed.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     * @throws UnsupportedLanguageException if the new language is not in the supported list
     * @throws DeadlineNotInFutureException if the deadline is changed to one that is not in the future
     * @throws AssignmentLanguageLockedException if the language changes and there are submissions
     */
    @Transactional
    public Assignment update(long userId, Role role, long assignmentId, AssignmentRequest request) {
        Assignment assignment = lockManageable(userId, role, assignmentId);
        String language = AssignmentProperties.normalize(request.language());
        boolean languageChanged = !language.equals(assignment.getLanguage());
        if (languageChanged && !properties.supports(request.language())) {
            throw new UnsupportedLanguageException();
        }
        if (!request.deadline().equals(assignment.getDeadline())
                && !request.deadline().isAfter(clock.instant())) {
            throw new DeadlineNotInFutureException();
        }
        if (languageChanged && assignments.hasSubmissions(assignmentId)) {
            throw new AssignmentLanguageLockedException();
        }
        assignment.revise(
                request.title(),
                request.description(),
                language,
                request.deadline(),
                request.maxAttempts(),
                request.timeLimitMs(),
                request.memoryLimitMb(),
                request.starterCode(),
                now());
        return assignments.saveAndFlush(assignment);
    }

    /**
     * Makes the assignment visible to students ({@code true}) or hides it again ({@code false}).
     * Setting the state it already has changes nothing. Same access rules as {@link #update}.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Assignment setPublished(long userId, Role role, long assignmentId, boolean published) {
        Assignment assignment = lockManageable(userId, role, assignmentId);
        if (assignment.isPublished() != published) {
            assignment.setPublished(published, now());
        }
        return assignments.saveAndFlush(assignment);
    }

    /**
     * Deletes the assignment together with its rubric criteria and test cases, which are teacher
     * work with no meaning once it is gone. Refused while any submission exists. Same access rules
     * as {@link #update}.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     * @throws AssignmentHasSubmissionsException if anyone has submitted to it
     */
    @Transactional
    public void delete(long userId, Role role, long assignmentId) {
        Assignment assignment = lockManageable(userId, role, assignmentId);
        if (assignments.hasSubmissions(assignmentId)) {
            throw new AssignmentHasSubmissionsException();
        }
        assignments.deleteTestCasesOf(assignmentId);
        assignments.deleteRubricCriteriaOf(assignmentId);
        assignments.delete(assignment);
        assignments.flush();
    }

    /**
     * The assignment, locked for the rest of the transaction, if the user may manage its course.
     * The access check comes first and needs only the course id, so a caller who may not touch the
     * assignment never takes its lock.
     */
    private Assignment lockManageable(long userId, Role role, long assignmentId) {
        long courseId = assignments.findCourseIdById(assignmentId).orElseThrow(AssignmentNotFoundException::new);
        try {
            access.requireManageable(userId, role, courseId);
        } catch (CourseNotFoundException e) {
            throw new AssignmentNotFoundException();
        }
        return assignments.findByIdForUpdate(assignmentId).orElseThrow(AssignmentNotFoundException::new);
    }

    /** The current time at the precision the database keeps (microseconds). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
