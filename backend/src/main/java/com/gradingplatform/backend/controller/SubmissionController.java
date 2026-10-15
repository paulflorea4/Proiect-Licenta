package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.SubmissionAcceptedResponse;
import com.gradingplatform.backend.dto.SubmissionRequest;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.SubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Students' submissions to an assignment. */
@RestController
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * A student submits code as text. Answers 202 with the new submission, {@code QUEUED}, without
     * waiting for it to be graded. Students only; an assignment the student may not see (not
     * enrolled, a draft, no such id) is the same 404.
     */
    @PostMapping("/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionAcceptedResponse submit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @Valid @RequestBody SubmissionRequest request) {
        return SubmissionAcceptedResponse.from(
                submissionService.submit(principal.id(), principal.role(), assignmentId, request));
    }
}
