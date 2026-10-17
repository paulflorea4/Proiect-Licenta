package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.repository.AssignmentRepository;
import com.gradingplatform.backend.repository.SubmissionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import com.gradingplatform.backend.sandbox.ExecutionLimits;
import com.gradingplatform.backend.sandbox.SandboxFailedException;
import com.gradingplatform.backend.sandbox.SandboxRequest;
import com.gradingplatform.backend.sandbox.SandboxResult;
import com.gradingplatform.backend.sandbox.SandboxRunner;
import com.gradingplatform.backend.sandbox.SandboxStatus;
import com.gradingplatform.backend.sandbox.SourceFile;
import com.gradingplatform.backend.sandbox.SubmissionTestRunner;
import com.gradingplatform.backend.sandbox.TestLanguages;
import com.gradingplatform.backend.sandbox.TestOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 5.3a: what the grader hands the runner and what it stores, against mocked repositories and a
 * scripted sandbox (no Docker). The same against real containers and the real database is in {@code
 * RealHttpGradingTests}.
 */
class SubmissionGraderTests {

    private static final long SUBMISSION = 7;
    private static final long ASSIGNMENT = 3;

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TestCaseRepository testCases = mock(TestCaseRepository.class);
    private final SubmissionResultWriter results = mock(SubmissionResultWriter.class);

    /** Every request the sandbox was given. */
    private final List<SandboxRequest> requests = new ArrayList<>();

    /** What the sandbox answers, by stdin. */
    private Function<String, SandboxResult> answer;

    private SubmissionGrader grader;

    @BeforeEach
    void world() {
        SandboxRunner sandbox = request -> {
            requests.add(request);
            return answer.apply(request.stdin());
        };
        grader = new SubmissionGrader(
                submissions,
                assignments,
                testCases,
                results,
                TestLanguages.javaAndPython(),
                new SubmissionTestRunner(sandbox));
        Submission submission =
                new Submission(ASSIGNMENT, 5L, "PYTHON", "print(input())", 1, Instant.parse("2030-01-01T00:00:00Z"));
        when(submissions.findById(SUBMISSION)).thenReturn(Optional.of(submission));
        when(assignments.findById(ASSIGNMENT))
                .thenReturn(Optional.of(new Assignment(
                        1L, "Echo", "d", "PYTHON", Instant.parse("2031-01-01T00:00:00Z"), null, 1500, 128, null)));
    }

    private static TestCase test(long id, String input, String expected, TestVisibility visibility, int position) {
        TestCase test = new TestCase(ASSIGNMENT, 9L, "t" + id, input, expected, visibility, 1, position);
        ReflectionTestUtils.setField(test, "id", id);
        return test;
    }

    private void tests(TestCase... tests) {
        when(testCases.findByAssignmentIdOrderByPositionAscIdAsc(ASSIGNMENT)).thenReturn(List.of(tests));
    }

    private static SandboxResult ok(String stdout) {
        return new SandboxResult(SandboxStatus.OK, 0, stdout, "", false, false, Duration.ofMillis(42));
    }

    @SuppressWarnings("unchecked")
    private List<TestResult> stored() {
        ArgumentCaptor<List<TestResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(results).store(eq(SUBMISSION), eq(ASSIGNMENT), anyList(), saved.capture());
        return saved.getValue();
    }

