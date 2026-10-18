package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.StudentSubmissionResponse;
import com.gradingplatform.backend.dto.SubmissionAcceptedResponse;
import com.gradingplatform.backend.dto.SubmissionRequest;
import com.gradingplatform.backend.dto.SubmissionResponse;
import com.gradingplatform.backend.dto.TeacherSubmissionResponse;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.SubmissionService;
import com.gradingplatform.backend.service.SubmissionViewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Students' submissions to an assignment, and reading one back with its result. */
@RestController
public class SubmissionController {

    private final SubmissionService submissionService;
    private final SubmissionViewService submissionViewService;

    public SubmissionController(SubmissionService submissionService, SubmissionViewService submissionViewService) {
        this.submissionService = submissionService;
        this.submissionViewService = submissionViewService;
    }

    /**
     * A student submits code as text. Answers 202 with the new submission, {@code QUEUED}, without
     * waiting for it to be graded. Students only; an assignment the student may not see (not
     * enrolled, a draft, no such id) is the same 404.
     */
    @PostMapping(path = "/assignments/{assignmentId}/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionAcceptedResponse submit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @Valid @RequestBody SubmissionRequest request) {
        return SubmissionAcceptedResponse.from(
                submissionService.submit(principal.id(), principal.role(), assignmentId, request));
    }

    /**
     * A submission with its result. The token's role picks the shape: a student gets their own with
     * hidden tests reduced to pass or fail (5.5a; another student's is a 403), the teacher or admin
     * who runs the course gets everything (5.5b; another teacher the 404 of an id that does not
     * exist). One authorization path for both, in {@link SubmissionViewService#view}.
     */
    @GetMapping("/submissions/{submissionId}")
    public SubmissionResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long submissionId) {
        var details = submissionViewService.view(principal.id(), principal.role(), submissionId);
        return principal.role() == Role.STUDENT
                ? StudentSubmissionResponse.of(details)
                : TeacherSubmissionResponse.of(details);
    }

    /**
     * The same submission with the code uploaded as one file (5.1b): {@code multipart/form-data} with
     * the file in the part {@code file}. Its extension must be the assignment's language's; other
     * parts are ignored. Same roles, answer and 404 as {@link #submit}.
     */
    @PostMapping(path = "/assignments/{assignmentId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionAcceptedResponse submitFile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @RequestPart(name = "file", required = false) List<MultipartFile> files) {
        return SubmissionAcceptedResponse.from(
                submissionService.submitFile(principal.id(), principal.role(), assignmentId, files));
    }
}
