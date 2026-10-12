package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.3d: whatever happens to a run, its container and its working directory are gone afterwards.
 * Success, a failing program and a timeout are also checked in {@code DockerCliSandboxRunnerTests} and
 * {@code SandboxTimeoutTests}; this class counts leftovers across all of them together and adds the
 * cases they do not cover: a run whose thread is interrupted (shutdown, cancellation).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxCleanupTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(2_000, 128);

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

    private SandboxResult python(String code, ExecutionLimits limits) {
        return runner.run(new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", code)), "", limits));
    }

    private static Set<String> sandboxContainers() {
        DockerCli.Output out = docker(
                Duration.ofSeconds(60), "ps", "--all", "--quiet", "--filter", "label=" + DockerRunArguments.LABEL);
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    private void assertNothingLeft(Set<String> containersBefore) throws IOException {
        assertThat(sandboxContainers()).as("sandbox containers").isEqualTo(containersBefore);
        try (Stream<Path> entries = Files.list(workRoot)) {
            assertThat(entries.toList()).as("working directories").isEmpty();
        }
    }

    @Test
    void successFailureAndTimeoutTogetherLeaveNothing() throws IOException {
        Set<String> before = sandboxContainers();

        assertThat(python("print(1)\n", LIMITS).status()).isEqualTo(SandboxStatus.OK);
        assertThat(python("raise SystemExit(4)\n", LIMITS).status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(python("while True:\n    pass\n", new ExecutionLimits(500, 128))
                        .status())
                .isEqualTo(SandboxStatus.TIMEOUT);

        assertNothingLeft(before);
    }

    @Test
    void aRunWhoseThreadIsInterruptedStillRemovesItsContainerAndDirectory() throws Exception {
        Set<String> before = sandboxContainers();
        AtomicReference<SandboxResult> result = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(python("import time\ntime.sleep(3600)\n", new ExecutionLimits(60_000, 128)));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        waitForARunningSandboxContainer(before);

        worker.interrupt();
        worker.join(Duration.ofSeconds(60).toMillis());

        assertThat(worker.isAlive()).as("the run ended").isFalse();
        assertThat(result.get().status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
        assertThat(interruptKept.get()).as("the interruption is not swallowed").isTrue();
        assertNothingLeft(before);
    }

    private static void waitForARunningSandboxContainer(Set<String> before) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (sandboxContainers().equals(before)) {
            assertThat(System.nanoTime()).as("a container appeared").isLessThan(giveUp);
            Thread.sleep(200);
        }
        // Let the program start sleeping, so the interruption lands in the wait for the client.
        Thread.sleep(500);
    }
}
