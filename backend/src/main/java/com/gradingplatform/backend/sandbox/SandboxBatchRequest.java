package com.gradingplatform.backend.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * One submission to be run against several inputs (4.6a): the code is built once and then run once per
 * input, each in its own container. Like {@link SandboxRequest} it carries only what reaches the
 * program: the inputs, never the expected outputs or whether a test is hidden.
 *
 * @param language the language id, as in {@link SandboxRequest}
 * @param sourceFiles the files to put in the working directory, validated like {@link SandboxRequest}
 * @param stdins what each run reads on standard input, in run order; at least one, each may be empty
 * @param limits the per-run limits, applied to every run
 */
public record SandboxBatchRequest(
        String language, List<SourceFile> sourceFiles, List<String> stdins, ExecutionLimits limits) {

    public SandboxBatchRequest {
        // The same checks, in one place: a batch is valid exactly when a single request of it would be.
        sourceFiles = new SandboxRequest(language, sourceFiles, "", limits).sourceFiles();
        Objects.requireNonNull(stdins, "stdins");
        stdins = List.copyOf(stdins); // also rejects null elements, and freezes the list
        if (stdins.isEmpty()) {
            throw new IllegalArgumentException("at least one input is required");
        }
    }

    /** The request for the run at {@code index}. */
    SandboxRequest requestFor(int index) {
        return new SandboxRequest(language, sourceFiles, stdins.get(index), limits);
    }

    /** Student code and test input stay out of logs. */
    @Override
    public String toString() {
        return "SandboxBatchRequest[language=" + language + ", sourceFiles=" + sourceFiles + ", runs=" + stdins.size()
                + ", limits=" + limits + "]";
    }
}
