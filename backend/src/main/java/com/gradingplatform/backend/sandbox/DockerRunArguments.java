package com.gradingplatform.backend.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The arguments of one sandbox container (4.3a), after {@code docker run} / {@code docker create}.
 * This is where the restrictions of the root {@code CLAUDE.md} (Sandbox) are written down, in one
 * place and as constants; {@code SandboxFlagsTests} reads them back from a created container.
 *
 * <p>Never loosen any of these without being asked. Everything here comes from configuration or from
 * us (container name, image, commands, limits): no student-controlled text is ever an argument,
 * the submission reaches the container only as files in the mounted directory and as stdin.
 */
final class DockerRunArguments {

    /** Must match the user created in the Dockerfiles under {@code infra/sandbox}. */
    static final String USER = "10001:10001";

    /** Where the submission's directory appears; the images' working directory. */
    static final String CONTAINER_WORKDIR = "/work";

    /** Marks every container we start, so leftovers can be found and counted (4.3d). */
    static final String LABEL = "com.gradingplatform.sandbox=true";

    /** Placeholder, pending the human's tuning: CPUs one container may use. */
    static final String CPUS = "1";

    /** Placeholder, pending tuning with Java (4.4b): processes and threads in one container. */
    static final int PIDS_LIMIT = 128;

    /** The only writable place besides the mounted directory (never executable). */
    static final String TMPFS = "/tmp:rw,noexec,nosuid,nodev,size=64m";

    private DockerRunArguments() {}

    /**
     * @param containerName a name we generate (never user text), so the container can be found again
     * @param image the image of the language, from the languages config
     * @param hostDirectory the submission's directory on the host
     * @param workdirWritable read-write for the compile container, read-only for every test container
     * @param limits memory (with swap disabled) from the assignment
     * @param command the command to run in the container, from the languages config
     */
    static List<String> build(
            String containerName,
            String image,
            Path hostDirectory,
            boolean workdirWritable,
            ExecutionLimits limits,
            List<String> command) {
        String memory = limits.memoryLimitMb() + "m";
        List<String> args = new ArrayList<>();
        args.addAll(List.of("--name", containerName, "--label", LABEL));
        args.add("--interactive"); // stdin is how the test input arrives
        args.addAll(List.of("--pull", "never")); // never fetch anything at run time
        args.addAll(List.of("--network", "none"));
        // Same value for both: swap is disabled, so memory growth cannot spill to disk.
        args.addAll(List.of("--memory", memory, "--memory-swap", memory));
        args.addAll(List.of("--cpus", CPUS, "--pids-limit", String.valueOf(PIDS_LIMIT)));
        args.add("--read-only");
        args.addAll(List.of("--tmpfs", TMPFS));
        // --mount, not -v: a Windows host path contains a colon, which -v would split on.
        args.addAll(List.of(
                "--mount",
                "type=bind,source=" + hostDirectory + ",target=" + CONTAINER_WORKDIR
                        + (workdirWritable ? "" : ",readonly")));
        args.addAll(List.of("--workdir", CONTAINER_WORKDIR));
        args.addAll(List.of("--user", USER));
        args.addAll(List.of("--cap-drop", "ALL"));
        args.addAll(List.of("--security-opt", "no-new-privileges"));
        args.add(image);
        args.addAll(command);
        return List.copyOf(args);
    }
}
