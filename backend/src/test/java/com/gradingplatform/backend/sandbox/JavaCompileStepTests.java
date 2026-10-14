package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;

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
 * 4.4b: a compiled language is built in its own container first and run in a read-only one after it.
 * Java is the language, from the real languages config; the compile-step cases that need a slow or
 * hostile compiler use a spec of their own over the same image.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JavaCompileStepTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(10_000, 128);

    private static final String SUM = """
            import java.util.Scanner;
            public class Main {
                public static void main(String[] args) {
                    Scanner in = new Scanner(System.in);
                    System.out.println(in.nextInt() + in.nextInt());
                }
            }
            """;

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void runnerWithAPrivateWorkRoot() {
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    private SandboxResult java(String code, String stdin) {
        return runner.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", code)), stdin, LIMITS));
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

    /** The Java entry of the real config with the compile command replaced. */
    private DockerCliSandboxRunner runnerWithCompileCommand(List<String> compile, ExecutionLimits compileLimits) {
        LanguageSpec real = languages.find("JAVA").orElseThrow();
        Map<String, LanguageSpec> map = new LinkedHashMap<>();
        map.put("JAVA", new LanguageSpec(real.image(), real.sourceFileName(), compile, real.runCommand()));
        return new DockerCliSandboxRunner(new SandboxProperties(map), workRoot, compileLimits);
    }

    // --- build, then run -------------------------------------------------------------------------

    @Test
    void aValidProgramIsCompiledAndRunAndItsOutputComesBack() {
        SandboxResult result = java(SUM, "3 4\n");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("7\n");
        assertThat(result.completedCleanly()).isTrue();
    }

    @Test
    void aProgramCanBeSplitOverSeveralFiles() {
        SandboxResult result = runner.run(new SandboxRequest(
                "JAVA",
                List.of(
                        new SourceFile(
                                "Main.java",
                                "public class Main { public static void main(String[] a) {"
                                        + " System.out.println(Helper.twice(21)); } }"),
                        new SourceFile("Helper.java", "class Helper { static int twice(int x) { return x * 2; } }")),
                "",
                LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("42\n");
    }

    @Test
    void theProgramRunsWithItsOwnSmallMemoryLimitWhileTheCompilerHadMore() {
        SandboxResult result = runner.run(new SandboxRequest(
                "JAVA", List.of(new SourceFile("Main.java", SUM)), "1 2\n", new ExecutionLimits(10_000, 64)));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("3\n");
    }

    @Test
    void theBuiltDirectoryIsMountedReadOnlyForTheRun() {
        SandboxResult result = java("""
                import java.io.File;
                import java.util.Arrays;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        String[] names = new File(".").list();
                        Arrays.sort(names);
                        System.out.println(String.join(",", names));
                        try {
                            new File("new.txt").createNewFile();
                            System.out.println("written");
                        } catch (java.io.IOException e) {
                            System.out.println("read-only");
                        }
                    }
                }
                """, "");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout().lines().toList()).containsExactly("Main.class,Main.java", "read-only");
    }

    // --- the program fails ------------------------------------------------------------------------

    @Test
    void anExceptionIsARuntimeErrorWithItsStackTraceOnStderr() {
        SandboxResult result = java("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("before");
                        throw new IllegalStateException("boom");
                    }
                }
                """, "");

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEqualTo("before\n");
        assertThat(result.stderr()).contains("IllegalStateException: boom");
    }

    @Test
    void aWrongAnswerIsStillAnOkRun() {
        SandboxResult result = java(SUM.replace("+", "*"), "3 4\n");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("12\n");
    }

    // --- the code does not build ------------------------------------------------------------------

    @Test
    void aSyntaxErrorIsACompileErrorWithTheCompilersOutputAndNothingIsRun() throws IOException {
        Set<String> before = sandboxContainers();

        SandboxResult result = java("public class Main { public static void main(String[] a) { int x = ; } }", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("Main.java:1:", "error:");
        assertThat(result.completedCleanly()).isFalse();
        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }

    @Test
    void aTypeErrorIsACompileErrorToo() {
        SandboxResult result =
                java("public class Main { public static void main(String[] a) { int x = \"text\"; } }", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.stderr()).contains("incompatible types");
    }

    @Test
    void theCompilerOutputNamesTheFileButNeverAPathOnTheHost() {
        SandboxResult result = java("class Main {", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.stderr()).doesNotContain(workRoot.toString()).doesNotContain("grading-sandbox");
    }

    @Test
    void aMissingMainFileIsACompileError() {
        SandboxResult result = runner.run(
                new SandboxRequest("JAVA", List.of(new SourceFile("Other.java", "class Other {}")), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.stderr()).contains("Main.java");
    }

    @Test
    void aClassWithoutTheRightNameBuildsButHasNoMainToRun() {
        SandboxResult result = java("class Other { public static void main(String[] a) {} }", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.stderr()).contains("Main");
    }

    // --- the compile container, as the platform's ---------------------------------------------------

    @Test
    void aCompilerThatRunsOutOfTimeIsACompileErrorWithAFixedMessage() {
        DockerCliSandboxRunner slow = runnerWithCompileCommand(List.of("sleep", "60"), new ExecutionLimits(500, 128));
        Set<String> before = sandboxContainers();

        SandboxResult result =
                slow.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", SUM)), "1 2\n", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.exitCode()).isNull();
        assertThat(result.stderr()).isEqualTo("Compilation took too long and was stopped.");
        assertThat(sandboxContainers()).isEqualTo(before);
    }

    @Test
    void theCompileContainerHasAReadOnlyRootFilesystem() {
        DockerCliSandboxRunner hostile =
                runnerWithCompileCommand(List.of("touch", "/etc/planted"), new ExecutionLimits(10_000, 128));

        SandboxResult result =
                hostile.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", SUM)), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.stderr()).contains("Read-only file system");
    }

    @Test
    void theCompileContainerHasNoNetwork() {
        DockerCliSandboxRunner hostile = runnerWithCompileCommand(
                List.of("bash", "-c", "exec 3<>/dev/tcp/1.1.1.1/53"), new ExecutionLimits(10_000, 128));

        SandboxResult result =
                hostile.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", SUM)), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void aCompilerKilledForMemoryIsACompileErrorNotAMemoryLimitOfTheProgram() {
        // `tail /dev/zero` buffers one endless line: it grows until the container's memory limit.
        DockerCliSandboxRunner hungry =
                runnerWithCompileCommand(List.of("tail", "/dev/zero"), new ExecutionLimits(20_000, 64));

        SandboxResult result =
                hungry.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", SUM)), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.COMPILE_ERROR);
        assertThat(result.exitCode()).isEqualTo(137);
    }

    @Test
    void aMissingImageIsASandboxErrorNotACompileError() throws IOException {
        LanguageSpec real = languages.find("JAVA").orElseThrow();
        Map<String, LanguageSpec> map = new LinkedHashMap<>();
        map.put(
                "JAVA",
                new LanguageSpec(
                        "grading-sandbox-does-not-exist:none",
                        real.sourceFileName(),
                        real.compileCommand(),
                        real.runCommand()));
        DockerCliSandboxRunner missing = new DockerCliSandboxRunner(new SandboxProperties(map), workRoot);
        Set<String> before = sandboxContainers();

        SandboxResult result =
                missing.run(new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", SUM)), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }

    @Test
    void noContainerAndNoDirectoryIsLeftAfterABuildAndARun() throws IOException {
        Set<String> before = sandboxContainers();

        java(SUM, "1 2\n");
        java("class Main {", "");

        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }
}
