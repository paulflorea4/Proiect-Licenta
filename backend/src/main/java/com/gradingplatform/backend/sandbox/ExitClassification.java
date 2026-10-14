package com.gradingplatform.backend.sandbox;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * How a container that ended on its own is turned into a {@link SandboxStatus} (4.5a). Pure, so the
 * table can be tested without Docker.
 *
 * <p>The exit code alone is not enough: 137 (128 + SIGKILL) is what the kernel's out-of-memory killer
 * leaves, but it is also what any other SIGKILL leaves, and a program may simply {@code exit(137)}.
 * Docker records whether the kernel's OOM killer fired in the container ({@code State.OOMKilled}); that
 * flag, read with {@code docker inspect} before the container is removed (4.3d), is what tells the
 * cases apart. Our own {@code docker kill} on a timeout never reaches this table: the runner decides
 * {@link SandboxStatus#TIMEOUT} first, from its own deadline.
 *
 * <p>The table, for a container that exited without us stopping it:
 *
 * <ul>
 *   <li>exit 0: {@link SandboxStatus#OK}, whatever the flag says. The main process ran to its end and
 *       chose to exit; if a helper process it started was killed for memory, the program dealt with it.
 *   <li>non-zero and the out-of-memory flag set: {@link SandboxStatus#MEMORY_LIMIT}, whatever the
 *       code. The kernel did kill something for memory, and a program that then exits 1 because its
 *       worker vanished has still hit the limit.
 *   <li>non-zero, no flag (including 137): {@link SandboxStatus#RUNTIME_ERROR}.
 * </ul>
 *
 * <p>Docker can record the exit before it records the flag: on a CI runner a memory bomb was read as
 * {@code 137 false} (so {@code RUNTIME_ERROR}) and the flag was there a moment later. So a
 * {@code 137 false} is read again a few times before it is believed ({@link #settle}); a program that
 * really exits with 137 pays that wait once, in the sandbox, not in its reported runtime.
 */
final class ExitClassification {

    /** What {@code docker inspect} recorded for the container. */
    record ContainerState(int exitCode, boolean oomKilled) {}

    /** 128 + SIGKILL: what the kernel's out-of-memory killer leaves, and the only code worth a second look. */
    static final int SIGKILL_EXIT_CODE = 137;

    private ExitClassification() {}

    static SandboxStatus classify(ContainerState state) {
        if (state.exitCode() == 0) {
            return SandboxStatus.OK;
        }
        return state.oomKilled() ? SandboxStatus.MEMORY_LIMIT : SandboxStatus.RUNTIME_ERROR;
    }

    /**
     * The recorded state, read again while it is {@code 137 false}: that is the one shape that can still
     * become {@code 137 true} because Docker records the flag a moment after the exit. Every other
     * state is final at once. At most {@code rechecks} further reads, each after {@code pause}; a read
     * that fails or is unreadable keeps the last good state, and a {@code pause} that cannot wait
     * (the thread was interrupted) ends the rechecking.
     *
     * @param inspect reads the state of the container (empty when it cannot be read)
     * @param rechecks how many more reads after the first one
     * @param pause waits between two reads; returns {@code false} when it could not wait
     */
    static Optional<ContainerState> settle(
            Supplier<Optional<ContainerState>> inspect, int rechecks, BooleanSupplier pause) {
        Optional<ContainerState> state = inspect.get();
        for (int i = 0; i < rechecks && state.isPresent() && mayStillGainTheFlag(state.get()); i++) {
            if (!pause.getAsBoolean()) {
                break;
            }
            Optional<ContainerState> again = inspect.get();
            if (again.isPresent()) {
                state = again;
            }
        }
        return state;
    }

    private static boolean mayStillGainTheFlag(ContainerState state) {
        return state.exitCode() == SIGKILL_EXIT_CODE && !state.oomKilled();
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
