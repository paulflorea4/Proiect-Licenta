package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.7b: runs that overlap in time share nothing. Every run gets its own working directory and its own
 * container (and so its own {@code /tmp}), so one submission can neither see another's source, stdin or
 * files nor leak into its output. Several programs with the same file name but different contents run at
 * the same moment; each must see only its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxConcurrentRunsTests {

    private static final int RUNS = 6;
    private static final ExecutionLimits LIMITS = new ExecutionLimits(10_000, 128);

    /**
     * Prints its own secret (stdin), what it finds in {@code /work} and {@code /tmp} before it writes
     * anything, then writes a file named after the secret into {@code /tmp} and waits so that the runs
     * overlap, and finally lists {@code /tmp} again.
     */
    private static final String PROGRAM = """
            import os, sys, time
            secret = sys.stdin.read().strip()
            print('secret=' + secret)
            print('source=' + os.path.basename(__file__) + ':' + str(secret in open(__file__).read()))
            print('work=' + ','.join(sorted(os.listdir('/work'))))
            print('tmp-before=' + ','.join(sorted(os.listdir('/tmp'))))
            open('/tmp/' + secret, 'w').write(secret)
            time.sleep(2)
            print('tmp-after=' + ','.join(sorted(os.listdir('/tmp'))))
            """;

    @Autowired
    SandboxProperties languages;

    @TempDir
    Path workRoot;

    DockerCliSandboxRunner runner;

    @BeforeEach
    void runner() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        runner = new DockerCliSandboxRunner(languages, workRoot);
    }

    private static String value(SandboxResult result, String label) {
        return result.stdout()
                .lines()
                .filter(line -> line.startsWith(label + "="))
                .map(line -> line.substring(label.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError(label + " missing from: " + result.stdout()));
    }

    private int runDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(workRoot)) {
            return (int) entries.count();
        }
    }

    @Test
    void overlappingRunsEachSeeOnlyTheirOwnSourceInputAndFiles() throws Exception {
        List<String> secrets = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            secrets.add("secret-" + i + "-" + Integer.toHexString(("run" + i).hashCode()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(RUNS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SandboxResult>> futures = new ArrayList<>();
        try {
            for (String secret : secrets) {
                futures.add(pool.submit(() -> {
                    go.await();
                    // the secret is in the source too, so a mixed-up directory would show
                    String code = "# " + secret + "\n" + PROGRAM;
                    return runner.run(new SandboxRequest(
                            "PYTHON", List.of(new SourceFile("main.py", code)), secret + "\n", LIMITS));
                }));
            }
            go.countDown();

            int mostDirectoriesAtOnce = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (futures.stream().anyMatch(f -> !f.isDone()) && System.nanoTime() < deadline) {
                mostDirectoriesAtOnce = Math.max(mostDirectoriesAtOnce, runDirectories());
                Thread.sleep(50);
            }

            assertThat(mostDirectoriesAtOnce)
                    .as("the runs really overlapped, each in a directory of its own")
                    .isGreaterThan(1);

            for (int i = 0; i < RUNS; i++) {
                SandboxResult result = futures.get(i).get(60, TimeUnit.SECONDS);
                String secret = secrets.get(i);

                assertThat(result.status()).as(result.stderr()).isEqualTo(SandboxStatus.OK);
                assertThat(value(result, "secret")).isEqualTo(secret);
                assertThat(value(result, "source")).isEqualTo("main.py:True");
                assertThat(value(result, "work")).as("only its own source file").isEqualTo("main.py");
                assertThat(value(result, "tmp-before")).as("a fresh /tmp").isEmpty();
                assertThat(value(result, "tmp-after"))
                        .as("only its own file in /tmp")
                        .isEqualTo(secret);
                for (String other : secrets) {
                    if (!other.equals(secret)) {
                        assertThat(result.stdout()).as("output of run %d", i).doesNotContain(other);
                        assertThat(result.stderr()).doesNotContain(other);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(runDirectories()).as("working directories left behind").isZero();
    }

    @Test
    void overlappingBatchesOfTestsDoNotCrossTalk() throws Exception {
        // two submissions, each run against several inputs at once with runAll; same file name
        String echo = "import sys\nprint('%s:' + sys.stdin.read().strip())\n";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<SandboxResult>>> batches = new ArrayList<>();
            for (String owner : List.of("alice", "bob")) {
                batches.add(pool.submit(() -> runner.runAll(new SandboxBatchRequest(
                        "PYTHON",
                        List.of(new SourceFile("main.py", echo.formatted(owner))),
                        List.of("1\n", "2\n", "3\n"),
                        LIMITS))));
            }
            for (int b = 0; b < batches.size(); b++) {
                String owner = b == 0 ? "alice" : "bob";
                List<SandboxResult> results = batches.get(b).get(120, TimeUnit.SECONDS);
                assertThat(results).hasSize(3);
                for (int i = 0; i < 3; i++) {
                    assertThat(results.get(i).status()).isEqualTo(SandboxStatus.OK);
                    assertThat(results.get(i).stdout()).isEqualTo(owner + ":" + (i + 1) + "\n");
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(runDirectories()).isZero();
    }
}
