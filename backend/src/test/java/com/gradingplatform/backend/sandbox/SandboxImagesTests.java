package com.gradingplatform.backend.sandbox;

import static com.gradingplatform.backend.sandbox.DockerCli.docker;
import static com.gradingplatform.backend.sandbox.DockerCli.dockerWithInput;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.2b: the runner images build from {@code infra/sandbox/<language>}, under the names the languages
 * config uses, and are what the runner relies on: a non-root user, a working directory that user can
 * write to, none of the usual download tools, and working compile/run commands from the config.
 *
 * <p>These run the images plainly; the restrictions the runner adds (no network, read-only root
 * filesystem...) are 4.3a's and are tested there. Needs Docker, like every backend test.
 *
 * <p>Scripts passed to {@code sh -c} must not contain double quotes: Windows mangles them on the way
 * to {@code docker.exe} (single quotes are fine).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxImagesTests {

    private static final Duration RUN = Duration.ofSeconds(60);

    @Autowired
    SandboxProperties languages;

    private String build(String id) {
        return SandboxTestImages.ensureBuilt(languages, id);
    }

    /** Runs a shell script in the image as the image's own user, with no extra options. */
    private DockerCli.Output sh(String image, String script) {
        return shWithInput(image, "", script);
    }

    private DockerCli.Output shWithInput(String image, String stdin, String script) {
        return dockerWithInput(RUN, stdin, "run", "--rm", "-i", "--entrypoint", "sh", image, "-c", script);
    }

    /** Test-only: the config commands are constants without spaces inside an element or quotes. */
    private static String shellLine(List<String> command) {
        return String.join(" ", command);
    }

    @Test
    void theDockerfilesSitWhereTheLanguagesConfigNamesThem() {
        for (String id : languages.ids()) {
            assertThat(SandboxTestImages.dockerfile(id)).as(id).isRegularFile();
        }
    }

    @Test
    void bothImagesRunAsTheSameUnprivilegedUserInTheWorkDirectory() {
        for (String id : languages.ids()) {
            String image = build(id);

            DockerCli.Output out = sh(image, "echo $(id -u):$(id -g); pwd; touch probe && echo writable");

            assertThat(out.exitCode()).as(id + " " + out.stderr()).isZero();
            assertThat(out.stdout().lines().toList()).as(id).containsExactly("10001:10001", "/work", "writable");
        }
    }

    @Test
    void theUserCannotChangeTheSystem() {
        for (String id : languages.ids()) {
            String image = build(id);

            DockerCli.Output out = sh(image, "touch /etc/probe 2>&1; echo exit=$?");

            assertThat(out.stdout()).as(id).contains("Permission denied", "exit=1");
        }
    }

    @Test
    void noDownloadToolsOrPackageInstallersAreShipped() {
        for (String id : languages.ids()) {
            String image = build(id);

            // Prints the name of every tool that exists; the exit status of `command -v` differs between shells.
            DockerCli.Output out = sh(
                    image,
                    "for t in curl wget pip pip3 git ssh nc; do command -v $t >/dev/null && echo $t; done; true");

            assertThat(out.exitCode()).as(id + " " + out.stderr()).isZero();
            assertThat(out.stdout().trim())
                    .as(id + ": tools that should not be there")
                    .isEmpty();
        }
    }

    @Test
    void pipIsGoneFromThePythonModuleSearchToo() {
        String image = build("PYTHON");

        DockerCli.Output out = docker(RUN, "run", "--rm", image, "python", "-m", "pip", "--version");

        assertThat(out.exitCode()).isNotZero();
        assertThat(out.stderr()).contains("No module named pip");
    }

    @Test
    void javaCompilesAndRunsWithTheCommandsFromTheConfig() {
        String image = build("JAVA");
        LanguageSpec spec = languages.find("JAVA").orElseThrow();
        String source = "public class Main { public static void main(String[] a) {"
                + " java.util.Scanner s = new java.util.Scanner(System.in);"
                + " System.out.println(s.nextInt() + s.nextInt()); } }";

        DockerCli.Output out = shWithInput(
                image,
                "3 4\n",
                "printf '%s' '" + source + "' > " + spec.sourceFileName() + " && " + shellLine(spec.compileCommand())
                        + " && " + shellLine(spec.runCommand()));

        assertThat(out.exitCode()).as(out.stderr()).isZero();
        assertThat(out.stdout().trim()).isEqualTo("7");
    }

    @Test
    void pythonRunsWithTheCommandFromTheConfig() {
        String image = build("PYTHON");
        LanguageSpec spec = languages.find("PYTHON").orElseThrow();

        DockerCli.Output out = shWithInput(
                image,
                "3 4\n",
                "printf '%s' 'a, b = map(int, input().split()); print(a + b)' > " + spec.sourceFileName() + " && "
                        + shellLine(spec.runCommand()));

        assertThat(out.exitCode()).as(out.stderr()).isZero();
        assertThat(out.stdout().trim()).isEqualTo("7");
    }

    @Test
    void aJavaCompileErrorFailsTheCompileStepWithTheCompilerMessage() {
        String image = build("JAVA");
        LanguageSpec spec = languages.find("JAVA").orElseThrow();

        DockerCli.Output out = sh(
                image,
                "printf '%s' 'public class Main { int x = ; }' > " + spec.sourceFileName() + " && "
                        + shellLine(spec.compileCommand()));

        assertThat(out.exitCode()).isNotZero();
        assertThat(out.stderr()).contains("Main.java:1");
    }
}
