package com.gradingplatform.backend.sandbox;

import java.util.Optional;

/**
 * How a container that ended on its own is turned into a {@link SandboxStatus} (4.5a, rule for 137
 * changed in 4.7a). Pure, so the table can be tested without Docker.
 *
 * <p>The exit code alone is not enough: 137 (128 + SIGKILL) is what the kernel's out-of-memory killer
 * leaves. Docker also records whether that killer fired ({@code State.OOMKilled}, read with
 * {@code docker inspect} before the container is removed, 4.3d), but on the CI runner that flag was
 * sometimes missing for a real memory bomb, and reading it again did not help (it was not late, it
 * was absent). Nothing but the kernel can SIGKILL a container's main process from inside, and our own
 * {@code docker kill} on a timeout never reaches this table (the runner decides
 * {@link SandboxStatus#TIMEOUT} first, from its own deadline), so 137 is read as memory whatever the
 * flag says. The one program this mislabels is one that deliberately calls {@code exit(137)}.
 *
 * <p>The table, for a container that exited without us stopping it:
 *
 * <ul>
 *   <li>exit 0: {@link SandboxStatus#OK}, whatever the flag says. The main process ran to its end and
 *       chose to exit; if a helper process it started was killed for memory, the program dealt with it.
 *   <li>exit 137, flag or not: {@link SandboxStatus#MEMORY_LIMIT}.
 *   <li>any other non-zero code and the out-of-memory flag set: {@link SandboxStatus#MEMORY_LIMIT}.
 *       The kernel did kill something for memory, and a program that then exits 1 because its
 *       worker vanished has still hit the limit. (This case still depends on the flag.)
 *   <li>any other non-zero code, no flag: {@link SandboxStatus#RUNTIME_ERROR}.
 * </ul>
 */
final class ExitClassification {

    /** What {@code docker inspect} recorded for the container. */
    record ContainerState(int exitCode, boolean oomKilled) {}

    /** 128 + SIGKILL: what the kernel's out-of-memory killer leaves. */
    static final int SIGKILL_EXIT_CODE = 137;

    private ExitClassification() {}

    static SandboxStatus classify(ContainerState state) {
        if (state.exitCode() == 0) {
            return SandboxStatus.OK;
        }
        return state.oomKilled() || state.exitCode() == SIGKILL_EXIT_CODE
                ? SandboxStatus.MEMORY_LIMIT
                : SandboxStatus.RUNTIME_ERROR;
    }

    /**
     * Reads the output of {@code docker inspect --format "{{.State.ExitCode}} {{.State.OOMKilled}}"},
     * for example {@code "137 true"}; empty when it is not that shape.
     */
    static Optional<ContainerState> parse(String inspectOutput) {
        String[] parts = inspectOutput.strip().split(" ");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            boolean oomKilled = switch (parts[1]) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new NumberFormatException(parts[1]);
            };
            return Optional.of(new ContainerState(Integer.parseInt(parts[0]), oomKilled));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