    @Test
    void everyTestRunsInRunOrderWithTheSubmissionsCodeAndTheAssignmentsLimits() {
        tests(
                test(11, "a", "a", TestVisibility.PUBLIC, 1),
                test(12, "b", "b", TestVisibility.HIDDEN, 2),
                test(13, "c", "c", TestVisibility.PUBLIC, 3));
        answer = SubmissionGraderTests::ok;

        grader.process(SUBMISSION);

        assertThat(requests).extracting(SandboxRequest::stdin).containsExactly("a", "b", "c");
        for (SandboxRequest request : requests) {
            assertThat(request.language()).isEqualTo("PYTHON");
            assertThat(request.sourceFiles()).containsExactly(new SourceFile("main.py", "print(input())"));
            assertThat(request.limits()).isEqualTo(new ExecutionLimits(1500, 128));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTestsAsRunAreHandedOnForScoring() {
        TestCase a = test(11, "a", "a", TestVisibility.PUBLIC, 1);
        TestCase b = test(12, "b", "b", TestVisibility.HIDDEN, 2);
        tests(a, b);
        answer = SubmissionGraderTests::ok;

        grader.process(SUBMISSION);

        ArgumentCaptor<List<TestCase>> scored = ArgumentCaptor.forClass(List.class);
        verify(results).store(eq(SUBMISSION), eq(ASSIGNMENT), scored.capture(), anyList());
        assertThat(scored.getValue()).containsExactly(a, b);
    }

    @Test
    void oneRowPerTestWithItsOutcomeOutputAndRuntimePublicAndHiddenAlike() {
        tests(
                test(11, "right", "right", TestVisibility.PUBLIC, 1),
                test(12, "wrong", "expected", TestVisibility.HIDDEN, 2),
                test(13, "crash", "x", TestVisibility.HIDDEN, 3));
        answer = Map.<String, SandboxResult>of(
                "right", ok("right\n"),
                "wrong", ok("printed\n"),
                "crash",
                        new SandboxResult(
                                SandboxStatus.RUNTIME_ERROR,
                                1,
                                "partial",
                                "Traceback",
                                false,
                                false,
                                Duration.ofMillis(1234)))::get;

        grader.process(SUBMISSION);

        List<TestResult> rows = stored();
        assertThat(rows).extracting(TestResult::getSubmissionId).containsOnly(SUBMISSION);
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(11L, 12L, 13L);
        assertThat(rows)
                .extracting(TestResult::getStatus)
                .containsExactly(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.RUNTIME_ERROR);
        assertThat(rows).extracting(TestResult::getActualOutput).containsExactly("right\n", "printed\n", "partial");
        assertThat(rows).extracting(TestResult::getStderr).containsExactly("", "", "Traceback");
        assertThat(rows).extracting(TestResult::getRuntimeMs).containsExactly(42, 42, 1234);
    }

    @Test
    void aNulInTheOutputIsStoredAsAReplacementCharacterAfterTheComparison() {
        tests(test(11, "nul", "a\u0000b", TestVisibility.PUBLIC, 1));
        answer = stdin -> new SandboxResult(SandboxStatus.OK, 0, "a\u0000b", "e\u0000", false, false, Duration.ZERO);

        grader.process(SUBMISSION);

        TestResult row = stored().get(0);
        assertThat(row.getStatus()).as("compared as printed").isEqualTo(TestOutcome.PASSED);
        assertThat(row.getActualOutput()).isEqualTo("a�b");
        assertThat(row.getStderr()).isEqualTo("e�");
    }

    @Test
    void aSandboxFailureIsThrownAndNothingIsStored() {
        tests(test(11, "a", "a", TestVisibility.PUBLIC, 1), test(12, "b", "b", TestVisibility.PUBLIC, 2));
        answer = stdin -> stdin.equals("a")
                ? ok("a")
                : new SandboxResult(SandboxStatus.SANDBOX_ERROR, null, "", "", false, false, Duration.ZERO);

        assertThatThrownBy(() -> grader.process(SUBMISSION)).isInstanceOf(SandboxFailedException.class);

        verify(results, never()).store(anyLong(), anyLong(), any(), any());
    }

    @Test
    void anAssignmentWithoutTestsRunsNothingAndStoresNothing() {
        tests();

        grader.process(SUBMISSION);

        assertThat(requests).isEmpty();
        verify(results, never()).store(anyLong(), anyLong(), any(), any());
    }

    @Test
    void aSubmissionThatIsGoneIsLeftAlone() {
        when(submissions.findById(SUBMISSION)).thenReturn(Optional.empty());

        grader.process(SUBMISSION);

        assertThat(requests).isEmpty();
        verify(results, never()).store(anyLong(), anyLong(), any(), any());
    }

    @Test
    void aLanguageNoLongerConfiguredIsAFailureNotAGrade() {
        Submission submission =
                new Submission(ASSIGNMENT, 5L, "COBOL", "DISPLAY 1.", 1, Instant.parse("2030-01-01T00:00:00Z"));
        when(submissions.findById(SUBMISSION)).thenReturn(Optional.of(submission));
        tests(test(11, "a", "a", TestVisibility.PUBLIC, 1));

        assertThatThrownBy(() -> grader.process(SUBMISSION)).isInstanceOf(IllegalStateException.class);

        assertThat(requests).isEmpty();
        verify(results, never()).store(anyLong(), anyLong(), any(), any());
    }

    private static SandboxResult compileError(String stdout, String stderr) {
        return new SandboxResult(SandboxStatus.COMPILE_ERROR, 1, stdout, stderr, false, false, Duration.ofSeconds(3));
    }

    @Test
    void aCompileErrorIsStoredOnEveryTestWithTheCompilersOutputAndNoProgramOutputOrRuntime() {
        tests(test(11, "a", "a", TestVisibility.PUBLIC, 1), test(12, "b", "b", TestVisibility.HIDDEN, 2));
        answer = stdin -> compileError("", "Main.java:1: error: ';' expected\n");

        grader.process(SUBMISSION); // returns: graded, not an ERROR

        List<TestResult> rows = stored();
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(11L, 12L);
        assertThat(rows).extracting(TestResult::getStatus).containsOnly(TestOutcome.COMPILE_ERROR);
        assertThat(rows).extracting(TestResult::getActualOutput).containsOnlyNulls();
        assertThat(rows).extracting(TestResult::getRuntimeMs).containsOnlyNulls();
        assertThat(rows).extracting(TestResult::getStderr).containsOnly("Main.java:1: error: ';' expected\n");
    }

    @Test
    void aCompileErrorOnAnyTestShortCircuitsEveryTest() {
        tests(
                test(11, "a", "a", TestVisibility.PUBLIC, 1),
                test(12, "b", "b", TestVisibility.HIDDEN, 2),
                test(13, "c", "c", TestVisibility.PUBLIC, 3));
        // A runner that builds per input (SandboxRunner's default runAll) could disagree with itself.
        answer = stdin -> stdin.equals("b") ? compileError("", "broken\n") : ok(stdin);

        grader.process(SUBMISSION);

        List<TestResult> rows = stored();
        assertThat(rows).extracting(TestResult::getTestCaseId).containsExactly(11L, 12L, 13L);
        assertThat(rows).extracting(TestResult::getStatus).containsOnly(TestOutcome.COMPILE_ERROR);
        assertThat(rows).extracting(TestResult::getStderr).containsOnly("broken\n");
        assertThat(rows).extracting(TestResult::getActualOutput).containsOnlyNulls();
    }

    @Test
    void theCompilersOutputIsBothOfItsStreamsInOrder() {
        assertThat(SubmissionGrader.compilerOutput(compileError("", "err\n"))).isEqualTo("err\n");
        assertThat(SubmissionGrader.compilerOutput(compileError("out\n", ""))).isEqualTo("out\n");
        assertThat(SubmissionGrader.compilerOutput(compileError("out\n", "err\n")))
                .isEqualTo("out\nerr\n");
        assertThat(SubmissionGrader.compilerOutput(compileError("out", "err"))).isEqualTo("out\nerr");
        assertThat(SubmissionGrader.compilerOutput(compileError("", ""))).isEmpty();
    }

    @Test
    void aCompilerThatReportsOnStdoutIsStoredToo() {
        tests(test(11, "a", "a", TestVisibility.PUBLIC, 1));
        answer = stdin -> compileError("1 error found\n", "file.x:3: bad token\n");

        grader.process(SUBMISSION);

        assertThat(stored().get(0).getStderr()).isEqualTo("1 error found\nfile.x:3: bad token\n");
    }

    @Test
    void aNulInTheCompilersOutputIsStorableToo() {
        tests(test(11, "a", "a", TestVisibility.PUBLIC, 1));
        answer = stdin -> compileError("", "bad\u0000char");

        grader.process(SUBMISSION);

        assertThat(stored().get(0).getStderr()).isEqualTo("bad�char");
    }
}
