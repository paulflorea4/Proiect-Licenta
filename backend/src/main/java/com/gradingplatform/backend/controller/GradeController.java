package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.MyGradeResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.GradeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
