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

    /** What a finished command printed. */
    record Result(int exitCode, String stdout, String stderr) {}

    /** The command did not finish in time (and was killed) or could not be started. */
    static final class DockerCommandException extends RuntimeException {
        DockerCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DockerCommand() {}

    static Result run(Duration timeout, String stdin, List<String> dockerArguments) {
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
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new DockerCommandException(
                        "docker " + dockerArguments.get(0) + " did not finish in " + timeout, null);
            }
            return new Result(
                    process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
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
