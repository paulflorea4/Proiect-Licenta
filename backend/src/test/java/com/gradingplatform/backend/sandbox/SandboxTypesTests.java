package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 4.1a: the request and result types hold their rules, so every runner can rely on them. */
class SandboxTypesTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(2_000, 256);

    private static SandboxResult result(SandboxStatus status, Integer exitCode) {
        return new SandboxResult(status, exitCode, "", "", false, false, Duration.ZERO);
    }

    @Test
    void aValidRequestKeepsEverythingItWasGiven() {
        List<SourceFile> files = List.of(new SourceFile("Main.java", "class Main {}"));

        SandboxRequest request = new SandboxRequest("JAVA", files, "1 2\n", LIMITS);

        assertThat(request.language()).isEqualTo("JAVA");
        assertThat(request.sourceFiles()).isEqualTo(files);
        assertThat(request.stdin()).isEqualTo("1 2\n");
        assertThat(request.limits()).isEqualTo(LIMITS);
    }

    @Test
    void emptyStdinIsAllowedButNotMissing() {
        List<SourceFile> files = List.of(new SourceFile("main.py", "print(1)"));

        assertThatCode(() -> new SandboxRequest("PYTHON", files, "", LIMITS)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SandboxRequest("PYTHON", files, null, LIMITS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFileListCannotBeChangedAfterwards() {
        List<SourceFile> files = new ArrayList<>(List.of(new SourceFile("main.py", "print(1)")));
        SandboxRequest request = new SandboxRequest("PYTHON", files, "", LIMITS);

        files.add(new SourceFile("extra.py", "x"));

        assertThat(request.sourceFiles()).hasSize(1);
        assertThatThrownBy(() -> request.sourceFiles().add(new SourceFile("y.py", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRequestNeedsAtLeastOneFileAndNoNulls() {
        assertThatThrownBy(() -> new SandboxRequest("PYTHON", List.of(), "", LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
        List<SourceFile> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new SandboxRequest("PYTHON", withNull, "", LIMITS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SandboxRequest("PYTHON", null, "", LIMITS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SandboxRequest("PYTHON", List.of(new SourceFile("a.py", "")), "", null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankLanguageIsRefused(String language) {
        List<SourceFile> files = List.of(new SourceFile("a.py", ""));

        assertThatThrownBy(() -> new SandboxRequest(language, files, "", LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoFilesCannotShareANameEvenInAnotherCase() {
        List<SourceFile> same = List.of(new SourceFile("Main.java", "a"), new SourceFile("Main.java", "b"));
        List<SourceFile> otherCase = List.of(new SourceFile("Main.java", "a"), new SourceFile("main.JAVA", "b"));

        assertThatThrownBy(() -> new SandboxRequest("JAVA", same, "", LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxRequest("JAVA", otherCase, "", LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                ".",
                "..",
                "../Main.java",
                "dir/Main.java",
                "dir\\Main.java",
                "/etc/passwd",
                "C:\\x.java",
                "c:x",
                "a\u0000b",
                "a\nb",
                "name.",
                "name ",
                " name"
            })
    void aFileNameThatIsNotPlainIsRefused(String name) {
        assertThatThrownBy(() -> new SourceFile(name, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Main.java", "main.py", "solution_2.py", "Résumé.txt", ".hidden", "a b.txt"})
    void ordinaryFileNamesAreAccepted(String name) {
        assertThatCode(() -> new SourceFile(name, "x")).doesNotThrowAnyException();
    }

    @Test
    void aFileNameHasALengthCap() {
        assertThatCode(() -> new SourceFile("a".repeat(SourceFile.MAX_NAME_LENGTH), ""))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new SourceFile("a".repeat(SourceFile.MAX_NAME_LENGTH + 1), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new ExecutionLimits(0, 128)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(-1, 128)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(1_000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLimits(1_000, -5)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ExecutionLimits(1, 1)).doesNotThrowAnyException();
    }

    @Test
    void anOkResultMeansExitCodeZero() {
        assertThatCode(() -> result(SandboxStatus.OK, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> result(SandboxStatus.OK, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(SandboxStatus.OK, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherStatusesMayHaveAnyOrNoExitCode() {
        assertThat(result(SandboxStatus.RUNTIME_ERROR, 1).exitCode()).isEqualTo(1);
        assertThat(result(SandboxStatus.TIMEOUT, null).exitCode()).isNull();
        assertThat(result(SandboxStatus.COMPILE_ERROR, null).status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result(SandboxStatus.MEMORY_LIMIT, 137).exitCode()).isEqualTo(137);
        assertThat(result(SandboxStatus.SANDBOX_ERROR, null).status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
    }

    @Test
    void aResultNeedsItsTextAndATimeThatIsNotNegative() {
        assertThatThrownBy(() -> new SandboxResult(SandboxStatus.TIMEOUT, null, null, "", false, false, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SandboxResult(SandboxStatus.TIMEOUT, null, "", null, false, false, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SandboxResult(SandboxStatus.TIMEOUT, null, "", "", false, false, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new SandboxResult(SandboxStatus.TIMEOUT, null, "", "", false, false, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatedStdoutIsNeverComparable() {
        SandboxResult complete = new SandboxResult(SandboxStatus.OK, 0, "42\n", "", false, false, Duration.ZERO);
        SandboxResult cut = new SandboxResult(SandboxStatus.OK, 0, "42", "", true, false, Duration.ZERO);
        SandboxResult cutStderrOnly = new SandboxResult(SandboxStatus.OK, 0, "42\n", "w", false, true, Duration.ZERO);

        assertThat(complete.completedCleanly()).isTrue();
        assertThat(cut.completedCleanly()).isFalse();
        assertThat(cutStderrOnly.completedCleanly()).isTrue();
        assertThat(result(SandboxStatus.TIMEOUT, null).completedCleanly()).isFalse();
        assertThat(result(SandboxStatus.RUNTIME_ERROR, 1).completedCleanly()).isFalse();
    }

    @Test
    void toStringNeverShowsCodeInputOrOutput() {
        SandboxRequest request =
                new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", "SECRET-CODE")), "SECRET-INPUT", LIMITS);
        SandboxResult output =
                new SandboxResult(SandboxStatus.OK, 0, "SECRET-OUT", "SECRET-ERR", false, false, Duration.ofMillis(5));

        assertThat(request.toString()).doesNotContain("SECRET").contains("main.py", "PYTHON");
        assertThat(request.sourceFiles().get(0).toString()).doesNotContain("SECRET");
        assertThat(output.toString()).doesNotContain("SECRET").contains("OK");
    }

    @Test
    void anyRunnerCanBeSwappedInBehindTheInterface() {
        SandboxRunner fake = request -> new SandboxResult(
                SandboxStatus.OK, 0, request.stdin().toUpperCase(), "", false, false, Duration.ofMillis(1));

        SandboxResult answer =
                fake.run(new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", "")), "abc", LIMITS));

        assertThat(answer.stdout()).isEqualTo("ABC");
        assertThat(answer.completedCleanly()).isTrue();
    }
}
