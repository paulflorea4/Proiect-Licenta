package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code docker} command line (4.3a). Arguments are a list handed to {@link ProcessBuilder}
 * one by one: no shell is involved, so nothing in an argument is split, expanded or interpreted.
 *
 * <p>Output is read continuously on two threads into a {@link BoundedCapture} each (4.3c): the
 * command is never blocked by a full pipe, and no more than the output limit of either stream is
 * ever held, however much it prints.
 */
final class DockerCommand {

    /** The output limit of commands that are not sandboxed programs (inspect, rm, build...). */
    static final int DEFAULT_OUTPUT_LIMIT = 1024 * 1024;

    /** How long the client gets to end after the timeout action ran, before it is destroyed. */
    static final Duration AFTER_TIMEOUT = Duration.ofSeconds(15);

    /** How long the output threads get to finish once the process has ended. */
    private static final Duration READER_JOIN = Duration.ofSeconds(5);

    /**
     * What a finished command printed. {@code timedOut}: the deadline passed and the timeout action
     * ran; the output is whatever was printed until the command ended. A {@code ...Truncated} flag
     * means that stream had more than the limit and the text is only its beginning.
     */
    record Result(
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean timedOut) {}

    /** The command did not finish in time (and was killed) or could not be started. */
    static final class DockerCommandException extends RuntimeException {
        DockerCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DockerCommand() {}

    /** Runs a command that must finish in time: otherwise the client is destroyed and this throws. */
    static Result run(Duration timeout, String stdin, List<String> dockerArguments) {
        return run(timeout, stdin, dockerArguments, null, DEFAULT_OUTPUT_LIMIT);
    }

    /** Same, with a timeout action (see below) and the default output limit. */
    static Result run(Duration timeout, String stdin, List<String> dockerArguments, Runnable onTimeout) {
        return run(timeout, stdin, dockerArguments, onTimeout, DEFAULT_OUTPUT_LIMIT);
    }

    /**
     * Runs a command. When it does not finish in {@code timeout}: with an {@code onTimeout} action,
     * the action runs (it must stop what the command started, e.g. {@code docker kill}), the client
     * is given {@link #AFTER_TIMEOUT} to end and the result is flagged {@code timedOut}; without one,
     * the client is destroyed and this throws. Killing only the client would leave a container running.
     *
     * @param outputLimitBytes the most of stdout, and of stderr, that is kept
     */
    static Result run(
            Duration timeout, String stdin, List<String> dockerArguments, Runnable onTimeout, int outputLimitBytes) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(dockerArguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            BoundedCapture stdout = new BoundedCapture(process.getInputStream(), outputLimitBytes);
            BoundedCapture stderr = new BoundedCapture(process.getErrorStream(), outputLimitBytes);
            Thread stdoutReader = start(stdout, "docker-stdout");
            Thread stderrReader = start(stderr, "docker-stderr");
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
            stdoutReader.join(READER_JOIN.toMillis());
            stderrReader.join(READER_JOIN.toMillis());
            return new Result(
                    process.isAlive() ? -1 : process.exitValue(),
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    stderr.truncated(),
                    timedOut);
        } catch (IOException e) {
            throw new DockerCommandException("could not run docker " + dockerArguments.get(0), e);
        } catch (InterruptedException e) {
            // The caller removes the container; the client must not outlive the call either.
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new DockerCommandException("interrupted while waiting for docker " + dockerArguments.get(0), e);
        }
    }

    private static Thread start(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
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
}
