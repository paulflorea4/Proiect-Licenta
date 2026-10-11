package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs a submission in one fresh Docker container through the {@code docker} command line
 * (4.3a; default of the root {@code CLAUDE.md}: {@link ProcessBuilder}, no library). The restrictions
 * are in {@link DockerRunArguments}.
 *
 * <p>Per run: a private directory on the host receives the source files and is mounted read-only into
 * a container named by us; the container is inspected for its exit code and then always removed.
 * The container is not started with {@code --rm}: 4.5a needs {@code docker inspect} after it exits.
 *
 * <p>Still to come, row by row: the wall-clock timeout that kills the container and reports
 * {@code TIMEOUT} (4.3b), the output size cap (4.3c), guaranteed cleanup of the directory and a test
 * that counts leftovers (4.3d), the Java compile step (4.4b) and mapping of out-of-memory and kills
 * (4.5a). <b>Until 4.3b this must not be wired to anything that takes a student's code:</b> the only
 * bound on a run is {@link #SAFETY_CEILING}, a safety net and not the assignment's time limit.
 *
 * <p>The text of a {@link SandboxStatus#SANDBOX_ERROR} result is for logs: it never goes to a student.
 */
@Component
public class DockerCliSandboxRunner implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerCliSandboxRunner.class);

    /** The longest any single run may take whatever the request says; replaced by 4.3b. */
    static final Duration SAFETY_CEILING = Duration.ofMinutes(10);

    /** For the short housekeeping commands (inspect, rm). */
    static final Duration HOUSEKEEPING = Duration.ofSeconds(60);

    private final SandboxProperties languages;
    private final Path workRoot;

    @Autowired
    public DockerCliSandboxRunner(SandboxProperties languages) {
        this(languages, Path.of(System.getProperty("java.io.tmpdir"), "grading-sandbox"));
    }

    /** @param workRoot where the per-run directories are created; created if missing */
    DockerCliSandboxRunner(SandboxProperties languages, Path workRoot) {
        this.languages = languages;
        this.workRoot = workRoot.toAbsolutePath();
        // The path goes into `--mount type=bind,source=...`, where a comma ends the value.
        if (this.workRoot.toString().contains(",")) {
            throw new IllegalArgumentException("the sandbox work directory must not contain a comma");
        }
        try {
            Files.createDirectories(this.workRoot);
            // The per-run directories below are open to the container's user; this one is what keeps
            // every other user of the host out of them.
            if (this.workRoot.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(this.workRoot, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the sandbox work directory", e);
        }
    }

    @Override
    public SandboxResult run(SandboxRequest request) {
        Optional<LanguageSpec> found = languages.find(request.language());
        if (found.isEmpty()) {
            return sandboxError("language is not configured", Duration.ZERO);
        }
        LanguageSpec spec = found.get();
        if (spec.isCompiled()) {
            return sandboxError(
                    "compiled languages are not supported until the compile step exists (4.4b)", Duration.ZERO);
        }
        Path directory;
        try {
            directory = prepare(request.sourceFiles());
        } catch (IOException e) {
            log.warn("could not prepare the working directory: {}", e.toString());
            return sandboxError("could not prepare the working directory", Duration.ZERO);
        }
        try {
            return runInContainer(spec, request.limits(), directory, false, spec.runCommand(), request.stdin());
        } finally {
            deleteTree(directory);
        }
    }

    /**
     * One container: {@code command} in {@code hostDirectory}, mounted read-only unless
     * {@code writable} (the compile step). Package-private so the compile step and the tests of the
     * flags can use it; callers go through {@link #run}.
     */
    SandboxResult runInContainer(
            LanguageSpec spec,
            ExecutionLimits limits,
            Path hostDirectory,
            boolean writable,
            List<String> command,
            String stdin) {
        String name = "sandbox-" + UUID.randomUUID();
        List<String> arguments = new ArrayList<>();
        arguments.add("run");
        arguments.addAll(DockerRunArguments.build(name, spec.image(), hostDirectory, writable, limits, command));
        long started = System.nanoTime();
        try {
            DockerCommand.Result run = DockerCommand.run(SAFETY_CEILING, stdin, arguments);
            Duration runtime = Duration.ofNanos(System.nanoTime() - started);
            // `docker run` itself exits 125-127 for its own failures, which a program can also
            // return; the container's recorded exit code tells them apart (no container: the
            // sandbox failed, say because the image is missing).
            Integer exitCode = recordedExitCode(name);
            if (exitCode == null) {
                log.warn(
                        "docker could not run container {}: {}",
                        name,
                        run.stderr().strip());
                return sandboxError("the container could not be run", runtime);
            }
            SandboxStatus status = exitCode == 0 ? SandboxStatus.OK : SandboxStatus.RUNTIME_ERROR;
            return new SandboxResult(status, exitCode, run.stdout(), run.stderr(), false, false, runtime);
        } catch (DockerCommand.DockerCommandException e) {
            log.warn("docker failed for container {}: {}", name, e.getMessage());
            return sandboxError("docker failed", Duration.ofNanos(System.nanoTime() - started));
        } finally {
            removeContainer(name);
        }
    }

    private Integer recordedExitCode(String containerName) {
        DockerCommand.Result inspect = DockerCommand.run(
                HOUSEKEEPING, "", List.of("inspect", "--format", "{{.State.ExitCode}}", containerName));
        if (inspect.exitCode() != 0) {
            return null;
        }
        try {
            return Integer.valueOf(inspect.stdout().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Always attempted, and never throws: a failed removal is logged, the result stands. */
    private void removeContainer(String containerName) {
        try {
            DockerCommand.Result removed = DockerCommand.run(HOUSEKEEPING, "", List.of("rm", "--force", containerName));
            if (removed.exitCode() != 0 && !removed.stderr().contains("No such container")) {
                log.warn(
                        "could not remove container {}: {}",
                        containerName,
                        removed.stderr().strip());
            }
        } catch (RuntimeException e) {
            log.warn("could not remove container {}: {}", containerName, e.getMessage());
        }
    }

    /** A new directory for this run, with the source files in it, readable by the container's user. */
    Path prepare(List<SourceFile> files) throws IOException {
        Path directory = Files.createTempDirectory(workRoot, "run-");
        try {
            // The container runs as a different user than the host's: a directory it must be able to
            // write (the compile step) has to be open to it. The parent is not, so other host users
            // still cannot get in. Windows has no POSIX permissions and Docker Desktop does not need them.
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            }
            for (SourceFile file : files) {
                Path target = directory.resolve(file.name()).normalize();
                if (!directory.equals(target.getParent())) {
                    throw new IOException("source file would be written outside the working directory");
                }
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
                }
            }
            return directory;
        } catch (IOException | RuntimeException e) {
            deleteTree(directory);
            throw e;
        }
    }

    static void deleteTree(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("could not remove working directory {}: {}", directory, e.toString());
        }
    }

    private static SandboxResult sandboxError(String message, Duration runtime) {
        return new SandboxResult(SandboxStatus.SANDBOX_ERROR, null, "", message, false, false, runtime);
    }
}
