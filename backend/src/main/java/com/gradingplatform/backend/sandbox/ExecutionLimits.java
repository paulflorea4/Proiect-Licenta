package com.gradingplatform.backend.sandbox;

/**
 * The limits a teacher sets per assignment (the assignment's time and memory limits). The other
 * limits (CPU, process count, output size) are not negotiable per run and live in the runner (4.3).
 * Both values must be positive: there is no "unlimited".
 *
 * @param timeLimitMs wall-clock limit for one run of the program, in milliseconds
 * @param memoryLimitMb memory limit for one run, in megabytes (swap is never allowed on top)
 */
public record ExecutionLimits(long timeLimitMs, int memoryLimitMb) {

    public ExecutionLimits {
        if (timeLimitMs <= 0) {
            throw new IllegalArgumentException("timeLimitMs must be positive");
        }
        if (memoryLimitMb <= 0) {
            throw new IllegalArgumentException("memoryLimitMb must be positive");
        }
    }
}
