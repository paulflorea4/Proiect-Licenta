package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.dto.CourseResponse;
import com.gradingplatform.backend.dto.EnrollRequest;
import com.gradingplatform.backend.dto.PageResponse;
import com.gradingplatform.backend.dto.StudentSummaryResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.CourseService;
import com.gradingplatform.backend.service.EnrollmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/courses")
public class CourseController {

    private final CourseService courseService;
    private final EnrollmentService enrollmentService;

    public CourseController(CourseService courseService, EnrollmentService enrollmentService) {
        this.courseService = courseService;
        this.enrollmentService = enrollmentService;
    }

    /**
     * The courses the caller can see, as {@link PageResponse} in id order: a teacher's own, a
     * student's enrolled courses, all of them for an admin. Any signed-in user may call it; what
     * they get depends only on who they are. Students do not receive the enrollment code.
     */
    @GetMapping
    public PageResponse<CourseResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var pageable = PageResponse.pageable(page, size, Sort.by("id"));
        return PageResponse.of(
                courseService.listVisibleTo(principal.id(), principal.role(), pageable),
                course -> CourseResponse.forRole(course, principal.role()));
    }

    /**
     * One course. A teacher may read their own, a student one they are enrolled in, an admin any;
     * for everyone else it is a 404, the same as for an id that does not exist. A student's answer
     * has no enrollment code.
     */
    @GetMapping("/{id}")
    public CourseResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        return CourseResponse.forRole(
                courseService.getVisibleTo(principal.id(), principal.role(), id), principal.role());
    }

    /**
     * A student joins the course that has the code in the body. Answers 200 with the course as a
     * student sees it (no enrollment code), also when the student was already in it. A code that
     * matches nothing is a 404, so the answer says nothing more than that.
     */
    @PostMapping("/enroll")
    @PreAuthorize("hasRole('STUDENT')")
    public CourseResponse enroll(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody EnrollRequest request) {
        return CourseResponse.forRole(enrollmentService.enroll(principal.id(), request.code()), principal.role());
    }

    /**
     * A student leaves a course. Answers 204. Their past submissions stay; they lose access to the
     * course. A course they are not in (or that does not exist) is the 404 of any course they cannot
     * see.
     */
    @DeleteMapping("/{id}/enrollment")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        enrollmentService.leave(principal.id(), id);
    }

    /**
     * The students enrolled in a course, as {@link PageResponse} in student id order. The owning
     * teacher or an admin; any other teacher gets the 404 of a course they cannot see.
     */
    @GetMapping("/{id}/students")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public PageResponse<StudentSummaryResponse> students(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long id,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var pageable = PageResponse.pageable(page, size, Sort.by("id"));
        return PageResponse.of(
                enrollmentService.listStudents(principal.id(), principal.role(), id, pageable),
                StudentSummaryResponse::from);
    }

    /**
     * Replaces a course's title and description (the owner and the enrollment code stay). The
     * owning teacher or an admin; any other teacher gets the 404 of a course they cannot see.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public CourseResponse update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long id,
            @Valid @RequestBody CourseRequest request) {
        return CourseResponse.forRole(
                courseService.update(principal.id(), principal.role(), id, request), principal.role());
    }

    /**
     * Deletes a course and its enrollments. The owning teacher or an admin. Blocked with a 409
     * while the course still has assignments: nothing students submitted is ever deleted with it.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        courseService.delete(principal.id(), principal.role(), id);
    }

    /**
     * Creates a course owned by the caller and generates its enrollment code. Teachers only: an
     * admin does not create courses (the row says teacher, and there is no role hierarchy, 2.5a).
     */
    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CourseRequest request) {
        return CourseResponse.from(courseService.create(principal.id(), request));
    }
}
