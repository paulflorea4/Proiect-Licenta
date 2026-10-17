package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.repository.AssignmentRepository;
import com.gradingplatform.backend.repository.SubmissionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import com.gradingplatform.backend.sandbox.ExecutionLimits;
import com.gradingplatform.backend.sandbox.LanguageSpec;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import com.gradingplatform.backend.sandbox.SandboxResult;
import com.gradingplatform.backend.sandbox.SourceFile;
import com.gradingplatform.backend.sandbox.SubmissionTestRunner;
import com.gradingplatform.backend.sandbox.TestCaseInput;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.sandbox.TestRunResult;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The worker's {@link SubmissionProcessor} (5.3a): runs every test case of the submission's
 * assignment through the Phase 4 runner ({@link SubmissionTestRunner}) and stores one {@code
 * test_results} row per test. Public and hidden tests are run and stored identically; only what a
 * student is shown differs (5.5a).
 *
 * <p>Nothing here writes a status: returning means graded, throwing means {@code ERROR} (the worker
 * decides, 5.2b). The sandbox runs outside any transaction; afterwards {@link SubmissionResultWriter}
 * stores the results with the rubric scores and the grade (5.4b) in one transaction, so a submission
 * has its whole result or none of it. A sandbox failure ({@code
 * SandboxFailedException}) is thrown before anything is stored. A language that is no longer
 * configured is also a failure of the platform, not of the student. Code that does not compile is
 * stored as {@code COMPILE_ERROR} on every test and still graded (5.3b).
 */
@Component
public class SubmissionGrader implements SubmissionProcessor {

    private final SubmissionRepository submissions;
    private final AssignmentRepository assignments;
    private final TestCaseRepository testCases;
    private final SubmissionResultWriter results;
    private final SandboxProperties languages;
    private final SubmissionTestRunner runner;

    public SubmissionGrader(
            SubmissionRepository submissions,
            AssignmentRepository assignments,
            TestCaseRepository testCases,
            SubmissionResultWriter results,
            SandboxProperties languages,
            SubmissionTestRunner runner) {
        this.submissions = submissions;
        this.assignments = assignments;
        this.testCases = testCases;
        this.results = results;
        this.languages = languages;
        this.runner = runner;
    }

    @Override
    public void process(long submissionId) {
        Optional<Submission> found = submissions.findById(submissionId);
        if (found.isEmpty()) {
            return; // deleted since it was claimed: nothing to grade, and the worker finishes nothing
        }
        Submission submission = found.get();
        List<TestCase> tests = testCases.findByAssignmentIdOrderByPositionAscIdAsc(submission.getAssignmentId());
        if (tests.isEmpty()) {
            return; // nothing to run (a published assignment has tests; 3.4b)
        }
        Assignment assignment =
                assignments.findById(submission.getAssignmentId()).orElseThrow();
        LanguageSpec language = languages
                .find(submission.getLanguage())
                .orElseThrow(() -> new IllegalStateException("the submission's language is no longer configured"));

        List<TestRunResult> runs = runner.runAll(
                SandboxProperties.normalize(submission.getLanguage()),
                List.of(new SourceFile(language.sourceFileName(), submission.getSourceCode())),
                new ExecutionLimits(assignment.getTimeLimitMs(), assignment.getMemoryLimitMb()),
                tests.stream()
                        .map(test -> new TestCaseInput(test.getId(), test.getInput(), test.getExpectedOutput()))
                        .toList());

        Optional<SandboxResult> build = runs.stream()
                .filter(run -> run.outcome() == TestOutcome.COMPILE_ERROR)
                .map(TestRunResult::run)
                .findFirst();
        results.store(
                submissionId,
                submission.getAssignmentId(),
                tests,
                runs.stream()
                        .map(run -> build.isPresent()
                                ? compileErrorRow(submissionId, run.testCaseId(), build.get())
                                : toRow(submissionId, run))
                        .toList());
    }

    /**
     * A compile error short-circuits (5.3b): every test is {@code COMPILE_ERROR} with the same build,
     * whatever else the runner returned, so no test can pass on code that did not build. No program
     * ran, so there is no program output or runtime (NULL, as V8 says); the compiler's own output,
     * both of its streams, goes in {@code stderr} for the student to read. The submission is still
     * graded, not an {@code ERROR}: the code was graded, it just did not compile.
     */
    private static TestResult compileErrorRow(long submissionId, long testCaseId, SandboxResult build) {
        return new TestResult(
                submissionId, testCaseId, TestOutcome.COMPILE_ERROR, null, storable(compilerOutput(build)), null);
    }

    /** The compiler's stdout then its stderr, each only if it said something. */
    static String compilerOutput(SandboxResult build) {
        String out = build.stdout();
        String err = build.stderr();
        if (out.isEmpty() || err.isEmpty()) {
            return out + err;
        }
        return out + (out.endsWith("\n") ? "" : "\n") + err;
    }

    private static TestResult toRow(long submissionId, TestRunResult result) {
        return new TestResult(
                submissionId,
                result.testCaseId(),
                result.outcome(),
                storable(result.run().stdout()),
                storable(result.run().stderr()),
                (int) Math.min(result.run().runtime().toMillis(), Integer.MAX_VALUE));
    }

    /**
     * The text with every NUL replaced by U+FFFD: Postgres {@code TEXT} refuses U+0000, and a program
     * that prints one must not turn its grading into an {@code ERROR}. The comparison has already
     * been made on the output as printed.
     */
    static String storable(String text) {
        return text.replace('\u0000', '�');
    }
}
