package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.3b: what happens to a container when its {@code docker run} client runs out of time. The classic
 * bug is killing only the client: the container, and the program in it, keeps running. These tests
 * show that bug is real (so the fix is needed) and that {@code docker kill} on the container ends it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DockerCommandTests {

    private static final Duration DOCKER = Duration.ofSeconds(60);

    @Autowired
    SandboxProperties languages;

    private List<String> sleepForever(String name) {
        String image = SandboxTestImages.ensureBuilt(languages, "PYTHON");
        return List.of("run", "--name", name, "--pull", "never", image, "sleep", "300");
    }

    private static String state(String name) {
        return docker(DOCKER, "inspect", "--format", "{{.State.Status}} {{.State.ExitCode}}", name)
                .stdout()
                .strip();
    }

    @Test
    void killingOnlyTheClientLeavesTheContainerRunning() {
        String name = "sandbox-test-" + UUID.randomUUID();
        try {
            assertThatThrownBy(() -> DockerCommand.run(Duration.ofSeconds(2), "", sleepForever(name)))
                    .isInstanceOf(DockerCommand.DockerCommandException.class);

            // The client is gone, the program is not: this is what a plain process kill gets you.
            assertThat(state(name)).startsWith("running");
        } finally {
            docker(DOCKER, "rm", "--force", name);
        }
    }

    @Test
    void theTimeoutActionKillsTheContainerAndTheClientEndsByItself() {
        String name = "sandbox-test-" + UUID.randomUUID();
        try {
            long started = System.nanoTime();

            DockerCommand.Result result = DockerCommand.run(
                    Duration.ofSeconds(2), "", sleepForever(name), () -> docker(DOCKER, "kill", name));

            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(result.timedOut()).isTrue();
            // Ended because the container was killed, not because the client was destroyed 15 s later.
            assertThat(elapsed).isLessThan(DockerCommand.AFTER_TIMEOUT.minusSeconds(3));
            assertThat(state(name)).isEqualTo("exited 137");
        } finally {
            docker(DOCKER, "rm", "--force", name);
        }
    }

    @Test
    void aCommandThatFinishesInTimeIsNotTimedOutAndTheActionNeverRuns() {
        boolean[] ran = {false};

        DockerCommand.Result result =
                DockerCommand.run(DOCKER, "", List.of("version", "--format", "ok"), () -> ran[0] = true);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(ran[0]).isFalse();
    }
}
