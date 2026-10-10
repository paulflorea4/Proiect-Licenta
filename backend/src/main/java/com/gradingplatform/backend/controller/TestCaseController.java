package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.StudentTestCasesResponse;
import com.gradingplatform.backend.dto.TestCaseRequest;
import com.gradingplatform.backend.dto.TestCaseResponse;
import com.gradingplatform.backend.dto.TestCasesResponse;
import com.gradingplatform.backend.dto.TestListResponse;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.TestCaseService;
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
 * The test cases of an assignment. The writes, and the list in full (hidden tests included), are
 * for the people who run its course (owning teacher, admin); students are refused the writes by
 * role and get a masked list (3.5b). Another teacher gets the 404 of an assignment that does not
 * exist.
 */
@RestController
@RequestMapping("/assignments/{assignmentId}/tests")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    /**
     * The tests in run order. The token's role picks the shape: the teacher or admin who runs the
     * course gets every test in full, a student of a course they are in gets a published
     * assignment's tests with the hidden ones masked (no name, input or expected output), and
     * everyone else the 404 of an assignment that does not exist.
     */
    @GetMapping
    public TestListResponse list(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long assignmentId) {
        var tests = testCaseService.list(principal.id(), principal.role(), assignmentId);
        return principal.role() == Role.STUDENT ? StudentTestCasesResponse.of(tests) : TestCasesResponse.of(tests);
    }

    /** Adds a test. Only on a draft with no submissions (409 otherwise). */
    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @Valid @RequestBody TestCaseRequest request) {
        return TestCaseResponse.from(testCaseService.create(principal.id(), principal.role(), assignmentId, request));
    }

    /** Replaces a test. Only on a draft with no submissions (409 otherwise). */
    @PutMapping("/{testId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public TestCaseResponse update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @PathVariable long testId,
            @Valid @RequestBody TestCaseRequest request) {
        return TestCaseResponse.from(
                testCaseService.update(principal.id(), principal.role(), assignmentId, testId, request));
    }

    /** Deletes a test. Only on a draft with no submissions (409 otherwise). */
    @DeleteMapping("/{testId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long assignmentId,
            @PathVariable long testId) {
        testCaseService.delete(principal.id(), principal.role(), assignmentId, testId);
    }
}
