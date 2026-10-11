package com.gradingplatform.backend.sandbox;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the sandbox needs for one run. The {@code language} is the key into the languages
 * config (4.1b), never a branch in code. {@code stdin} is the only way test data reaches the
 * program; nothing else about the test (its expected output, whether it is hidden) is in here.
 *
 * @param language the language id, e.g. {@code JAVA}; upper-case, as the assignment stores it
 * @param sourceFiles the files to put in the working directory, at least one, names unique
 * @param stdin what the program reads on standard input; may be empty
 * @param limits the per-run limits
 */
public record SandboxRequest(String language, List<SourceFile> sourceFiles, String stdin, ExecutionLimits limits) {

    public SandboxRequest {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(sourceFiles, "sourceFiles");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(limits, "limits");
        if (language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        sourceFiles = List.copyOf(sourceFiles); // also rejects null elements, and freezes the list
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("at least one source file is required");
        }
        Set<String> seen = new HashSet<>();
        for (SourceFile file : sourceFiles) {
            // Case-insensitive: Windows and macOS hosts would put "A.java" and "a.java" in one file.
            if (!seen.add(file.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate source file name");
            }
        }
    }

    /** Student code and test input stay out of logs. */
    @Override
    public String toString() {
        return "SandboxRequest[language=" + language + ", sourceFiles=" + sourceFiles + ", stdin=<" + stdin.length()
                + " chars>, limits=" + limits + "]";
    }
}
