package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.RubricCriterionRequest;
import com.gradingplatform.backend.dto.RubricCriterionResponse;
import com.gradingplatform.backend.dto.RubricResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.RubricService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rubric of an assignment, for the people who run its course (owning teacher, admin). Students
 * are refused by role; another teacher gets the 404 of an assignment that does not exist.
 */
@RestController
@RequestMapping("/assignments/{assignmentId}/rubric")
public class RubricController {

    private final RubricService rubricService;

    public RubricController(RubricService rubricService) {
        this.rubricService = rubricService;
    }

    /** The criteria in creation order, with the sum of their weights. */
    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public RubricResponse list(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long assignmentId) {
        return RubricResponse.of(rubricService.list(principal.id(), principal.role(), assignmentId));
    }

    /** Adds a criterion. Only {@code TESTS} is available yet; 409 once the assignment has submissions. */
    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RubricCriterionResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @Valid @RequestBody RubricCriterionRequest request) {
        return RubricCriterionResponse.from(
                rubricService.create(principal.id(), principal.role(), assignmentId, request));
    }

    /** Replaces a criterion's name, type and weight; 409 once the assignment has submissions. */
    @PutMapping("/{criterionId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public RubricCriterionResponse update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @PathVariable long criterionId,
            @Valid @RequestBody RubricCriterionRequest request) {
        return RubricCriterionResponse.from(
                rubricService.update(principal.id(), principal.role(), assignmentId, criterionId, request));
    }

    /** Deletes a criterion; 409 once the assignment has submissions or while tests belong to it. */
    @DeleteMapping("/{criterionId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @PathVariable long criterionId) {
        rubricService.delete(principal.id(), principal.role(), assignmentId, criterionId);
    }
}
