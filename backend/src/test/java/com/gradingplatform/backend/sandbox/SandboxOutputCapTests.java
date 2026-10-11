package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.3c: what a program prints is cut at {@link DockerCliSandboxRunner#OUTPUT_LIMIT_BYTES} while it
 * runs, the cut is marked so it cannot be compared as if complete, and a program that prints without
 * end does not grow memory or disk before the timeout fires.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxOutputCapTests {

    private static final int CAP = DockerCliSandboxRunner.OUTPUT_LIMIT_BYTES;

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

    private SandboxResult python(String code, long timeLimitMs) {
        return runner.run(new SandboxRequest(
                "PYTHON", List.of(new SourceFile("main.py", code)), "", new ExecutionLimits(timeLimitMs, 128)));
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void outputUnderTheCapArrivesWholeAndIsNotMarked() {
        // 500 000 bytes: far more than a 64 KB cap would allow, well under ours.
        SandboxResult result = python("import sys\nsys.stdout.write('0123456789' * 50000)\n", 10_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("0123456789".repeat(50_000));
        assertThat(result.stdoutTruncated()).isFalse();
        assertThat(result.completedCleanly()).isTrue();
    }

    @Test
    void outputOfExactlyTheCapIsWholeAndOneByteMoreIsMarkedTruncated() {
        SandboxResult exact = python("import sys\nsys.stdout.write('a' * %d)\n".formatted(CAP), 10_000);
        SandboxResult over = python("import sys\nsys.stdout.write('a' * %d)\n".formatted(CAP + 1), 10_000);

        assertThat(exact.stdout()).hasSize(CAP);
        assertThat(exact.stdoutTruncated()).isFalse();
        assertThat(over.stdout()).hasSize(CAP);
        assertThat(over.stdoutTruncated()).isTrue();
    }

    @Test
    void aProgramThatPrintsFarMoreThanTheCapIsCutButStillExitsNormally() {
        SandboxResult result = python("import sys\nsys.stdout.write('0123456789' * 1000000)\n", 20_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.exitCode()).isZero();
        assertThat(bytes(result.stdout())).isEqualTo(CAP);
        assertThat(result.stdout()).isEqualTo("0123456789".repeat(CAP / 10) + "0123456789".substring(0, CAP % 10));
        assertThat(result.stdoutTruncated()).isTrue();
        // A cut output is never comparable, even though the program "succeeded".
        assertThat(result.completedCleanly()).isFalse();
    }

    @Test
    void aProgramThatPrintsWithoutEndIsStoppedByTheTimeoutWithBoundedOutput() {
        long started = System.nanoTime();

        SandboxResult result = python("while True:\n    print('x' * 100)\n", 1_500);

        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(bytes(result.stdout())).isEqualTo(CAP);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(1_500)
                        .plus(DockerCliSandboxRunner.START_GRACE)
                        .plus(Duration.ofSeconds(10)));
    }

    @Test
    void stderrIsCutOnItsOwnAndDoesNotMarkStdout() {
        SandboxResult result =
                python("import sys\nsys.stdout.write('fine')\nsys.stderr.write('e' * %d)\n".formatted(CAP * 3), 10_000);

        assertThat(result.stdout()).isEqualTo("fine");
        assertThat(result.stdoutTruncated()).isFalse();
        assertThat(bytes(result.stderr())).isEqualTo(CAP);
        assertThat(result.stderrTruncated()).isTrue();
        // stderr is not compared, so a cut stderr does not stop stdout being compared.
        assertThat(result.completedCleanly()).isTrue();
    }

    @Test
    void bothStreamsPrintingWithoutEndStayBounded() {
        SandboxResult result = python(
                "import sys\nwhile True:\n    sys.stdout.write('o' * 1000)\n    sys.stderr.write('e' * 1000)\n", 1_500);

        assertThat(result.status()).isEqualTo(SandboxStatus.TIMEOUT);
        assertThat(bytes(result.stdout())).isEqualTo(CAP);
        assertThat(bytes(result.stderr())).isEqualTo(CAP);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stderrTruncated()).isTrue();
    }

    @Test
    void theCutNeverSplitsACharacter() {
        // 'a' then two-byte characters: the cap falls inside one of them.
        SandboxResult result = python("import sys\nsys.stdout.write('a' + 'é' * 700000)\n", 20_000);

        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stdout()).doesNotContain("�");
        assertThat(bytes(result.stdout())).isLessThanOrEqualTo(CAP).isGreaterThanOrEqualTo(CAP - 3);
        assertThat(result.stdout()).startsWith("aé");
    }

    @Test
    void aFailingProgramWithHugeOutputIsStillARuntimeErrorWithBoundedText() {
        SandboxResult result =
                python("import sys\nsys.stdout.write('o' * %d)\nraise SystemExit(4)\n".formatted(CAP * 2), 10_000);

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(bytes(result.stdout())).isEqualTo(CAP);
    }
}
