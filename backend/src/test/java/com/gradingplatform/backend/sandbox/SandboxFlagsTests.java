package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.3a: every restriction is asserted on a real container, not only present in the code. A container
 * is created (not started) from the runner's own argument list and Docker is asked what it will
 * enforce ({@code docker inspect}), so a flag that Docker silently ignored or reinterpreted fails here.
 * What the restrictions do to a hostile program is 4.7a's.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxFlagsTests {

    private static final Duration DOCKER = Duration.ofSeconds(60);
    private static final long MIB = 1024L * 1024L;

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path directory;

    /** Creates a container from the runner's arguments, hands its inspect JSON to {@code check}, removes it. */
    private void inspected(boolean writable, java.util.function.Consumer<String> check) {
        String image = SandboxTestImages.ensureBuilt(languages, "PYTHON");
        String name = "sandbox-flags-" + UUID.randomUUID();
        List<String> create = new ArrayList<>(List.of("create"));
        create.addAll(DockerRunArguments.build(
                name, image, directory, writable, new ExecutionLimits(5_000, 192), List.of("python", "main.py")));
        try {
            DockerCli.Output created = docker(DOCKER, create.toArray(String[]::new));
            assertThat(created.exitCode()).as(created.stderr()).isZero();

            DockerCli.Output inspect = docker(DOCKER, "inspect", "--format", "{{json .}}", name);
            assertThat(inspect.exitCode()).as(inspect.stderr()).isZero();
            check.accept(inspect.stdout());
        } finally {
            docker(DOCKER, "rm", "--force", name);
        }
    }

    @Test
    void thereIsNoNetwork() {
        inspected(false, json -> {
            assertThat(JsonPath.<String>read(json, "$.HostConfig.NetworkMode")).isEqualTo("none");
            assertThat(JsonPath.<Map<String, Object>>read(json, "$.HostConfig.PortBindings"))
                    .isNullOrEmpty();
        });
    }

    @Test
    void memoryIsLimitedAndSwapIsTheSameSoThereIsNone() {
        inspected(false, json -> {
            assertThat(JsonPath.<Number>read(json, "$.HostConfig.Memory").longValue())
                    .isEqualTo(192 * MIB);
            assertThat(JsonPath.<Number>read(json, "$.HostConfig.MemorySwap").longValue())
                    .isEqualTo(192 * MIB);
        });
    }

    @Test
    void cpuAndProcessCountAreLimited() {
        inspected(false, json -> {
            assertThat(JsonPath.<Number>read(json, "$.HostConfig.NanoCpus").longValue())
                    .isEqualTo(1_000_000_000L);
            assertThat(JsonPath.<Number>read(json, "$.HostConfig.PidsLimit").intValue())
                    .isEqualTo(DockerRunArguments.PIDS_LIMIT);
        });
    }

    @Test
    void theRootFilesystemIsReadOnlyAndTmpIsTheOnlyScratchSpace() {
        inspected(false, json -> {
            assertThat(JsonPath.<Boolean>read(json, "$.HostConfig.ReadonlyRootfs"))
                    .isTrue();
            Map<String, String> tmpfs = JsonPath.read(json, "$.HostConfig.Tmpfs");
            assertThat(tmpfs).containsOnlyKeys("/tmp");
            assertThat(tmpfs.get("/tmp")).contains("noexec", "nosuid", "nodev", "size=64m");
        });
    }

    @Test
    void theSubmissionDirectoryIsTheOnlyMountAndIsReadOnlyForTestRuns() {
        inspected(false, json -> {
            List<Map<String, Object>> mounts = JsonPath.read(json, "$.Mounts");
            assertThat(mounts).hasSize(1);
            assertThat(mounts.get(0))
                    .containsEntry("Type", "bind")
                    .containsEntry("Destination", "/work")
                    .containsEntry("RW", false);
            assertThat(JsonPath.<List<String>>read(json, "$.HostConfig.Binds")).isNullOrEmpty();
        });
    }

    @Test
    void theCompileContainerGetsTheSameDirectoryReadWrite() {
        inspected(true, json -> {
            List<Map<String, Object>> mounts = JsonPath.read(json, "$.Mounts");
            assertThat(mounts).hasSize(1);
            assertThat(mounts.get(0)).containsEntry("Destination", "/work").containsEntry("RW", true);
        });
    }

    @Test
    void theProgramIsNotRootAndHasNoCapabilitiesAndCannotGainPrivileges() {
        inspected(false, json -> {
            assertThat(JsonPath.<String>read(json, "$.Config.User")).isEqualTo("10001:10001");
            assertThat(JsonPath.<List<String>>read(json, "$.HostConfig.CapDrop"))
                    .containsExactly("ALL");
            assertThat(JsonPath.<List<String>>read(json, "$.HostConfig.CapAdd")).isNullOrEmpty();
            assertThat(JsonPath.<List<String>>read(json, "$.HostConfig.SecurityOpt"))
                    .anyMatch(option -> option.startsWith("no-new-privileges"));
            assertThat(JsonPath.<Boolean>read(json, "$.HostConfig.Privileged")).isFalse();
        });
    }

    @Test
    void nothingFromTheHostIsSharedAndNoDeviceIsExposed() {
        inspected(false, json -> {
            assertThat(JsonPath.<String>read(json, "$.HostConfig.PidMode")).isEmpty();
            assertThat(JsonPath.<String>read(json, "$.HostConfig.UsernsMode")).isEmpty();
            assertThat(JsonPath.<List<Object>>read(json, "$.HostConfig.Devices"))
                    .isNullOrEmpty();
            assertThat(JsonPath.<List<String>>read(json, "$.HostConfig.VolumesFrom"))
                    .isNullOrEmpty();
        });
    }

    @Test
    void theContainerRunsInTheWorkDirectoryKeepsStdinOpenAndIsLabelled() {
        inspected(false, json -> {
            assertThat(JsonPath.<String>read(json, "$.Config.WorkingDir")).isEqualTo("/work");
            assertThat(JsonPath.<Boolean>read(json, "$.Config.OpenStdin")).isTrue();
            assertThat(JsonPath.<Map<String, String>>read(json, "$.Config.Labels"))
                    .containsEntry("com.gradingplatform.sandbox", "true");
            // `--rm` would make the inspect after exit impossible (4.5a).
            assertThat(JsonPath.<Boolean>read(json, "$.HostConfig.AutoRemove")).isFalse();
        });
    }
}
