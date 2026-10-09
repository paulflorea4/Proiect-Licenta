package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.dto.AssignmentResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.AssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
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
