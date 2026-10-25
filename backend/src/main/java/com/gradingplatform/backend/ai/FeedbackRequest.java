package com.gradingplatform.backend.ai;

import com.gradingplatform.backend.dto.StudentSubmissionResponse;
import com.gradingplatform.backend.dto.StudentTestResultResponse;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.sandbox.TestOutcome;
import java.util.ArrayList;
import java.util.List;

/**
 * The body of the AI service's `POST /feedback`, mirroring its contract field for field (6.3a,
 * `ai-service/src/ai_service/schemas.py`; `AiContractMirrorTests` fails if they drift). Every name is
 * camelCase on the wire, as Jackson writes record components.
 *
 * <p>A hidden test is {@link HiddenTestResult}, whether it passed and nothing else: there is no field
 * here in which a hidden test's input, expected output or output could travel. 7.1b builds this from a
 * submission.
 *
 * @param assignmentDescription the teacher's description
 * @param language the assignment's language id, e.g. `PYTHON`
 * @param code the student's code, exactly as submitted
 * @param compilerOutput the compiler's message when the build failed, else null
 * @param publicTests the public tests in full, in run order
 * @param hiddenTests the hidden tests as pass/fail only, in run order
 * @param hintLevel 1 nudge, 2 pointer, 3 partial explanation
 */
public record FeedbackRequest(
        String assignmentDescription,
        String language,
        String code,
        String compilerOutput,
        List<PublicTestResult> publicTests,
        List<HiddenTestResult> hiddenTests,
        int hintLevel) {

    public FeedbackRequest {
        publicTests = List.copyOf(publicTests);
        hiddenTests = List.copyOf(hiddenTests);
    }

    /**
     * The request for a hint on this submission (7.1b): **built from the student's own view of it**
     * (5.5a), never from a test case or a test result. That view is masked by type: a hidden test is
     * a {@link StudentTestResultResponse.HiddenResult}, whether it passed and nothing else, so nothing
     * more of a hidden test can reach the AI service than the student can see. The tests keep the view's
     * run order.
     *
     * @param submission the student's view of their graded submission ({@code SubmissionViewService})
     * @param assignmentDescription the assignment's description, as the student sees it
     * @param hintLevel 1 to 3
     * @throws IllegalArgumentException when the submission is not graded or the level is not 1 to 3
     * @throws IllegalStateException when a public test of a graded submission has no result
     */
    public static FeedbackRequest of(
            StudentSubmissionResponse submission, String assignmentDescription, int hintLevel) {
        if (submission.status() != SubmissionStatus.GRADED) {
            throw new IllegalArgumentException("Only a graded submission can get a hint");
        }
        if (hintLevel < 1 || hintLevel > 3) {
            throw new IllegalArgumentException("A hint level is 1, 2 or 3");
        }
        var publicTests = new ArrayList<PublicTestResult>();
        var hiddenTests = new ArrayList<HiddenTestResult>();
        for (StudentTestResultResponse test : submission.tests()) {
            if (test instanceof StudentTestResultResponse.PublicResult p) {
                if (p.status() == null) {
                    // Tests are locked after the first submission, so a graded one has every result.
                    throw new IllegalStateException("A graded submission's public test has no result");
                }
                publicTests.add(new PublicTestResult(
                        p.name(), p.input(), p.expectedOutput(), p.status(), p.actualOutput(), p.stderr()));
            } else {
                // The only other kind (the interface is sealed): whether it passed, nothing more.
                hiddenTests.add(new HiddenTestResult(((StudentTestResultResponse.HiddenResult) test).passed()));
            }
        }
        return new FeedbackRequest(
                assignmentDescription,
                submission.language(),
                submission.sourceCode(),
                submission.compilerOutput(),
                publicTests,
                hiddenTests,
                hintLevel);
    }

    /**
     * A public test: the student may see all of it, so the model may too.
     *
     * @param actualOutput null when no program ran (a compile error)
     * @param stderr null when there is none to send
     */
    public record PublicTestResult(
            String name, String input, String expectedOutput, TestOutcome status, String actualOutput, String stderr) {}

    /** A hidden test: whether it passed, and nothing else. */
    public record HiddenTestResult(boolean passed) {}
}
