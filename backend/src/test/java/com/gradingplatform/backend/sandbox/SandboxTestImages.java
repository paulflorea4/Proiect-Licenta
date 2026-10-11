package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Builds the runner images from {@code infra/sandbox} for the tests that need them, once per test run. */
final class SandboxTestImages {

    private static final Duration BUILD = Duration.ofMinutes(10);
    private static final Path SANDBOX_DIR = Path.of("../infra/sandbox");
    private static final Set<String> BUILT = new HashSet<>();

    private SandboxTestImages() {}

    static Path dockerfile(String languageId) {
        return SANDBOX_DIR.resolve(languageId.toLowerCase(Locale.ROOT)).resolve("Dockerfile");
    }

    /** Builds the image of a language under the name in the config (cached by Docker), and returns that name. */
    static synchronized String ensureBuilt(SandboxProperties languages, String languageId) {
        String image = languages.find(languageId).orElseThrow().image();
        if (BUILT.contains(image)) {
            return image;
        }
        assertThat(dockerfile(languageId)).as("Dockerfile of " + languageId).isRegularFile();

        DockerCli.Output built = docker(
                BUILD,
                "build",
                "--quiet",
                "--tag",
                image,
                dockerfile(languageId).getParent().toString());

        assertThat(built.exitCode()).as(built.stderr()).isZero();
        BUILT.add(image);
        return image;
    }
}
