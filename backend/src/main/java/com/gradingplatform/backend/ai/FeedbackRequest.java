package com.gradingplatform.backend.ai;

import com.gradingplatform.backend.sandbox.TestOutcome;
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
