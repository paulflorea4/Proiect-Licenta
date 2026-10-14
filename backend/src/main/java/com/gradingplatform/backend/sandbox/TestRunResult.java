package com.gradingplatform.backend.sandbox;

import java.util.Objects;

/**
 * The result of one test for one submission (4.6a), ready for Phase 5 to persist. {@code run} holds the
 * actual output, stderr, exit code and runtime (already cut at the size cap by the sandbox). For a
 * {@link TestOutcome#COMPILE_ERROR} it is the compiler's output, the same for every test of the
 * submission. <b>It is the teacher's view, with a hidden test's actual output in full:</b> what a student
 * sees of a hidden test is masked in Phase 5 (5.5a), never built from this record.
 *
 * @param testCaseId the {@link TestCaseInput#id()} it belongs to
 * @param outcome the verdict
 * @param run what the sandbox reported
 */
public record TestRunResult(long testCaseId, TestOutcome outcome, SandboxResult run) {

    public TestRunResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(run, "run");
    }
}
