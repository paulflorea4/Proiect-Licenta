package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.HintReportRequest;
import com.gradingplatform.backend.dto.HintReportResponse;
import com.gradingplatform.backend.dto.HintResponse;
import com.gradingplatform.backend.dto.HintsResponse;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.HintService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI hints on a student's graded submission (Phase 7). */
@RestController
public class HintController {

    private final HintService hintService;

    public HintController(HintService hintService) {
        this.hintService = hintService;
    }

    /**
     * The hints a submission has received, level 1 first (7.2b). Same rule and same role-picked shape
     * as {@code GET /submissions/{id}} (5.5a/5.5b): the student who made it gets {@link
     * HintsResponse.StudentHints} (another student's is 403), the people who run the course get {@link
     * HintsResponse.TeacherHints} with the report fields (another teacher gets the 404 of a missing id).
     * Never calls the AI service.
     */
    @GetMapping("/submissions/{submissionId}/hints")
    public HintsResponse list(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long submissionId) {
        var stored = hintService.list(principal.id(), principal.role(), submissionId);
        return principal.role() == Role.STUDENT
                ? HintsResponse.StudentHints.of(submissionId, stored)
                : HintsResponse.TeacherHints.of(submissionId, stored);
    }

    /**
     * The next hint level for the signed-in student's own graded submission (7.2a): 201 when a new
     * hint was stored, 200 when another request had just stored it or when the AI service could only
     * give its fallback hint (not stored, {@code fallback: true}). Students only; another student's
     * submission is 403 and a missing one 404 (5.5a). No body: the level is the next unused one.
     */
    @PostMapping("/submissions/{submissionId}/hints")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<HintResponse> requestNext(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long submissionId) {
        HintService.Outcome outcome = hintService.requestNext(principal.id(), submissionId);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.hint());
    }

    /**
     * The signed-in student flags one of their own hints as wrong or unhelpful (7.4b), optionally
     * saying why; the body may be left out. 200 with the stored report, the same after a repeat (the
     * first report is kept). Students only; another student's submission is 403, a missing one 404
     * (5.5a), and a level the submission has no stored hint for is 404 {@code HINT_NOT_FOUND}.
     */
    @PostMapping("/submissions/{submissionId}/hints/{level}/report")
    @PreAuthorize("hasRole('STUDENT')")
    public HintReportResponse report(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long submissionId,
            @PathVariable int level,
            @Valid @RequestBody(required = false) HintReportRequest request) {
        String reason = request == null ? null : request.reason();
        return hintService.report(principal.id(), submissionId, level, reason);
    }
}
