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
 * 4.3a: {@link DockerCliSandboxRunner} runs a submission in a fresh container and reports what
 * happened. Python only here: the compile step (Java) is 4.4b. The restrictions are read back from a
 * container in {@code SandboxFlagsTests}; the few "what does it do to a program" checks below prove the
 * plumbing (mounts, tmpfs, user, network) works on this host, the hostile-program suite is 4.7a.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DockerCliSandboxRunnerTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(5_000, 128);

    @Autowired
    SandboxProperties languages;

    @Autowired
    SandboxRunner beanRunner;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void runnerWithAPrivateWorkRoot() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    private SandboxResult python(String code, String stdin) {
        return runner.run(new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", code)), stdin, LIMITS));
    }

    private static Set<String> sandboxContainers() {
        DockerCli.Output out = docker(
                Duration.ofSeconds(60), "ps", "--all", "--quiet", "--filter", "label=" + DockerRunArguments.LABEL);
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    // --- running ----------------------------------------------------------------------------

    @Test
    void theRunnerIsTheSpringBeanBehindTheInterface() {
        assertThat(beanRunner).isInstanceOf(DockerCliSandboxRunner.class);
    }

    @Test
    void aProgramReadsStdinAndItsOutputComesBack() {
        SandboxResult result = python("a, b = map(int, input().split())\nprint(a + b)\n", "3 4\n");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("7\n");
        assertThat(result.stderr()).isEmpty();
        assertThat(result.runtime()).isPositive();
        assertThat(result.completedCleanly()).isTrue();
    }

    @Test
    void emptyStdinAndUnicodeAreCarriedAsWritten() {
        SandboxResult result = python("import sys\nprint('ă€' + sys.stdin.read())\n", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("ă€\n");
    }

    @Test
    void aProgramCanBeSplitOverSeveralFiles() {
        SandboxResult result = runner.run(new SandboxRequest(
                "PYTHON",
                List.of(
                        new SourceFile("main.py", "import helper\nprint(helper.twice(21))\n"),
                        new SourceFile("helper.py", "def twice(x):\n    return x * 2\n")),
                "",
                LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("42\n");
    }

    @Test
    void aNonZeroExitIsARuntimeErrorWithTheExitCode() {
        SandboxResult result = python("import sys\nsys.exit(3)\n", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.completedCleanly()).isFalse();
    }

    @Test
    void anExceptionIsARuntimeErrorAndItsTracebackIsOnStderr() {
        SandboxResult result = python("print('before')\nraise ValueError('boom')\n", "");

        assertThat(result.status()).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEqualTo("before\n");
        assertThat(result.stderr()).contains("ValueError: boom");
    }

    @Test
    void aProgramThatNeverReadsItsInputIsStillFine() {
        SandboxResult result = python("print('done')\n", "x".repeat(200_000));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("done\n");
    }

    // --- the plumbing, as the program sees it -------------------------------------------------

    @Test
    void theProgramRunsAsTheUnprivilegedUserWithTheSubmissionAsItsWorkingDirectory() {
        SandboxResult result =
                python("import os\nprint(os.getuid(), os.getgid(), os.getcwd(), sorted(os.listdir('.')))\n", "");

        assertThat(result.stdout().strip()).isEqualTo("10001 10001 /work ['main.py']");
    }

    @Test
    void theSubmissionDirectoryIsReadOnlyButTmpIsWritable() {
        SandboxResult result = python("""
                import os
                def attempt(path):
                    try:
                        with open(path, 'w') as f:
                            f.write('x')
                        return 'written'
                    except OSError as e:
                        return e.strerror
                print(attempt('/work/new.txt'))
                print(attempt('/tmp/new.txt'))
                print(attempt('/etc/new.txt'))
                print(attempt('/new.txt'))
                """, "");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout().lines().toList())
                .containsExactly("Read-only file system", "written", "Read-only file system", "Read-only file system");
    }

    @Test
    void thereIsNoNetwork() {
        SandboxResult result = python("""
                import socket
                try:
                    socket.create_connection(('1.1.1.1', 53), timeout=3)
                    print('connected')
                except OSError as e:
                    print('blocked')
                """, "");

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout().strip()).isEqualTo("blocked");
    }

    @Test
    void theCompileContainerCanWriteTheDirectoryAsTheUnprivilegedUserAndTheHostSeesIt() throws IOException {
        LanguageSpec spec = languages.find("PYTHON").orElseThrow();
        Path directory = runner.prepare(List.of(new SourceFile("main.py", "print(1)\n")));
        try {
            SandboxResult result = runner.runInContainer(
                    spec, LIMITS, directory, true, List.of("sh", "-c", "echo built > out.txt"), "");

            assertThat(result.status()).isEqualTo(SandboxStatus.OK);
            assertThat(Files.readString(directory.resolve("out.txt")).strip()).isEqualTo("built");
        } finally {
            DockerCliSandboxRunner.deleteTree(directory);
        }
    }

    // --- not a program's fault ------------------------------------------------------------------

    @Test
    void anUnconfiguredLanguageIsASandboxErrorNotACrash() {
        SandboxResult result =
                runner.run(new SandboxRequest("COBOL", List.of(new SourceFile("main.cob", "")), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void aCompiledLanguageIsASandboxErrorUntilTheCompileStepExists() {
        SandboxResult result = runner.run(
                new SandboxRequest("JAVA", List.of(new SourceFile("Main.java", "class Main {}")), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
    }

    @Test
    void aMissingImageIsASandboxErrorAndNothingIsLeftBehind() throws IOException {
        Map<String, LanguageSpec> map = new LinkedHashMap<>();
        map.put(
                "PYTHON",
                new LanguageSpec(
                        "grading-sandbox-does-not-exist:none", "main.py", List.of(), List.of("python", "main.py")));
        DockerCliSandboxRunner missing = new DockerCliSandboxRunner(new SandboxProperties(map), workRoot);
        Set<String> before = sandboxContainers();

        SandboxResult result =
                missing.run(new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", "print(1)")), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.SANDBOX_ERROR);
        assertThat(result.stderr()).doesNotContain("does-not-exist"); // docker's text stays in the log
        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }

    // --- cleanup --------------------------------------------------------------------------------

    @Test
    void noContainerAndNoDirectoryIsLeftAfterASuccessfulOrAFailedRun() throws IOException {
        Set<String> before = sandboxContainers();

        python("print(1)\n", "");
        python("raise SystemExit(2)\n", "");

        assertThat(sandboxContainers()).isEqualTo(before);
        assertThat(entriesOf(workRoot)).isEmpty();
    }

    // --- the runner's own preconditions ---------------------------------------------------------

    @Test
    void aWorkRootWithACommaIsRefusedBecauseItWouldEndTheMountValue() {
        assertThatThrownBy(() -> new DockerCliSandboxRunner(languages, workRoot.resolve("a,b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onAPosixHostOnlyTheOwnerCanEnterTheWorkRootWhileARunDirectoryIsOpenToTheContainersUser() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                workRoot.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions only");
        Path root = workRoot.resolve("fresh-root");
        DockerCliSandboxRunner fresh = new DockerCliSandboxRunner(languages, root);

        Path directory = fresh.prepare(List.of(new SourceFile("main.py", "print(1)")));
        try {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root)))
                    .isEqualTo("rwx------");
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                    .isEqualTo("rwxrwxrwx");
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(directory.resolve("main.py"))))
                    .isEqualTo("rw-r--r--");
        } finally {
            DockerCliSandboxRunner.deleteTree(directory);
        }
    }

    @Test
    void theSourceFilesAreWrittenExactlyAndNothingElseIsInTheDirectory() throws IOException {
        Path directory = runner.prepare(List.of(new SourceFile("main.py", "a\r\nb\n"), new SourceFile("Data.txt", "")));
        try {
            assertThat(entriesOf(directory)).containsExactlyInAnyOrder("main.py", "Data.txt");
            assertThat(Files.readString(directory.resolve("main.py"))).isEqualTo("a\r\nb\n");
            assertThat(directory.getParent()).isEqualTo(workRoot.toAbsolutePath());
        } finally {
            DockerCliSandboxRunner.deleteTree(directory);
        }
    }

    private static List<String> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }
}
