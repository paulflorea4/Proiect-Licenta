package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.4a: the first end-to-end proof of the sandbox. A Python submission goes in through the
 * {@link SandboxRunner} interface only (the Spring bean, with the real work root and the language config),
 * runs in a real container, and its stdout is compared with the expected output of one test case. The
 * comparison here is a bare trailing-whitespace strip: the real, swappable comparison is 4.5b.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PythonEndToEndTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(5_000, 128);

    /** One test case as a teacher writes it: what goes to stdin, what must come out of stdout. */
    private record Case(String input, String expectedOutput) {}

    private static final Case SUM = new Case("3 4\n", "7\n");

    @Autowired
    SandboxProperties languages;

    @Autowired
    SandboxRunner runner;

    @BeforeEach
    void theImageExists() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
    }

    private SandboxResult run(String code, Case test) {
        String fileName = languages.find("PYTHON").orElseThrow().sourceFileName();
        return runner.run(new SandboxRequest("PYTHON", List.of(new SourceFile(fileName, code)), test.input(), LIMITS));
    }

    private static boolean passes(SandboxResult result, Case test) {
        return result.completedCleanly()
                && result.stdout().stripTrailing().equals(test.expectedOutput().stripTrailing());
    }

    @Test
    void aCorrectSubmissionPassesTheTestCase() {
        SandboxResult result = run("a, b = map(int, input().split())\nprint(a + b)\n", SUM);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("7\n");
        assertThat(passes(result, SUM)).isTrue();
    }

    @Test
    void aWrongSubmissionRunsCleanlyButFailsTheTestCase() {
        SandboxResult result = run("a, b = map(int, input().split())\nprint(a * b)\n", SUM);

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("12\n");
        assertThat(passes(result, SUM)).isFalse();
    }

    @Test
    void aCrashingSubmissionNeverPassesEvenWithTheRightOutputBeforeTheCrash() {
        SandboxResult result = run("print(7)\nraise SystemExit(1)\n", SUM);

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.stdout()).isEqualTo("7\n");
        assertThat(passes(result, SUM)).isFalse();
    }

    @Test
    void twoRunsOfTheSameSubmissionGiveTheSameAnswer() {
        String code = "a, b = map(int, input().split())\nprint(a + b)\n";

        assertThat(run(code, SUM).stdout()).isEqualTo(run(code, SUM).stdout());
    }
}
