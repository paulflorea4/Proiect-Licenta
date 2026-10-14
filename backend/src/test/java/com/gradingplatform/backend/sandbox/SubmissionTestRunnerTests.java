package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 4.6a without Docker: how one submission's runs become one verdict per test. */
class SubmissionTestRunnerTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(1_000, 64);
    private static final List<SourceFile> FILES = List.of(new SourceFile("main.py", "print(1)"));

    private static SandboxResult ok(String stdout) {
        return new SandboxResult(SandboxStatus.OK, 0, stdout, "", false, false, Duration.ofMillis(3));
    }

    private static SandboxResult with(SandboxStatus status) {
        Integer exit = status == SandboxStatus.OK ? Integer.valueOf(0) : null;
        return new SandboxResult(status, exit, "7\n", "err", false, false, Duration.ofMillis(3));
    }

    /** A sandbox that answers from a list and remembers what it was asked. */
    private static final class FakeSandbox implements SandboxRunner {
        final List<SandboxBatchRequest> batches = new ArrayList<>();
        final List<SandboxResult> answers;

        FakeSandbox(SandboxResult... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public SandboxResult run(SandboxRequest request) {
            throw new AssertionError("the suite must use runAll");
        }

        @Override
        public List<SandboxResult> runAll(SandboxBatchRequest request) {
            batches.add(request);
            return answers;
        }
    }

    private static List<TestRunResult> run(FakeSandbox sandbox, TestCaseInput... tests) {
        return new SubmissionTestRunner(sandbox).runAll("PYTHON", FILES, LIMITS, List.of(tests));
    }

    private static TestCaseInput test(long id, String expected) {
        return new TestCaseInput(id, "in" + id, expected);
    }

    // --- verdicts ---------------------------------------------------------------------------------

    @Test
    void eachStatusBecomesItsOutcomeAndAMatchingOutputPasses() {
        FakeSandbox sandbox = new FakeSandbox(
                ok("7\n"),
                ok("8\n"),
                with(SandboxStatus.TIMEOUT),
                with(SandboxStatus.MEMORY_LIMIT),
                with(SandboxStatus.RUNTIME_ERROR),
                with(SandboxStatus.COMPILE_ERROR));

        List<TestRunResult> results = run(
                sandbox,
                test(1, "7\n"),
                test(2, "7\n"),
                test(3, "7\n"),
                test(4, "7\n"),
                test(5, "7\n"),
                test(6, "7\n"));

        assertThat(results)
                .extracting(TestRunResult::outcome)
                .containsExactly(
                        TestOutcome.PASSED,
                        TestOutcome.FAILED,
                        TestOutcome.TIMEOUT,
                        TestOutcome.MEMORY_LIMIT,
                        TestOutcome.RUNTIME_ERROR,
                        TestOutcome.COMPILE_ERROR);
    }

    @Test
    void resultsComeBackInTheOrderOfTheTestsWithTheirIdsAndTheirRuns() {
        SandboxResult first = ok("a\n");
        SandboxResult second = ok("b\n");
        FakeSandbox sandbox = new FakeSandbox(first, second);

        List<TestRunResult> results = run(sandbox, test(42, "a"), test(7, "b"));

        assertThat(results).extracting(TestRunResult::testCaseId).containsExactly(42L, 7L);
        assertThat(results).extracting(TestRunResult::run).containsExactly(first, second);
        assertThat(results).extracting(TestRunResult::outcome).containsOnly(TestOutcome.PASSED);
    }

    @Test
    void theComparisonIgnoresTrailingWhitespaceAndLineEndings() {
        FakeSandbox sandbox = new FakeSandbox(ok("1  \r\n2\r\n"));

        assertThat(run(sandbox, test(1, "1\n2")).get(0).outcome()).isEqualTo(TestOutcome.PASSED);
    }

    @Test
    void aCutOutputIsFailedEvenWhenTheKeptPrefixEqualsTheExpectedText() {
        SandboxResult cut = new SandboxResult(SandboxStatus.OK, 0, "7\n", "", true, false, Duration.ofMillis(3));

        assertThat(run(new FakeSandbox(cut), test(1, "7\n")).get(0).outcome()).isEqualTo(TestOutcome.FAILED);
    }

    @Test
    void aRuntimeErrorAfterTheRightOutputIsNotAPass() {
        SandboxResult crashed =
                new SandboxResult(SandboxStatus.RUNTIME_ERROR, 1, "7\n", "", false, false, Duration.ZERO);

        assertThat(run(new FakeSandbox(crashed), test(1, "7\n")).get(0).outcome())
                .isEqualTo(TestOutcome.RUNTIME_ERROR);
    }

    @Test
    void theComparisonCanBeReplacedByAStricterOne() {
        FakeSandbox sandbox = new FakeSandbox(ok("7"));
        SubmissionTestRunner strict = new SubmissionTestRunner(sandbox, new OutputComparison() {
            @Override
            public boolean matches(String expected, String actual) {
                return expected.equals(actual);
            }
        });

        List<TestRunResult> results = strict.runAll("PYTHON", FILES, LIMITS, List.of(test(1, "7\n")));

        assertThat(results.get(0).outcome()).isEqualTo(TestOutcome.FAILED);
    }

    // --- what the sandbox is asked and told -------------------------------------------------------

    @Test
    void onlyTheInputsGoToTheSandboxNeverTheExpectedOutputs() {
        FakeSandbox sandbox = new FakeSandbox(ok("x"), ok("x"));

        run(sandbox, new TestCaseInput(1, "first input", "SECRET-ONE"), new TestCaseInput(2, "", "SECRET-TWO"));

        SandboxBatchRequest batch = sandbox.batches.get(0);
        assertThat(batch.stdins()).containsExactly("first input", "");
        assertThat(batch.language()).isEqualTo("PYTHON");
        assertThat(batch.limits()).isEqualTo(LIMITS);
        assertThat(batch.toString()).doesNotContain("SECRET").doesNotContain("first input");
    }

    @Test
    void theSandboxIsCalledOnceForTheWholeSubmission() {
        FakeSandbox sandbox = new FakeSandbox(ok("x"), ok("x"), ok("x"));

        run(sandbox, test(1, "x"), test(2, "x"), test(3, "x"));

        assertThat(sandbox.batches).hasSize(1);
    }

    @Test
    void aSandboxFailureOnAnyTestFailsTheWholeSuiteAndIsNeverAVerdict() {
        FakeSandbox sandbox = new FakeSandbox(ok("7\n"), with(SandboxStatus.SANDBOX_ERROR));

        assertThatThrownBy(() -> run(sandbox, test(1, "7\n"), test(2, "7\n")))
                .isInstanceOf(SandboxFailedException.class)
                .hasMessage("the sandbox failed while running the tests");
    }

    @Test
    void aSandboxThatReturnsTheWrongNumberOfResultsIsABug() {
        FakeSandbox sandbox = new FakeSandbox(ok("7\n"));

        assertThatThrownBy(() -> run(sandbox, test(1, "7\n"), test(2, "7\n")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuiteWithoutTestsOrWithBadInputIsRefused() {
        FakeSandbox sandbox = new FakeSandbox();

        assertThatThrownBy(() -> run(sandbox)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new SubmissionTestRunner(sandbox).runAll("PYTHON", List.of(), LIMITS, List.of(test(1, ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubmissionTestRunner(sandbox).runAll(" ", FILES, LIMITS, List.of(test(1, ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sandbox.batches).isEmpty();
    }

    // --- the types --------------------------------------------------------------------------------

    @Test
    void theOutcomeNamesAreExactlyTheValuesOfTheTestResultsColumn() {
        assertThat(TestOutcome.values())
                .extracting(Enum::name)
                .containsExactly("PASSED", "FAILED", "TIMEOUT", "MEMORY_LIMIT", "RUNTIME_ERROR", "COMPILE_ERROR");
    }

    @Test
    void aTestCaseInputNeverShowsItsTextsInALog() {
        String shown = new TestCaseInput(5, "the-input", "the-expected-output").toString();

        assertThat(shown).contains("id=5").doesNotContain("the-input").doesNotContain("the-expected-output");
    }

    @Test
    void theDefaultRunAllCallsRunOncePerInputInOrder() {
        List<String> seen = new ArrayList<>();
        SandboxRunner single = request -> {
            seen.add(request.stdin());
            return ok(request.stdin());
        };

        List<SandboxResult> results =
                single.runAll(new SandboxBatchRequest("PYTHON", FILES, List.of("a", "b", "c"), LIMITS));

        assertThat(seen).containsExactly("a", "b", "c");
        assertThat(results).extracting(SandboxResult::stdout).containsExactly("a", "b", "c");
    }

    @Test
    void aBatchIsValidExactlyWhenARequestOfItWouldBe() {
        assertThatThrownBy(() -> new SandboxBatchRequest("PYTHON", FILES, List.of(), LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxBatchRequest("", FILES, List.of("x"), LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxBatchRequest(
                        "PYTHON",
                        List.of(new SourceFile("A.py", ""), new SourceFile("a.py", "")),
                        List.of("x"),
                        LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxBatchRequest("PYTHON", FILES, java.util.Arrays.asList("x", null), LIMITS))
                .isInstanceOf(NullPointerException.class);
    }
}
