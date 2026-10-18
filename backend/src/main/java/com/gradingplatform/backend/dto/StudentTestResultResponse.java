package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.sandbox.TestOutcome;

/**
 * One test's result in a student's submission view (5.5a), built on the masked test of 3.5b
 * ({@link StudentTestCaseResponse}). A public test comes with its whole result. A {@link
 * HiddenResult} says only whether it passed: it has no component for the hidden test's real name,
 * input, expected output, actual output or stderr (a program can echo its input to stderr), nor
 * for the outcome or runtime, so no code path can serialise them. {@code
 * StudentTestResultResponseTests} pins the component names.
 */
public sealed interface StudentTestResultResponse
        permits StudentTestResultResponse.PublicResult, StudentTestResultResponse.HiddenResult {

    /**
     * A public test and its result. The result fields are null if the test has none (not run); a
     * compile error has no actual output and the compiler's text in {@code stderr} (5.3b).
     */
    record PublicResult(
            Long id,
            String name,
            String input,
            String expectedOutput,
            TestVisibility visibility,
            int weight,
            int position,
            TestOutcome status,
            String actualOutput,
            String stderr,
            Integer runtimeMs)
            implements StudentTestResultResponse {

        static PublicResult of(StudentTestCaseResponse.PublicTest test, TestResult result) {
            return new PublicResult(
                    test.id(),
                    test.name(),
                    test.input(),
                    test.expectedOutput(),
                    test.visibility(),
                    test.weight(),
                    test.position(),
                    result == null ? null : result.getStatus(),
                    result == null ? null : result.getActualOutput(),
                    result == null ? null : result.getStderr(),
                    result == null ? null : result.getRuntimeMs());
        }
    }

    /** A hidden test: its masked name and place, and pass or fail; a test without a result did not pass. */
    record HiddenResult(Long id, String name, TestVisibility visibility, int position, boolean passed)
            implements StudentTestResultResponse {

        static HiddenResult of(StudentTestCaseResponse.HiddenTest test, TestResult result) {
            return new HiddenResult(
                    test.id(),
                    test.name(),
                    test.visibility(),
                    test.position(),
                    result != null && result.getStatus() == TestOutcome.PASSED);
        }
    }
}
