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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.5a: how a run that ended is told apart in a real container: normal, non-zero, killed for memory,
 * killed by something else with the same exit code 137, and our own timeout kill. The decision table
 * itself is in {@link ExitClassificationTests}; the full hostile-program suite is 4.7a.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxExitStatusTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(10_000, 64);

    /** Grows without end in 10 MB steps: the container's memory limit is reached long before the timeout. */
    private static final String BOMB = "x = []\nwhile True:\n    x.append(bytearray(10_000_000))\n";

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void runnerWithAPrivateWorkRoot() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        SandboxTestImages.ensureBuilt(languages, "JAVA");
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

    private static List<String> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }

    /** What a failed status assertion shows: the result (lengths only) and the program's own stderr. */
    private static String describe(SandboxResult result) {
        return result + " stderr=<" + result.stderr().strip() + ">";
    }

    // --- memory ----------------------------------------------------------------------------------

    @Test
    void aProgramThatOutgrowsItsMemoryIsKilledAndReportedAsMemoryLimit() {
        SandboxResult result = python(BOMB, LIMITS);

        assertThat(result.status()).as(describe(result)).isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.exitCode()).isEqualTo(137);
        assertThat(result.completedCleanly()).isFalse();
    }

    @Test
    void theMemoryLimitIsReachedFarSoonerThanTheTimeLimit() {
        SandboxResult result = python(BOMB, new ExecutionLimits(30_000, 64));

        assertThat(result.status()).as(describe(result)).isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.runtime()).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void outputPrintedBeforeTheKillIsKept() {
        SandboxResult result = python("print('started', flush=True)\n" + BOMB, LIMITS);

        assertThat(result.status()).as(describe(result)).isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.stdout()).isEqualTo("started\n");
    }

    @Test
    void aProgramThatStaysInsideItsMemoryIsNotTouched() {
        SandboxResult result = python("x = bytearray(20_000_000)\nprint(len(x))\n", LIMITS);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("20000000\n");
    }

    // --- 137 that is not memory ------------------------------------------------------------------

    @Test
    void anExplicitExit137WithoutTheMemoryKillerIsAnOrdinaryRuntimeError() {
        SandboxResult result = python("import sys\nsys.exit(137)\n", LIMITS);

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(137);
    }

    @Test
    void aChildKilledByAnotherSignalIsNotAMemoryKillAndTheParentFinishesNormally() {
        SandboxResult result = python("""
                import subprocess
                print(subprocess.run(['sh', '-c', 'kill -9 $$']).returncode)
                """, LIMITS);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("-9\n");
    }

    @Test
    void aHelperKilledForMemoryWhileTheMainProgramExitsNormallyIsStillOk() {
        SandboxResult result = python("""
                import subprocess, sys
                subprocess.run([sys.executable, '-c', "x = []\\nwhile True: x.append(bytearray(10_000_000))"])
                print('main done')
                """, LIMITS);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("main done\n");
    }

    @Test
    void aHelperKilledForMemoryAndAMainProgramThatThenFailsIsAMemoryLimit() {
        SandboxResult result = python("""
                import subprocess, sys
                subprocess.run([sys.executable, '-c', "x = []\\nwhile True: x.append(bytearray(10_000_000))"])
                sys.exit(1)
                """, LIMITS);

        assertThat(result.status()).as(describe(result)).isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    // --- the other endings are unchanged ---------------------------------------------------------

    @Test
    void ourOwnTimeoutKillIsATimeoutNotAMemoryLimit() {
        SandboxResult result = python("while True:\n    pass\n", new ExecutionLimits(1_000, 64));

        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void aNormalFailureIsStillARuntimeError() {
        SandboxResult result = python("raise ValueError('boom')\n", LIMITS);

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    // --- Java ------------------------------------------------------------------------------------

    @Test
    void aJavaHeapBombIsAnOutOfMemoryErrorOfTheJvmAndSoARuntimeErrorNotAKernelKill() {
        // Pinned, not endorsed: the JVM sizes its heap at a quarter of the container's memory and throws
        // OutOfMemoryError before the kernel gets involved, so the flag is never set. See decisions-phase-04, 4.5a.
        SandboxResult result = runner.run(new SandboxRequest(
                "JAVA", List.of(new SourceFile("Main.java", """
                                import java.util.ArrayList;
                                import java.util.List;
                                public class Main {
                                    public static void main(String[] args) {
                                        List<long[]> hog = new ArrayList<>();
                                        while (true) {
                                            hog.add(new long[1_000_000]);
                                        }
                                    }
                                }
                                """)), "", new ExecutionLimits(10_000, 128)));

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("OutOfMemoryError");
    }

    // --- housekeeping ----------------------------------------------------------------------------

    @Test
    void noContainerAndNoDirectoryIsLeftAfterAMemoryKill() throws IOException {
        Set<String> before = sandboxContainers();

        python(BOMB, LIMITS);

        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }
}
