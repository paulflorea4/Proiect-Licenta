package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.3e: what a killed backend leaves behind (a labelled container, running or not, and a {@code run-*}
 * directory) is removed at startup, and nothing else is touched. The "crash" is played by creating the
 * leftovers by hand with the runner's own arguments, as a dead process would have left them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxStartupSweepTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(5_000, 128);

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    @TempDir
    Path mountSource;

    DockerCliSandboxRunner runner;
    String image;
    final List<String> namesToRemove = new ArrayList<>();

    @BeforeEach
    void runner() {
        image = SandboxTestImages.ensureBuilt(languages, "PYTHON");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    @AfterEach
    void removeWhatTheTestCreated() {
        for (String name : namesToRemove) {
            docker(Duration.ofSeconds(60), "rm", "--force", name);
        }
    }

    private static Set<String> names(String... filter) {
        List<String> args = new ArrayList<>(List.of("ps", "--all", "--format", "{{.Names}}"));
        for (String f : filter) {
            args.addAll(List.of("--filter", f));
        }
        DockerCli.Output out = docker(Duration.ofSeconds(60), args.toArray(String[]::new));
        assertThat(out.exitCode()).as(out.stderr()).isZero();
        return out.stdout().lines().filter(line -> !line.isBlank()).collect(Collectors.toSet());
    }

    /** A sandbox container as the runner makes one, left over: created, and started when {@code running}. */
    private String leftover(String name, boolean running) {
        List<String> create = new ArrayList<>(List.of("create"));
        create.addAll(DockerRunArguments.build(name, image, mountSource, false, LIMITS, List.of("sleep", "3600")));
        namesToRemove.add(name);
        assertThat(docker(Duration.ofSeconds(60), create.toArray(String[]::new)).exitCode())
                .isZero();
        if (running) {
            assertThat(docker(Duration.ofSeconds(60), "start", name).exitCode()).isZero();
        }
        return name;
    }

    private static List<String> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aRunningAndAStoppedSandboxContainerAreBothRemoved() {
        leftover("sandbox-orphan-running", true);
        leftover("sandbox-orphan-stopped", false);

        DockerCliSandboxRunner.SweepResult swept = runner.sweepOrphans();

        assertThat(swept.containers()).isEqualTo(2);
        assertThat(names("label=" + DockerRunArguments.LABEL)).isEmpty();
    }

    @Test
    void aContainerWithoutOurLabelIsLeftAlone() {
        String foreign = "foreign-container-4-3e";
        namesToRemove.add(foreign);
        assertThat(docker(Duration.ofSeconds(60), "create", "--name", foreign, image, "sleep", "3600")
                        .exitCode())
                .isZero();
        leftover("sandbox-orphan-labelled", false);

        runner.sweepOrphans();

        assertThat(names("name=" + foreign)).containsExactly(foreign);
        assertThat(names("name=sandbox-orphan-labelled")).isEmpty();
    }

    @Test
    void runDirectoriesWithTheirContentsAreDeletedAndNothingElseInTheWorkRootIs() throws IOException {
        Path orphan = Files.createDirectory(workRoot.resolve("run-123456"));
        Files.writeString(orphan.resolve("main.py"), "print(1)");
        Files.createDirectories(orphan.resolve("pkg/inner"));
        Files.writeString(orphan.resolve("pkg/inner/Main.class"), "x");
        Files.createDirectory(workRoot.resolve("run-789"));
        Files.writeString(workRoot.resolve("notes.txt"), "keep");
        Files.createDirectory(workRoot.resolve("other"));
        Files.writeString(workRoot.resolve("run-file-not-a-directory"), "keep");

        DockerCliSandboxRunner.SweepResult swept = runner.sweepOrphans();

        assertThat(swept.directories()).isEqualTo(2);
        assertThat(entriesOf(workRoot)).containsExactly("notes.txt", "other", "run-file-not-a-directory");
    }

    @Test
    void aSymbolicLinkNamedLikeARunDirectoryIsNotFollowed() throws IOException {
        Path outside = Files.createDirectory(mountSource.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "keep");
        try {
            Files.createSymbolicLink(workRoot.resolve("run-link"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here: " + e);
        }

        runner.sweepOrphans();

        assertThat(outside.resolve("precious.txt")).hasContent("keep");
    }

    @Test
    void nothingToSweepIsAQuietNoOpAndASecondSweepFindsNothing() {
        leftover("sandbox-orphan-once", false);

        assertThat(runner.sweepOrphans().containers()).isEqualTo(1);
        assertThat(runner.sweepOrphans()).isEqualTo(new DockerCliSandboxRunner.SweepResult(0, 0));
    }

    @Test
    void aRunnerWorksNormallyAfterTheSweep() {
        leftover("sandbox-orphan-before-run", false);
        runner.sweepOrphans();

        SandboxResult result = runner.run(
                new SandboxRequest("PYTHON", List.of(new SourceFile("main.py", "print(6 * 7)\n")), "", LIMITS));

        assertThat(result.status()).isEqualTo(SandboxStatus.OK);
        assertThat(result.stdout()).isEqualTo("42\n");
    }

    // --- the startup hook ------------------------------------------------------------------------

    @Test
    void theHookCallsTheSweepOnceWhenTheSingletonsAreReady() {
        DockerCliSandboxRunner mocked = mock(DockerCliSandboxRunner.class);

        new SandboxStartupSweep(mocked).afterSingletonsInstantiated();

        verify(mocked).sweepOrphans();
    }

    @Test
    void aFailingSweepNeverStopsTheApplicationFromStarting() {
        DockerCliSandboxRunner mocked = mock(DockerCliSandboxRunner.class);
        when(mocked.sweepOrphans()).thenThrow(new IllegalStateException("docker is gone"));

        assertThatCode(() -> new SandboxStartupSweep(mocked).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }
}
