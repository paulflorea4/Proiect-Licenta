package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code docker} command line (4.3a). Arguments are a list handed to {@link ProcessBuilder}
 * one by one: no shell is involved, so nothing in an argument is split, expanded or interpreted.
 *
 * <p>Output goes to temporary files rather than pipes, so a command that prints a lot can never
 * block on a full pipe while we wait for it (the output size cap is 4.3c).
 */
final class DockerCommand {

    /**
     * What a finished command printed. {@code timedOut}: the deadline passed and the timeout action
     * ran; the output is whatever was printed until the command ended.
     */
    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /** The command did not finish in time (and was killed) or could not be started. */
    static final class DockerCommandException extends RuntimeException {
        DockerCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DockerCommand() {}

    /** How long the client gets to end after the timeout action ran, before it is destroyed. */
    static final Duration AFTER_TIMEOUT = Duration.ofSeconds(15);

    /** Runs a command that must finish in time: otherwise the client is destroyed and this throws. */
    static Result run(Duration timeout, String stdin, List<String> dockerArguments) {
        return run(timeout, stdin, dockerArguments, null);
    }

    /**
     * Runs a command. When it does not finish in {@code timeout}: with an {@code onTimeout} action,
     * the action runs (it must stop what the command started, e.g. {@code docker kill}), the client
     * is given {@link #AFTER_TIMEOUT} to end and the result is flagged {@code timedOut}; without one,
     * the client is destroyed and this throws. Killing only the client would leave a container running.
     */
    static Result run(Duration timeout, String stdin, List<String> dockerArguments, Runnable onTimeout) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(dockerArguments);
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile("docker-out", ".txt");
            err = Files.createTempFile("docker-err", ".txt");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            feed(process, stdin);
            boolean timedOut = false;
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                if (onTimeout == null) {
                    process.destroyForcibly();
                    throw new DockerCommandException(
                            "docker " + dockerArguments.get(0) + " did not finish in " + timeout, null);
                }
                timedOut = true;
                try {
                    onTimeout.run();
                } finally {
                    // Normally the client ends by itself once its container is gone.
                    if (!process.waitFor(AFTER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(AFTER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    }
                }
            }
            return new Result(
                    process.isAlive() ? -1 : process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8),
                    timedOut);
        } catch (IOException e) {
            throw new DockerCommandException("could not run docker " + dockerArguments.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerCommandException("interrupted while waiting for docker " + dockerArguments.get(0), e);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /**
     * Writes stdin on its own thread: a program that never reads its input would otherwise block this
     * thread on a full pipe before the timeout could apply. When the process ends, the write fails
     * with a broken pipe and the thread ends with it.
     */
    private static void feed(Process process, String stdin) {
        byte[] bytes = stdin.getBytes(StandardCharsets.UTF_8);
        Thread writer = new Thread(
                () -> {
                    try (OutputStream in = process.getOutputStream()) {
                        in.write(bytes);
                    } catch (IOException closedEarly) {
                        // The program may exit without reading its input; that is its right, not an error.
                    }
                },
                "docker-stdin");
        writer.setDaemon(true);
        writer.start();
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A leftover temp file is not worth failing a run for.
            }
        }
    }
}
