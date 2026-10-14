package com.gradingplatform.backend.sandbox;

import java.util.Objects;

/**
 * What happened to one test of one submission (4.6a). The names are exactly the values the
 * {@code test_results.status} column holds (its migration lists them): Phase 5 stores {@code name()}. A sandbox
 * failure is not an outcome, see {@link SandboxFailedException}.
 */
public enum TestOutcome {
    /** The program finished cleanly and its output matches the expected output. */
    PASSED,
    /** The program finished cleanly but the output differs, or was cut at the size cap. */
    FAILED,
    TIMEOUT,
    MEMORY_LIMIT,
    RUNTIME_ERROR,
    COMPILE_ERROR;

    /**
     * @param run what the sandbox reported for this test
     * @param passed whether {@link OutputComparison#passes} said the run passes
     * @throws SandboxFailedException when the run is a {@link SandboxStatus#SANDBOX_ERROR}
     */
    static TestOutcome of(SandboxResult run, boolean passed) {
        Objects.requireNonNull(run, "run");
        return switch (run.status()) {
            case OK -> passed ? PASSED : FAILED;
            case COMPILE_ERROR -> COMPILE_ERROR;
            case RUNTIME_ERROR -> RUNTIME_ERROR;
            case TIMEOUT -> TIMEOUT;
            case MEMORY_LIMIT -> MEMORY_LIMIT;
            case SANDBOX_ERROR -> throw new SandboxFailedException();
        };
    }
}
