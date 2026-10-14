package com.gradingplatform.backend.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
 * <p>Wall-clock timeout (4.3b): a run gets the request's time limit plus {@link #START_GRACE}; when
 * that passes the <em>container</em> is killed ({@code docker kill}: stopping only the client process
 * would leave the program running) and the result is {@link SandboxStatus#TIMEOUT} with whatever was
 * printed until then.
 *
 * <p>Output cap (4.3c): at most {@link #OUTPUT_LIMIT_BYTES} of stdout, and as much of stderr, is kept;
 * the rest is read and thrown away while the program runs, so neither memory nor disk grows with what
 * it prints, and the result's {@code ...Truncated} flag says that output was cut.
 *
 * <p>Cleanup (4.3d): the container is removed and the directory deleted in {@code finally} blocks, so
 * they are gone after a success, a failing program, a timeout, a docker failure and an interrupted
 * thread alike ({@code SandboxCleanupTests} counts what is left). A crash of the JVM itself cannot be
 * covered here.
 *
 * <p>Startup sweep (4.3e): when the JVM itself is killed or crashes, no {@code finally} runs.
 * {@link #sweepOrphans} removes what such a run left, and is called once at startup by
 * {@link SandboxStartupSweep}, before anything can start a run.
 *
 * <p>Compile step (4.4b, once per batch since 4.6a): for a language with a compile command, one writable container (with
 * {@link #COMPILE_LIMITS}) builds into the run directory first; if that fails the result is
 * {@link SandboxStatus#COMPILE_ERROR} with the compiler's output and no test container is started,
 * otherwise every run (4.6a: each test) uses the built directory, mounted read-only.
 *
 * <p>Exit conditions (4.5a): a container that ended on its own is classified from its recorded exit
 * code and out-of-memory flag by {@link ExitClassification}; a timeout is decided first, from our own
 * deadline, so our {@code docker kill} is never mistaken for a memory kill.
 *
 * <p>The text of a {@link SandboxStatus#SANDBOX_ERROR} result is for logs: it never goes to a student.
 */
@Component
public class DockerCliSandboxRunner implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerCliSandboxRunner.class);

    /**
     * Added to the request's time limit before a run is killed: starting a container takes time
     * that is the platform's, not the program's. Placeholder, pending the human's tuning. A program
     * that overruns by less than this is not stopped (its reported runtime shows it).
     */
    static final Duration START_GRACE = Duration.ofSeconds(2);

    /** How often, and for how long, a kill is retried while the container does not exist yet. */
    private static final int KILL_ATTEMPTS = 20;

    private static final long KILL_RETRY_MILLIS = 250;

    /**
     * The most of a program's stdout, and of its stderr, that is kept. Placeholder, pending the human's
     * tuning, and well above the 100000 characters a test case's expected output may have (4.1b/3.5a
     * caps): a correct program must never be cut short by it. It bounds memory, not correctness.
     */
    static final int OUTPUT_LIMIT_BYTES = 1024 * 1024;

    /**
     * Limits of the compile container (4.4b), which are the platform's and not the assignment's: a
     * student's memory limit for the program (it may be 128 MB) is too little for {@code javac}.
     * Placeholders, pending the human's tuning. Time and memory only; every other restriction of
     * {@link DockerRunArguments} applies to the compile container unchanged.
     */
    static final ExecutionLimits COMPILE_LIMITS = new ExecutionLimits(30_000, 512);

    /** The name of every per-run directory under the work root, followed by what the JDK appends. */
    static final String RUN_DIRECTORY_PREFIX = "run-";

    /** What {@link #sweepOrphans} removed. */
    record SweepResult(int containers, int directories) {}

    /** For the short housekeeping commands (inspect, rm). */
    static final Duration HOUSEKEEPING = Duration.ofSeconds(60);

    private final SandboxProperties languages;
    private final Path workRoot;
    private final ExecutionLimits compileLimits;

    @Autowired
    public DockerCliSandboxRunner(SandboxProperties languages) {
        this(languages, Path.of(System.getProperty("java.io.tmpdir"), "grading-sandbox"));
    }

    /** @param workRoot where the per-run directories are created; created if missing */
    DockerCliSandboxRunner(SandboxProperties languages, Path workRoot) {
        this(languages, workRoot, COMPILE_LIMITS);
    }

    /** @param compileLimits what the compile container may use; the tests of the compile step shrink it */
    DockerCliSandboxRunner(SandboxProperties languages, Path workRoot, ExecutionLimits compileLimits) {
        this.languages = languages;
        this.compileLimits = compileLimits;
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
        return runAll(new SandboxBatchRequest(
                        request.language(), request.sourceFiles(), List.of(request.stdin()), request.limits()))
                .get(0);
    }

    /**
     * Builds once, then runs every input in its own fresh container on the same, now read-only,
     * directory (4.6a). Nothing is shared between the runs but the built files: each container has its
     * own tmpfs and processes, and cannot write the directory. A failed build is returned for every
     * input; after a {@code SANDBOX_ERROR} the remaining inputs are not tried.
     */
    @Override
    public List<SandboxResult> runAll(SandboxBatchRequest request) {
        int count = request.stdins().size();
        Optional<LanguageSpec> found = languages.find(request.language());
        if (found.isEmpty()) {
            return Collections.nCopies(count, sandboxError("language is not configured", Duration.ZERO));
        }
        LanguageSpec spec = found.get();
        Path directory;
        try {
            directory = prepare(request.sourceFiles());
        } catch (IOException e) {
            log.warn("could not prepare the working directory: {}", e.toString());
            return Collections.nCopies(count, sandboxError("could not prepare the working directory", Duration.ZERO));
        }
        try {
            if (spec.isCompiled()) {
                SandboxResult compiled =
                        runInContainer(spec, compileLimits, directory, true, spec.compileCommand(), "");
                if (compiled.status() != SandboxStatus.OK) {
                    return Collections.nCopies(count, compileFailure(compiled));
                }
            }
            List<SandboxResult> results = new ArrayList<>(count);
            for (String stdin : request.stdins()) {
                if (!results.isEmpty() && results.get(results.size() - 1).status() == SandboxStatus.SANDBOX_ERROR) {
                    results.add(sandboxError("not run: the sandbox failed on an earlier input", Duration.ZERO));
                } else {
                    results.add(runInContainer(spec, request.limits(), directory, false, spec.runCommand(), stdin));
                }
            }
            return results;
        } finally {
            deleteTree(directory);
        }
    }

    /**
     * The compile step did not succeed: the student's code does not build ({@code COMPILE_ERROR}, with the
     * compiler's own output), unless the sandbox itself failed, which stays a {@code SANDBOX_ERROR}. A
     * compiler that runs out of time is also the code's doing, and gets a fixed message because there is
     * no output worth showing.
     */
    private static SandboxResult compileFailure(SandboxResult compiled) {
        switch (compiled.status()) {
            case SANDBOX_ERROR:
                return compiled;
            case TIMEOUT:
                return new SandboxResult(
                        SandboxStatus.COMPILE_ERROR,
                        null,
                        "",
                        "Compilation took too long and was stopped.",
                        false,
                        false,
                        compiled.runtime());
            default:
                return new SandboxResult(
                        SandboxStatus.COMPILE_ERROR,
                        compiled.exitCode(),
                        compiled.stdout(),
                        compiled.stderr(),
                        compiled.stdoutTruncated(),
                        compiled.stderrTruncated(),
                        compiled.runtime());
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
        Duration deadline = Duration.ofMillis(limits.timeLimitMs()).plus(START_GRACE);
        try {
            DockerCommand.Result run =
                    DockerCommand.run(deadline, stdin, arguments, () -> killContainer(name), OUTPUT_LIMIT_BYTES);
            Duration runtime = Duration.ofNanos(System.nanoTime() - started);
            if (run.timedOut()) {
                return new SandboxResult(
                        SandboxStatus.TIMEOUT,
                        null,
                        run.stdout(),
                        run.stderr(),
                        run.stdoutTruncated(),
                        run.stderrTruncated(),
                        runtime);
            }
            // `docker run` itself exits 125-127 for its own failures, which a program can also
            // return; the container's recorded state tells them apart (no container: the sandbox
            // failed, say because the image is missing). The state also says whether the kernel
            // killed the program for memory (4.5a).
            ExitClassification.ContainerState state = recordedState(name).orElse(null);
            if (state == null) {
                log.warn(
                        "docker could not run container {}: {}",
                        name,
                        run.stderr().strip());
                return sandboxError("the container could not be run", runtime);
            }
            return new SandboxResult(
                    ExitClassification.classify(state),
                    state.exitCode(),
                    run.stdout(),
                    run.stderr(),
                    run.stdoutTruncated(),
                    run.stderrTruncated(),
                    runtime);
        } catch (DockerCommand.DockerCommandException e) {
            log.warn("docker failed for container {}: {}", name, e.getMessage());
            return sandboxError("docker failed", Duration.ofNanos(System.nanoTime() - started));
        } finally {
            removeContainer(name);
        }
    }

    /**
     * Removes what an earlier process left behind when it died mid-run (4.3e): every container with
     * our label, running or not, and every {@code run-*} directory under the work root. Anything else
     * in either place is left alone. <b>Only for a moment when no run of this process exists yet</b>
     * (startup), and it assumes this is the only backend using the work root and the label (v1 runs one
     * instance): with a second instance it would remove that instance's runs.
     *
     * <p>Never throws and never needs Docker: if Docker cannot be asked, the containers are skipped
     * (logged) and the directories are still handled.
     */
    SweepResult sweepOrphans() {
        return new SweepResult(sweepContainers(), sweepDirectories());
    }

    private int sweepContainers() {
        List<String> ids;
        try {
            DockerCommand.Result listed = DockerCommand.run(
                    HOUSEKEEPING,
                    "",
                    List.of("ps", "--all", "--quiet", "--no-trunc", "--filter", "label=" + DockerRunArguments.LABEL));
            if (listed.exitCode() != 0) {
                log.warn(
                        "startup sweep: could not list sandbox containers: {}",
                        listed.stderr().strip());
                return 0;
            }
            ids = listed.stdout()
                    .lines()
                    .map(String::strip)
                    .filter(id -> !id.isEmpty())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("startup sweep: could not list sandbox containers: {}", e.getMessage());
            return 0;
        }
        for (String id : ids) {
            removeContainer(id);
        }
        if (!ids.isEmpty()) {
            log.warn(
                    "startup sweep: removed {} sandbox container(s) left by an earlier run of the backend", ids.size());
        }
        return ids.size();
    }

    private int sweepDirectories() {
        List<Path> orphans;
        try (Stream<Path> entries = Files.list(workRoot)) {
            orphans = entries.filter(path -> path.getFileName().toString().startsWith(RUN_DIRECTORY_PREFIX))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.warn("startup sweep: could not list {}: {}", workRoot, e.toString());
            return 0;
        }
        for (Path orphan : orphans) {
            deleteTree(orphan);
        }
        if (!orphans.isEmpty()) {
            log.warn(
                    "startup sweep: removed {} working director(ies) left by an earlier run of the backend",
                    orphans.size());
        }
        return orphans.size();
    }

    /**
     * Kills the container itself. If it does not exist yet (the deadline fired while Docker was still
     * creating it) the kill is retried for a few seconds; the removal afterwards catches any remainder.
     */
    private void killContainer(String containerName) {
        for (int attempt = 0; attempt < KILL_ATTEMPTS; attempt++) {
            DockerCommand.Result killed = DockerCommand.run(HOUSEKEEPING, "", List.of("kill", containerName));
            if (killed.exitCode() == 0 || !killed.stderr().contains("No such container")) {
                if (killed.exitCode() != 0 && !killed.stderr().contains("is not running")) {
                    log.warn(
                            "could not kill container {}: {}",
                            containerName,
                            killed.stderr().strip());
                }
                return;
            }
            try {
                Thread.sleep(KILL_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("container {} did not appear to be killed", containerName);
    }

    private Optional<ExitClassification.ContainerState> recordedState(String containerName) {
        DockerCommand.Result inspect = DockerCommand.run(
                HOUSEKEEPING,
                "",
                List.of("inspect", "--format", "{{.State.ExitCode}} {{.State.OOMKilled}}", containerName));
        if (inspect.exitCode() != 0) {
            return Optional.empty();
        }
        return ExitClassification.parse(inspect.stdout());
    }

    /**
     * Always attempted, and never throws: a failed removal is logged, the result stands. It also runs
     * when the calling thread was interrupted (shutdown, cancellation): a set interrupt flag would make
     * the wait for {@code docker rm} fail at once, so the flag is cleared for the removal and restored.
     */
    private void removeContainer(String containerName) {
        boolean interrupted = Thread.interrupted();
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
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A new directory for this run, with the source files in it, readable by the container's user. */
    Path prepare(List<SourceFile> files) throws IOException {
        Path directory = Files.createTempDirectory(workRoot, RUN_DIRECTORY_PREFIX);
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
