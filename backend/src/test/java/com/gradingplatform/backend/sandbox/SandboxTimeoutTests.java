package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * 4.3b: a program that does not stop is stopped, the container with it, and the result says
 * {@code TIMEOUT}. The standard judge sanity checks (a busy loop, a sleep) plus what the timeout must
 * not do: cut short a program that is within its limit.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxTimeoutTests {

    /** A run may take its limit, the start-up allowance and a few seconds to kill and clean up. */
    private static final Duration KILL_ALLOWANCE = Duration.ofSeconds(8);

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void runner() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    private SandboxResult python(String code, String stdin, long timeLimitMs) {
        return runner.run(new SandboxRequest(
                "PYTHON", List.of(new SourceFile("main.py", code)), stdin, new ExecutionLimits(timeLimitMs, 128)));
    }

    private static Set<String> containers(boolean includeStopped) {
        List<String> args =
                new java.util.ArrayList<>(List.of("ps", "--quiet", "--filter", "label=" + DockerRunArguments.LABEL));
        if (includeStopped) {
            args.add("--all");
        }
        DockerCli.Output out = docker(Duration.ofSeconds(60), args.toArray(String[]::new));
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    private void assertTimedOutWithin(SandboxResult result, long timeLimitMs, Duration elapsed) {
        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(result.exitCode()).isNull();
        assertThat(result.completedCleanly()).isFalse();
        assertThat(result.runtime()).isGreaterThanOrEqualTo(Duration.ofMillis(timeLimitMs));
        assertThat(elapsed)
                .isLessThan(Duration.ofMillis(timeLimitMs)
                        .plus(DockerCliSandboxRunner.START_GRACE)
                        .plus(KILL_ALLOWANCE));
    }

    private void assertNothingLeft(Set<String> before) throws Exception {
        assertThat(containers(false)).as("running sandbox containers").isEqualTo(Set.of());
        assertThat(containers(true)).as("all sandbox containers").isEqualTo(before);
        try (Stream<Path> entries = Files.list(workRoot)) {
            assertThat(entries.toList()).as("working directories").isEmpty();
        }
    }

    @Test
    void anInfiniteLoopIsStoppedAndReportedAsATimeout() throws Exception {
        Set<String> before = containers(true);
        long started = System.nanoTime();

        SandboxResult result = python("while True:\n    pass\n", "", 1_000);

        assertTimedOutWithin(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
        assertNothingLeft(before);
    }

    @Test
    void aProgramThatSleepsForeverIsStoppedAndReportedAsATimeout() throws Exception {
        Set<String> before = containers(true);
        long started = System.nanoTime();

        SandboxResult result = python("import time\ntime.sleep(3600)\n", "", 1_000);

        assertTimedOutWithin(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
        assertNothingLeft(before);
    }

    @Test
    void aProgramBlockedOnInputThatNeverComesIsStopped() throws Exception {
        // stdin is written and closed by the runner; a program waiting on something else still ends.
        Set<String> before = containers(true);
        long started = System.nanoTime();

        SandboxResult result = python("import threading\nthreading.Event().wait()\n", "ignored", 1_000);

        assertTimedOutWithin(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
        assertNothingLeft(before);
    }

    @Test
    void aTimeoutKeepsWhatWasPrintedBeforeIt() {
        SandboxResult result = python("print('started', flush=True)\nwhile True:\n    pass\n", "", 1_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(result.stdout()).isEqualTo("started\n");
    }

    @Test
    void aProgramThatDoesNotReadItsLargeInputAndLoopsIsStopped() throws Exception {
        Set<String> before = containers(true);
        long started = System.nanoTime();

        SandboxResult result = python("while True:\n    pass\n", "x".repeat(2_000_000), 1_000);

        assertTimedOutWithin(result, 1_000, Duration.ofNanos(System.nanoTime() - started));
        assertNothingLeft(before);
    }

    @Test
    void theNextRunAfterATimeoutIsUnaffected() {
        python("while True:\n    pass\n", "", 500);

        SandboxResult next = python("print(6 * 7)\n", "", 5_000);

        assertThat(next.status()).isEqualTo(SandboxStatus.OK);
        assertThat(next.stdout()).isEqualTo("42\n");
    }

    @Test
    void aProgramThatFinishesEarlyDoesNotWaitForTheLimit() {
        long started = System.nanoTime();

        SandboxResult result = python("print('quick')\n", "", 10_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void startingTheContainerIsNotChargedToTheProgram() {
        // A tiny limit: a trivial program still finishes, because the allowance covers the start-up.
        SandboxResult result = python("print('hi')\n", "", 100);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("hi\n");
    }

    @Test
    void aProgramThatRunsForLessThanItsLimitButSeveralSecondsIsNotCutShort() {
        SandboxResult result = python("import time\ntime.sleep(2)\nprint('late')\n", "", 6_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("late\n");
        assertThat(result.runtime()).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }
}
