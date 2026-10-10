package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.dto.AssignmentResponse;
import com.gradingplatform.backend.dto.PageResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.AssignmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * The assignments of a course, as {@link PageResponse} in id order. Anyone who can see the
     * course may call it: a teacher or admin gets every assignment, a student only the published
     * ones (and the student's shape of each). A course the caller cannot see is the usual 404.
     */
    @GetMapping("/courses/{courseId}/assignments")
    public PageResponse<AssignmentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long courseId,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var pageable = PageResponse.pageable(page, size, Sort.by("id"));
        return PageResponse.of(
                assignmentService.listIn(principal.id(), principal.role(), courseId, pageable),
                a -> AssignmentResponse.forRole(a, principal.role()));
    }

    /**
     * One assignment. Visible to whoever can see its course, except that a student gets a 404
     * {@code ASSIGNMENT_NOT_FOUND} for an unpublished one, the same as for an id that does not exist.
     */
    @GetMapping("/assignments/{id}")
    public AssignmentResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        return AssignmentResponse.forRole(
                assignmentService.getVisibleTo(principal.id(), principal.role(), id), principal.role());
    }

    /**
     * Creates an unpublished assignment in a course. Teachers only (admin excluded, as for creating
     * a course); another teacher's course is the 404 of a course they cannot see.
     */
    @PostMapping("/courses/{courseId}/assignments")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long courseId,
            @Valid @RequestBody AssignmentRequest request) {
        return AssignmentResponse.from(assignmentService.create(principal.id(), principal.role(), courseId, request));
    }
}
