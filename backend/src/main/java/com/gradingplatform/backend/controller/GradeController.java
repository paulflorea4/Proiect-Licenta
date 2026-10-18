package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.ManualScoreRequest;
import com.gradingplatform.backend.dto.ManualScoreResponse;
import com.gradingplatform.backend.dto.MyGradeResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.GradeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Grades of submissions. */
@RestController
public class GradeController {

    private final GradeService gradeService;

    public GradeController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    /**
     * The signed-in student's grade for an assignment: their best graded attempt (5.4c). Students
     * only; an assignment the student may not see (not enrolled, a draft, no such id) is the same 404.
     */
    @GetMapping("/assignments/{assignmentId}/my-grade")
    @PreAuthorize("hasRole('STUDENT')")
    public MyGradeResponse myGrade(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long assignmentId) {
        return MyGradeResponse.of(
                assignmentId, gradeService.bestAttemptOf(principal.id(), principal.role(), assignmentId));
    }

    /**
     * A teacher scores a {@code MANUAL} criterion of a graded submission (5.4d), from 0 to the
     * criterion's weight, with an optional comment; saving again replaces it. The course's teacher
     * or an admin; another teacher gets the 404 of a submission that does not exist.
     */
    @PutMapping("/submissions/{submissionId}/manual-scores/{criterionId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ManualScoreResponse enterManualScore(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long submissionId,
            @PathVariable long criterionId,
            @Valid @RequestBody ManualScoreRequest request) {
        return ManualScoreResponse.from(
                gradeService.enterManualScore(principal.id(), principal.role(), submissionId, criterionId, request));
    }
}
