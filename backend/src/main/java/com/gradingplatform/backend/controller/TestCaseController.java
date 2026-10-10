package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.TestCaseRequest;
import com.gradingplatform.backend.dto.TestCaseResponse;
import com.gradingplatform.backend.dto.TestCasesResponse;
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
 * The test cases of an assignment, in full (hidden tests included), for the people who run its
 * course (owning teacher, admin). Students are refused by role; another teacher gets the 404 of an
 * assignment that does not exist. What students see of the tests is a separate shape (3.5b).
 */
@RestController
@RequestMapping("/assignments/{assignmentId}/tests")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    /** The tests in run order. */
    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public TestCasesResponse list(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long assignmentId) {
        return TestCasesResponse.of(testCaseService.list(principal.id(), principal.role(), assignmentId));
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
