package com.gradingplatform.backend.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * How one language is built and run in the sandbox (4.1b). Adding a language is a new entry under
 * {@code sandbox.languages} in {@code application.yml} plus an image: no code looks at which
 * language it is.
 *
 * <p>The commands are argument lists, never shell strings: the runner passes each element as one
 * argument, so nothing is split, expanded or interpreted by a shell. They run inside the container
 * with the working directory as the current directory, where the source file is written.
 *
 * @param image the Docker image to start (built from {@code infra/sandbox/<language>}, see 4.2b)
 * @param sourceFileName the plain name the student's code is written under, e.g. {@code Main.java}
 *     (for Java it must match the public class: this is the convention teachers and students are
 *     told)
 * @param compileCommand the build step, or empty for an interpreted language
 * @param runCommand the command that runs the program, reading the test input on stdin
 * @param minMemoryMb the smallest memory limit, in megabytes, an assignment in this language may have
 *     (7.1c), or null for none; a JVM does not reliably start below 128 MB
 */
public record LanguageSpec(
        String image,
        String sourceFileName,
        List<String> compileCommand,
        List<String> runCommand,
        Integer minMemoryMb) {

    @ConstructorBinding
    public LanguageSpec {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        // A leading '-' would be read by `docker run` as an option, and whitespace is never in an image name.
        if (image.startsWith("-") || image.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("image is not a valid image name");
        }
        if (sourceFileName == null || !SourceFile.isPlainFileName(sourceFileName)) {
            throw new IllegalArgumentException("sourceFileName must be a plain file name");
        }
        compileCommand = commandOrEmpty(compileCommand, "compileCommand");
        runCommand = commandOrEmpty(runCommand, "runCommand");
        if (runCommand.isEmpty()) {
            throw new IllegalArgumentException("runCommand must not be empty");
        }
        if (minMemoryMb != null && minMemoryMb < 1) {
            throw new IllegalArgumentException("minMemoryMb must be at least 1");
        }
    }

    /** A language without a memory minimum. */
    public LanguageSpec(String image, String sourceFileName, List<String> compileCommand, List<String> runCommand) {
        this(image, sourceFileName, compileCommand, runCommand, null);
    }

    /** Whether an assignment in this language may have this memory limit (7.1c). */
    public boolean allowsMemoryLimit(int memoryLimitMb) {
        return minMemoryMb == null || memoryLimitMb >= minMemoryMb;
    }

    /**
     * The extension an uploaded source file must have for this language (5.1b): that of
     * {@link #sourceFileName}, lower-case, without the dot (for {@code Main.java} it is {@code java}).
     */
    public String sourceFileExtension() {
        return SourceFile.extensionOf(sourceFileName);
    }

    /** Whether the language has a build step before it runs (Java does, Python does not). */
    public boolean isCompiled() {
        return !compileCommand.isEmpty();
    }

    private static List<String> commandOrEmpty(List<String> command, String name) {
        if (command == null) {
            return List.of();
        }
        for (String part : command) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain a blank element");
            }
        }
        return List.copyOf(command);
    }
}
