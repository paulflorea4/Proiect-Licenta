package com.gradingplatform.backend.sandbox;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Runs one submission against several inputs, one fresh container per input (4.6a), and returns one
     * result per input, in order. A language with a build step is built <b>once</b>: if the build fails
     * every result is that same {@link SandboxStatus#COMPILE_ERROR}. If the sandbox itself fails
     * ({@link SandboxStatus#SANDBOX_ERROR}) the remaining inputs are not tried and get a
     * {@code SANDBOX_ERROR} too. The same promises as {@link #run} apply to every result.
     *
     * <p>This default simply calls {@link #run} for each input, building again every time; an
     * implementation with a build step overrides it to build once.
     */
    default List<SandboxResult> runAll(SandboxBatchRequest request) {
        List<SandboxResult> results = new ArrayList<>(request.stdins().size());
        for (int i = 0; i < request.stdins().size(); i++) {
            results.add(run(request.requestFor(i)));
        }
        return results;
    }
}
