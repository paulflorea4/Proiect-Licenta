package com.gradingplatform.backend.sandbox;

import java.time.Duration;
import java.util.Objects;

/**
 * What one sandbox run produced. For {@link SandboxStatus#COMPILE_ERROR} the compiler's output is
 * in {@code stderr} (and {@code stdout}). Output is text decoded as UTF-8; when the runner cut it
 * at its size cap (4.3c) the matching {@code ...Truncated} flag is set, and a truncated output must
 * never be compared as if it were complete.
 *
 * @param status how the run ended
 * @param exitCode the program's exit code, or {@code null} when there is none (compile error, timeout, sandbox failure)
 * @param stdout what the program wrote to standard output, never {@code null}
 * @param stderr what the program wrote to standard error, never {@code null}
 * @param stdoutTruncated whether {@code stdout} was cut at the size cap
 * @param stderrTruncated whether {@code stderr} was cut at the size cap
 * @param runtime wall-clock time of the run, never negative
 */
public record SandboxResult(
        SandboxStatus status,
        Integer exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        Duration runtime) {

    public SandboxResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(runtime, "runtime");
        if (runtime.isNegative()) {
            throw new IllegalArgumentException("runtime must not be negative");
        }
        if (status == SandboxStatus.OK && (exitCode == null || exitCode != 0)) {
            throw new IllegalArgumentException("OK means exit code 0");
        }
    }

    /** True when the run ended {@link SandboxStatus#OK} and stdout was not cut: the output can be compared. */
    public boolean completedCleanly() {
        return status == SandboxStatus.OK && !stdoutTruncated;
    }

    /** What the program printed is the student's data and may be large: log lengths only. */
    @Override
    public String toString() {
        return "SandboxResult[status=" + status + ", exitCode=" + exitCode + ", stdout=<" + stdout.length()
                + " chars>, stderr=<" + stderr.length() + " chars>, stdoutTruncated=" + stdoutTruncated
                + ", stderrTruncated=" + stderrTruncated + ", runtime=" + runtime + "]";
    }
}
