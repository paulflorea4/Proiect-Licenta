package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 4.3a: the argument list of a sandbox container, without Docker. {@code SandboxFlagsTests} reads the
 * same restrictions back from a real container; this pins how they are written and in which form.
 */
class DockerRunArgumentsTests {

    private static final Path DIR = Path.of("/tmp/grading-sandbox/run-1");
    private static final ExecutionLimits LIMITS = new ExecutionLimits(2_000, 256);

    private static List<String> args(boolean writable) {
        return DockerRunArguments.build(
                "sandbox-1", "grading-sandbox-python:dev", DIR, writable, LIMITS, List.of("python", "main.py"));
    }

    /** The value that follows a flag. */
    private static String valueOf(List<String> args, String flag) {
        int at = args.indexOf(flag);
        assertThat(at).as(flag + " is present").isNotNegative();
        return args.get(at + 1);
    }

    @Test
    void theImageAndTheCommandComeLastAndInThatOrder() {
        List<String> args = args(false);

        assertThat(args.subList(args.size() - 3, args.size()))
                .containsExactly("grading-sandbox-python:dev", "python", "main.py");
    }

    @Test
    void thereIsNoNetworkAndNothingIsPulled() {
        assertThat(valueOf(args(false), "--network")).isEqualTo("none");
        assertThat(valueOf(args(false), "--pull")).isEqualTo("never");
    }

    @Test
    void swapIsDisabledByGivingMemoryAndSwapTheSameValue() {
        List<String> args = args(false);

        assertThat(valueOf(args, "--memory")).isEqualTo("256m");
        assertThat(valueOf(args, "--memory-swap")).isEqualTo(valueOf(args, "--memory"));
    }

    @Test
    void cpuAndProcessLimitsAreSet() {
        List<String> args = args(false);

        assertThat(valueOf(args, "--cpus")).isEqualTo(DockerRunArguments.CPUS);
        assertThat(valueOf(args, "--pids-limit")).isEqualTo(String.valueOf(DockerRunArguments.PIDS_LIMIT));
    }

    @Test
    void theRootFilesystemIsReadOnlyWithOnlyANonExecutableTmpWritable() {
        List<String> args = args(false);

        assertThat(args).contains("--read-only");
        assertThat(valueOf(args, "--tmpfs")).startsWith("/tmp:").contains("noexec", "nosuid", "nodev", "size=");
    }

    @Test
    void theSubmissionDirectoryIsTheOnlyMountAndReadOnlyForTestRuns() {
        List<String> readOnly = args(false);
        List<String> writable = args(true);

        assertThat(readOnly.stream().filter("--mount"::equals)).hasSize(1);
        assertThat(readOnly).doesNotContain("--volume", "-v");
        assertThat(valueOf(readOnly, "--mount")).isEqualTo("type=bind,source=" + DIR + ",target=/work,readonly");
        assertThat(valueOf(writable, "--mount")).isEqualTo("type=bind,source=" + DIR + ",target=/work");
        assertThat(valueOf(readOnly, "--workdir")).isEqualTo("/work");
    }

    @Test
    void theProgramRunsAsTheImagesUnprivilegedUserWithNoCapabilitiesAndNoPrivilegeGain() {
        List<String> args = args(false);

        assertThat(valueOf(args, "--user")).isEqualTo("10001:10001");
        assertThat(valueOf(args, "--cap-drop")).isEqualTo("ALL");
        assertThat(valueOf(args, "--security-opt")).isEqualTo("no-new-privileges");
    }

    @Test
    void theContainerIsNamedAndLabelledSoItCanBeFoundAndCounted() {
        List<String> args = args(false);

        assertThat(valueOf(args, "--name")).isEqualTo("sandbox-1");
        assertThat(valueOf(args, "--label")).isEqualTo(DockerRunArguments.LABEL);
    }

    @Test
    void stdinIsOpenButNothingLoosensTheSandbox() {
        List<String> args = args(false);

        assertThat(args).contains("--interactive");
        // `--rm` would make `docker inspect` after the exit impossible (4.5a); nothing below may ever appear.
        assertThat(args)
                .doesNotContain(
                        "--rm",
                        "--privileged",
                        "--cap-add",
                        "--publish",
                        "-p",
                        "--device",
                        "--pid",
                        "--ipc",
                        "--userns",
                        "--oom-kill-disable",
                        "--tty",
                        "--env",
                        "-e");
        assertThat(args).doesNotContain("host");
        assertThat(args.stream().filter(a -> a.startsWith("--network"))).hasSize(1);
    }

    @Test
    void everyArgumentIsOneListElementWithNoShellInvolved() {
        // A command element with spaces or shell characters stays one argument, untouched.
        List<String> args = DockerRunArguments.build(
                "sandbox-1", "img", DIR, false, LIMITS, List.of("sh", "-c", "echo $HOME; ls | wc -l"));

        assertThat(args.get(args.size() - 1)).isEqualTo("echo $HOME; ls | wc -l");
    }

    @Test
    void theListCannotBeChangedAfterwards() {
        List<String> args = args(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> args.add("--privileged"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
