package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.6a in real containers: one submission, several tests, the build done once and every test run in a
 * container of its own. {@code SubmissionTestRunnerTests} has the verdict table without Docker.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubmissionRunAllTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(3_000, 128);

    /** Prints the sum of the two numbers on stdin; with "loop" as input it never ends. */
    private static final String SUM_OR_LOOP = """
            import sys
            line = sys.stdin.read().strip()
            if line == 'loop':
                while True:
                    pass
            if line == 'crash':
                raise ValueError('boom')
            a, b = map(int, line.split())
            print(a + b)
            """;

    @Autowired
    SandboxProperties languages;

    @Autowired
    SubmissionTestRunner suite;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void imagesAndAPrivateWorkRoot() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    private static List<SourceFile> python(String code) {
        return List.of(new SourceFile("main.py", code));
    }

    private static List<SourceFile> java(String code) {
        return List.of(new SourceFile("Main.java", code));
    }

    private static Set<String> sandboxContainers() {
        DockerCli.Output out = docker(
                Duration.ofSeconds(60), "ps", "--all", "--quiet", "--filter", "label=" + DockerRunArguments.LABEL);
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    private static List<String> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }

    // --- the suite, through the Spring bean --------------------------------------------------------

    @Test
    void everyTestGetsItsOwnVerdictInOrder() {
        List<TestRunResult> results = suite.runAll(
                "PYTHON",
                python(SUM_OR_LOOP),
                LIMITS,
                List.of(
                        new TestCaseInput(10, "3 4\n", "7\n"),
                        new TestCaseInput(11, "3 4\n", "8\n"),
                        new TestCaseInput(12, "loop\n", "never\n"),
                        new TestCaseInput(13, "crash\n", "7\n"),
                        new TestCaseInput(14, "20 22\n", "42")));

        assertThat(results).extracting(TestRunResult::testCaseId).containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(results)
                .extracting(TestRunResult::outcome)
                .containsExactly(
                        TestOutcome.PASSED,
                        TestOutcome.FAILED,
                        TestOutcome.TIMEOUT,
                        TestOutcome.RUNTIME_ERROR,
                        TestOutcome.PASSED);
        assertThat(results.get(0).run().stdout()).isEqualTo("7\n");
        assertThat(results.get(1).run().stdout()).isEqualTo("7\n");
        assertThat(results.get(3).run().stderr()).contains("ValueError: boom");
    }

    @Test
    void aTimedOutTestDoesNotStopTheOnesAfterIt() {
        List<TestRunResult> results = suite.runAll(
                "PYTHON",
                python(SUM_OR_LOOP),
                new ExecutionLimits(500, 64),
                List.of(new TestCaseInput(1, "loop\n", ""), new TestCaseInput(2, "1 2\n", "3\n")));

        assertThat(results).extracting(TestRunResult::outcome).containsExactly(TestOutcome.TIMEOUT, TestOutcome.PASSED);
    }

    @Test
    void javaIsCompiledAndEveryTestIsRun() {
        String program = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        System.out.println(in.nextInt() * 2);
                    }
                }
                """;

        List<TestRunResult> results = suite.runAll(
                "JAVA",
                java(program),
                new ExecutionLimits(10_000, 128),
                List.of(
                        new TestCaseInput(1, "4\n", "8\n"),
                        new TestCaseInput(2, "5\n", "11\n"),
                        new TestCaseInput(3, "0\n", "0\n")));

        assertThat(results)
                .extracting(TestRunResult::outcome)
                .containsExactly(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.PASSED);
    }

    @Test
    void aCompileErrorIsTheOutcomeOfEveryTestAndCarriesTheCompilersMessage() {
        List<TestRunResult> results = suite.runAll(
                "JAVA",
                java("class Main {"),
                new ExecutionLimits(10_000, 128),
                List.of(new TestCaseInput(1, "", ""), new TestCaseInput(2, "", "x"), new TestCaseInput(3, "", "")));

        assertThat(results).extracting(TestRunResult::outcome).containsOnly(TestOutcome.COMPILE_ERROR);
        assertThat(results)
                .extracting(r -> r.run().stderr())
                .allSatisfy(text -> assertThat(text).contains("Main.java:1:"));
        assertThat(results).extracting(TestRunResult::testCaseId).containsExactly(1L, 2L, 3L);
    }

    // --- built once, run separately ----------------------------------------------------------------

    @Test
    void theBuildIsDoneOnceAndEveryTestRunsTheSameClassFile() {
        // Main.class is written by the one compile; each test prints when that file was last modified. A
        // compile per test would give each its own time, a second or more apart.
        String program = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(new java.io.File("Main.class").lastModified());
                    }
                }
                """;

        List<SandboxResult> runs = runner.runAll(new SandboxBatchRequest(
                "JAVA", java(program), List.of("", "", "", ""), new ExecutionLimits(10_000, 128)));

        assertThat(runs).extracting(SandboxResult::status).containsOnly(SandboxStatus.OK);
        assertThat(runs)
                .extracting(r -> r.stdout().strip())
                .doesNotContain("0")
                .containsOnly(runs.get(0).stdout().strip());
    }

    @Test
    void theRunsShareNothingEveryTestStartsFromAFreshContainer() {
        String program = """
                import os
                marker = '/tmp/left-behind'
                print('seen' if os.path.exists(marker) else 'fresh')
                open(marker, 'w').write('x')
                """;

        List<SandboxResult> runs =
                runner.runAll(new SandboxBatchRequest("PYTHON", python(program), List.of("", "", ""), LIMITS));

        assertThat(runs).extracting(r -> r.stdout().strip()).containsExactly("fresh", "fresh", "fresh");
    }

    @Test
    void theBuiltDirectoryIsReadOnlyForEveryRunSoOneTestCannotChangeWhatTheNextOneRuns() {
        String program = """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        try {
                            new java.io.File("Planted.class").createNewFile();
                            System.out.println("written");
                        } catch (java.io.IOException e) {
                            System.out.println("read-only");
                        }
                    }
                }
                """;

        List<SandboxResult> runs = runner.runAll(
                new SandboxBatchRequest("JAVA", java(program), List.of("", ""), new ExecutionLimits(10_000, 128)));

        assertThat(runs).extracting(r -> r.stdout().strip()).containsExactly("read-only", "read-only");
    }

    @Test
    void eachTestGetsItsOwnInputAndTheLimitsOfTheAssignment() {
        List<SandboxResult> runs = runner.runAll(new SandboxBatchRequest(
                "PYTHON",
                python("import sys\nprint(sys.stdin.read().upper(), end='')\n"),
                List.of("abc", "", "x y\nz"),
                LIMITS));

        assertThat(runs).extracting(SandboxResult::stdout).containsExactly("ABC", "", "X Y\nZ");
    }

    // --- the sandbox failing -----------------------------------------------------------------------

    @Test
    void aSandboxFailureIsNeverAVerdictAndTheRemainingInputsAreNotTried() throws IOException {
        Map<String, LanguageSpec> map = new LinkedHashMap<>();
        map.put(
                "PYTHON",
                new LanguageSpec(
                        "grading-sandbox-does-not-exist:none", "main.py", List.of(), List.of("python", "main.py")));
        DockerCliSandboxRunner missing = new DockerCliSandboxRunner(new SandboxProperties(map), workRoot);
        Set<String> before = sandboxContainers();

        List<SandboxResult> runs =
                missing.runAll(new SandboxBatchRequest("PYTHON", python("print(1)"), List.of("a", "b", "c"), LIMITS));

        assertThat(runs).extracting(SandboxResult::status).containsOnly(SandboxStatus.SANDBOX_ERROR);
        assertThat(runs.get(2).stderr()).startsWith("not run");
        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();

        assertThatThrownBy(() -> new SubmissionTestRunner(missing)
                        .runAll("PYTHON", python("print(1)"), LIMITS, List.of(new TestCaseInput(1, "", "1\n"))))
                .isInstanceOf(SandboxFailedException.class);
    }

    @Test
    void anUnconfiguredLanguageIsASandboxFailureForEveryInput() {
        List<SandboxResult> runs = runner.runAll(
                new SandboxBatchRequest("COBOL", List.of(new SourceFile("main.cob", "")), List.of("a", "b"), LIMITS));

        assertThat(runs).extracting(SandboxResult::status).containsOnly(SandboxStatus.SANDBOX_ERROR);
    }

    // --- housekeeping ------------------------------------------------------------------------------

    @Test
    void noContainerAndNoDirectoryIsLeftAfterABatchOfSuccessesFailuresAndATimeout() throws IOException {
        Set<String> before = sandboxContainers();

        runner.runAll(new SandboxBatchRequest(
                "PYTHON", python(SUM_OR_LOOP), List.of("1 2\n", "crash\n", "loop\n"), new ExecutionLimits(500, 64)));

        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }

    @Test
    void singleRunsStillWorkThroughRun() {
        SandboxResult result = runner.run(new SandboxRequest("PYTHON", python("print(5)"), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("5\n");
    }
}
