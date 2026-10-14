package com.gradingplatform.backend.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs all the tests of an assignment against one submission and says what happened to each (4.6a).
 * It returns results only: storing them, scoring them and showing them to anyone is Phase 5.
 *
 * <p>The code is built once and each test then runs in its own container ({@link
 * SandboxRunner#runAll}); the test's <em>input</em> is the only part of a test that reaches the sandbox,
 * and the expected output is compared with here, outside it (4.7d). Public and hidden tests are run and
 * judged identically: this class does not know which is which. Every test is run (a failure does not stop
 * the rest), because the score is the weighted fraction of the tests passed.
 *
 * <p>A pass is decided by {@link OutputComparison#passes}, so a cut output, a crash, a timeout and every
 * other not-clean run is never a pass. If the sandbox itself fails, nothing is returned and {@link
 * SandboxFailedException} is thrown: a sandbox failure is never a grade.
 */
@Component
public class SubmissionTestRunner {

    private final SandboxRunner sandbox;
    private final OutputComparison comparison;

    @Autowired
    public SubmissionTestRunner(SandboxRunner sandbox) {
        this(sandbox, TrailingWhitespaceComparison.INSTANCE);
    }

    SubmissionTestRunner(SandboxRunner sandbox, OutputComparison comparison) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.comparison = Objects.requireNonNull(comparison, "comparison");
    }

    /**
     * @param language the language id of the assignment
     * @param sourceFiles the submission's files
     * @param limits the assignment's time and memory limits, applied to every test
     * @param tests the assignment's test cases (public and hidden alike), at least one
     * @return one result per test, in the order of {@code tests}
     * @throws SandboxFailedException when the sandbox failed on any test
     */
    public List<TestRunResult> runAll(
            String language, List<SourceFile> sourceFiles, ExecutionLimits limits, List<TestCaseInput> tests) {
        Objects.requireNonNull(tests, "tests");
        List<TestCaseInput> ordered = List.copyOf(tests);
        List<String> inputs = ordered.stream().map(TestCaseInput::input).toList();
        List<SandboxResult> runs = sandbox.runAll(new SandboxBatchRequest(language, sourceFiles, inputs, limits));
        if (runs.size() != ordered.size()) {
            throw new IllegalStateException("the sandbox returned a different number of results than inputs");
        }
        List<TestRunResult> results = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            TestCaseInput test = ordered.get(i);
            SandboxResult run = runs.get(i);
            results.add(new TestRunResult(
                    test.id(), TestOutcome.of(run, comparison.passes(run, test.expectedOutput())), run));
        }
        return List.copyOf(results);
    }
}
