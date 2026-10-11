package com.gradingplatform.backend.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * The {@code docker} command line for tests (images, containers, hostile programs): a thin layer
 * over {@link DockerCommand}, the one the runner itself uses, that fails the test on a timeout.
 *
 * <p>Arguments are a list, never a shell string. Scripts handed to {@code sh -c} must not contain
 * double quotes: Windows mangles them on the way to {@code docker.exe} (single quotes are fine).
 */
final class DockerCli {

    /** What a finished command printed. */
    record Output(int exitCode, String stdout, String stderr) {}

    private DockerCli() {}

    static Output docker(Duration timeout, String... args) {
        return dockerWithInput(timeout, "", args);
    }

    static Output dockerWithInput(Duration timeout, String stdin, String... args) {
        DockerCommand.Result result = DockerCommand.run(timeout, stdin, List.of(args));
        return new Output(result.exitCode(), result.stdout(), result.stderr());
    }
}
