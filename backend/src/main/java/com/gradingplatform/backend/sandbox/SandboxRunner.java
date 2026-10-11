package com.gradingplatform.backend.sandbox;

/**
 * Runs untrusted code and reports what happened (4.1a). Everything above the sandbox (grading,
 * Phase 5) codes against this interface, so a different runner can be swapped in; the Docker CLI
 * implementation comes in 4.3a. Nothing else in the backend may start a container.
 *
 * <p>An implementation must never throw for something the submitted program did (a crash, a loop,
 * a compile error): that is a {@link SandboxStatus}. It reports {@link SandboxStatus#SANDBOX_ERROR}
 * when the sandbox itself failed, and throws only for a programming error such as a {@code null}
 * request. It must apply the limits of the request and may only tighten, never loosen, its own.
 */
public interface SandboxRunner {

    /** Runs one request in a fresh, isolated environment and returns once it has finished or been stopped. */
    SandboxResult run(SandboxRequest request);
}
