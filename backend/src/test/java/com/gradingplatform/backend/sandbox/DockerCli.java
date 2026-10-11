package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code docker} command line for tests (images now, hostile programs later): arguments are
 * a list, never a shell string, and a command that does not finish in time is killed and fails the
 * test.
 */
final class DockerCli {

    /** What a finished command printed. */
    record Output(int exitCode, String stdout, String stderr) {}

    private DockerCli() {}

    static Output docker(Duration timeout, String... args) {
        return dockerWithInput(timeout, "", args);
    }

    static Output dockerWithInput(Duration timeout, String stdin, String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        try {
            // Files rather than pipes: a command that prints a lot cannot block on a full pipe.
            Path out = Files.createTempFile("docker-out", ".txt");
            Path err = Files.createTempFile("docker-err", ".txt");
            try {
                Process process = new ProcessBuilder(command)
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile())
                        .start();
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("docker " + args[0] + " did not finish in " + timeout);
                }
                return new Output(
                        process.exitValue(),
                        Files.readString(out, StandardCharsets.UTF_8),
                        Files.readString(err, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not run docker", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for docker", e);
        }
    }
}
