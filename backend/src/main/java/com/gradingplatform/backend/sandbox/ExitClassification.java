package com.gradingplatform.backend.sandbox;

import java.util.Optional;
import java.util.regex.Pattern;

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
 * <p>First, the container must have run (7.7b): Docker's {@code State.Status} is {@code exited} and its
 * {@code State.Error} empty. A container whose start failed (a run command that does not exist, a
 * host that cannot start containers) stays {@code created} with exit code 127, 126 or 128 and the
 * reason in {@code State.Error}; nothing of the student's ran, so it is
 * {@link SandboxStatus#SANDBOX_ERROR}, never a grade. The same goes for any other status (a container
 * still {@code running} or {@code dead}).
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

    /**
     * What {@code docker inspect} recorded for the container: {@code status} as Docker spells it
     * ({@code exited}, {@code created}, ...), {@code error} empty when Docker recorded none.
     */
    record ContainerState(String status, int exitCode, boolean oomKilled, String error) {

        /** The program's own process ran and ended: only then do the exit code and the flag mean anything. */
        boolean ranToItsEnd() {
            return EXITED.equals(status) && error.isEmpty();
        }
    }

    /** 128 + SIGKILL: what the kernel's out-of-memory killer leaves. */
    static final int SIGKILL_EXIT_CODE = 137;

    /** The {@code docker inspect} format {@link #parse} reads; the error goes last because it holds spaces. */
    static final String INSPECT_FORMAT = "{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}";

    private static final String EXITED = "exited";

    private static final Pattern STATUS = Pattern.compile("[a-z]+");

    private ExitClassification() {}

    static SandboxStatus classify(ContainerState state) {
        if (!state.ranToItsEnd()) {
            return SandboxStatus.SANDBOX_ERROR;
        }
        if (state.exitCode() == 0) {
            return SandboxStatus.OK;
        }
        return state.oomKilled() || state.exitCode() == SIGKILL_EXIT_CODE
                ? SandboxStatus.MEMORY_LIMIT
                : SandboxStatus.RUNTIME_ERROR;
    }

    /**
     * Reads the output of {@code docker inspect --format} {@link #INSPECT_FORMAT}, for example
     * {@code "exited 137 true "} or {@code "created 127 false OCI runtime create failed: ..."}; empty
     * when it is not that shape.
     */
    static Optional<ContainerState> parse(String inspectOutput) {
        String[] parts = inspectOutput.strip().split(" ", 4);
        if (parts.length < 3 || !STATUS.matcher(parts[0]).matches()) {
            return Optional.empty();
        }
        try {
            boolean oomKilled = switch (parts[2]) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new NumberFormatException(parts[2]);
            };
            String error = parts.length == 4 ? parts[3].strip() : "";
            return Optional.of(new ContainerState(parts[0], Integer.parseInt(parts[1]), oomKilled, error));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
