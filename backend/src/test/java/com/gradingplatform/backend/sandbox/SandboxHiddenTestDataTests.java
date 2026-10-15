package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.7d: hidden test data never exists inside the container. A test's input reaches the program only on
 * stdin, one test per container, and its expected output never leaves the backend: the comparison happens
 * outside the sandbox ({@link SubmissionTestRunner}, {@link OutputComparison}). So a submission that
 * searches for the tests finds nothing, which is what keeps hidden tests hidden.
 *
 * <p>The submission here is a spy that searches for every test's input and expected output. It reads its
 * stdin, then reads every regular file it can reach from {@code /} (all of it, in chunks; {@code /proc}
 * and {@code /sys} are kernel views and are skipped, except for the command line and environment of every
 * visible process, which are read on purpose), and reports what it found where. The markers are written
 * into its source as hex, so the source file itself does not contain them. Two positive controls show
 * the search would see test data if it were there: a marker written literally in a comment of the source
 * must be found in {@code /work}, and the spy's copy of its own stdin must be found in {@code /tmp}.
 *
 * <p>The runner cannot tell public from hidden tests ({@link TestCaseInput} has no visibility), so every
 * test here stands for a hidden one. Each run must see exactly its own input on stdin and nothing of the
 * other tests, nor any expected output, anywhere.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxHiddenTestDataTests {

    /** Reading the whole image takes a few seconds; the limit only has to leave room for it. */
    private static final ExecutionLimits LIMITS = new ExecutionLimits(60_000, 256);

    /** Placeholders: {@code @@SOURCE_MARKER@@} (literal) and {@code @@NEEDLES@@} (label/hex pairs). */
    private static final String PYTHON_SPY = """
            # @@SOURCE_MARKER@@
            import os, sys
            NEEDLES = {label: bytes.fromhex(h) for label, h in [@@NEEDLES@@]}
            OVERLAP = max(len(n) for n in NEEDLES.values())
            found = set()
            counts = {'scanned': 0, 'unreadable': 0}

            def scan(path):
                try:
                    with open(path, 'rb') as f:
                        tail = b''
                        while True:
                            chunk = f.read(1 << 20)
                            if not chunk:
                                break
                            data = tail + chunk
                            for label, needle in NEEDLES.items():
                                if needle in data:
                                    found.add(label + ':' + path)
                            tail = data[-OVERLAP:]
                    counts['scanned'] += 1
                except OSError:
                    counts['unreadable'] += 1

            stdin = sys.stdin.buffer.read()
            for label, needle in NEEDLES.items():
                if needle in stdin:
                    print('stdin-has=' + label)
            with open('/tmp/stdin-copy', 'wb') as f:
                f.write(stdin)

            for root, dirs, files in os.walk('/', onerror=lambda e: counts.update(unreadable=counts['unreadable'] + 1)):
                if root == '/':
                    dirs[:] = [d for d in dirs if d not in ('proc', 'sys')]
                for name in files:
                    path = os.path.join(root, name)
                    if not os.path.islink(path) and os.path.isfile(path):
                        scan(path)
            for pid in os.listdir('/proc'):
                if pid.isdigit():
                    scan('/proc/' + pid + '/cmdline')
                    scan('/proc/' + pid + '/environ')
            for key, value in os.environ.items():
                for label, needle in NEEDLES.items():
                    if needle in (key + '=' + value).encode():
                        found.add(label + ':env')

            print('work=' + ','.join(sorted(os.listdir('/work'))))
            print('scanned=' + str(counts['scanned']))
            print('unreadable=' + str(counts['unreadable']))
            for entry in sorted(found):
                print('found=' + entry)
            """;

    /** The same spy in Java, so the compiled-language path (build container, class files) is covered. */
    private static final String JAVA_SPY = """
            // @@SOURCE_MARKER@@
            import java.io.IOException;
            import java.io.InputStream;
            import java.nio.file.DirectoryStream;
            import java.nio.file.FileVisitResult;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.SimpleFileVisitor;
            import java.nio.file.attribute.BasicFileAttributes;
            import java.util.HexFormat;
            import java.util.LinkedHashMap;
            import java.util.Map;
            import java.util.Set;
            import java.util.TreeSet;

            public class Main {
                static final String[][] NEEDLE_HEX = {@@NEEDLES@@};
                static final Map<String, byte[]> needles = new LinkedHashMap<>();
                static final Set<String> found = new TreeSet<>();
                static int scanned, unreadable, overlap;
                static byte[] buffer;

                static boolean contains(byte[] data, int length, byte[] needle) {
                    outer:
                    for (int i = 0; i + needle.length <= length; i++) {
                        for (int j = 0; j < needle.length; j++) {
                            if (data[i + j] != needle[j]) continue outer;
                        }
                        return true;
                    }
                    return false;
                }

                static void scan(Path file) {
                    try (InputStream in = Files.newInputStream(file)) {
                        int kept = 0;
                        int read;
                        while ((read = in.read(buffer, kept, buffer.length - kept)) > 0) {
                            int length = kept + read;
                            for (Map.Entry<String, byte[]> needle : needles.entrySet()) {
                                if (contains(buffer, length, needle.getValue())) found.add(needle.getKey() + ":" + file);
                            }
                            kept = Math.min(overlap, length);
                            System.arraycopy(buffer, length - kept, buffer, 0, kept);
                        }
                        scanned++;
                    } catch (IOException | RuntimeException e) {
                        unreadable++;
                    }
                }

                public static void main(String[] args) throws IOException {
                    for (String[] pair : NEEDLE_HEX) needles.put(pair[0], HexFormat.of().parseHex(pair[1]));
                    for (byte[] needle : needles.values()) overlap = Math.max(overlap, needle.length);
                    buffer = new byte[(1 << 20) + overlap];

                    byte[] stdin = System.in.readAllBytes();
                    for (Map.Entry<String, byte[]> needle : needles.entrySet()) {
                        if (contains(stdin, stdin.length, needle.getValue())) System.out.println("stdin-has=" + needle.getKey());
                    }
                    Files.write(Path.of("/tmp/stdin-copy"), stdin);

                    Files.walkFileTree(Path.of("/"), new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                            String name = dir.toString();
                            return name.equals("/proc") || name.equals("/sys")
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (attributes.isRegularFile()) scan(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            unreadable++;
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"))) {
                        for (Path process : processes) {
                            if (process.getFileName().toString().chars().allMatch(Character::isDigit)) {
                                scan(process.resolve("cmdline"));
                                scan(process.resolve("environ"));
                            }
                        }
                    }
                    for (Map.Entry<String, String> variable : System.getenv().entrySet()) {
                        byte[] text = (variable.getKey() + "=" + variable.getValue()).getBytes();
                        for (Map.Entry<String, byte[]> needle : needles.entrySet()) {
                            if (contains(text, text.length, needle.getValue())) found.add(needle.getKey() + ":env");
                        }
                    }

                    try (DirectoryStream<Path> work = Files.newDirectoryStream(Path.of("/work"))) {
                        Set<String> names = new TreeSet<>();
                        for (Path file : work) names.add(file.getFileName().toString());
                        System.out.println("work=" + String.join(",", names));
                    }
                    System.out.println("scanned=" + scanned);
                    System.out.println("unreadable=" + unreadable);
                    for (String entry : found) System.out.println("found=" + entry);
                }
            }
            """;

    @Autowired
    SandboxProperties languages;

    @Autowired
    SubmissionTestRunner suite;

    /** The tests of one assignment: unique markers in every input and every expected output. */
    private record Suite(List<TestCaseInput> tests, Map<String, String> needles, String sourceMarker) {

        static Suite of(int count) {
            String run = UUID.randomUUID().toString();
            List<TestCaseInput> tests = new ArrayList<>();
            Map<String, String> needles = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String input = "HIDDEN-INPUT-" + i + "-" + run;
                String expected = "EXPECTED-OUTPUT-" + i + "-" + run;
                tests.add(new TestCaseInput(100 + i, input + "\n", expected + "\n"));
                needles.put("input-" + i, input);
                needles.put("expected-" + i, expected);
            }
            String sourceMarker = "SOURCE-MARKER-" + run;
            needles.put("source", sourceMarker);
            return new Suite(List.copyOf(tests), needles, sourceMarker);
        }

        /** The spy's source: the needles as hex (so the file does not hold them), the source marker literally. */
        String spy(String template, String pairFormat) {
            String pairs = needles.entrySet().stream()
                    .map(e -> pairFormat.formatted(
                            e.getKey(), HexFormat.of().formatHex(e.getValue().getBytes(StandardCharsets.UTF_8))))
                    .collect(Collectors.joining(", "));
            return template.replace("@@SOURCE_MARKER@@", sourceMarker).replace("@@NEEDLES@@", pairs);
        }
    }

    private static List<String> values(SandboxResult result, String label) {
        return result.stdout()
                .lines()
                .filter(line -> line.startsWith(label + "="))
                .map(line -> line.substring(label.length() + 1))
                .toList();
    }

    private static String value(SandboxResult result, String label) {
        List<String> values = values(result, label);
        assertThat(values).as(label + " in: " + result.stdout()).hasSize(1);
        return values.get(0);
    }

    /**
     * Every run saw its own input on stdin and nothing else of the suite, anywhere; the search reached the
     * whole filesystem and found exactly the two planted controls.
     */
    private static void assertNoTestDataReached(
            Suite suite, List<TestRunResult> results, String sourcePath, Set<String> work) {
        assertThat(results).hasSize(suite.tests().size());
        for (int i = 0; i < results.size(); i++) {
            TestRunResult result = results.get(i);
            SandboxResult run = result.run();
            String what = "test " + i + ": " + run.stderr();

            assertThat(result.testCaseId()).isEqualTo(suite.tests().get(i).id());
            assertThat(run.status()).as(what).isEqualTo(SandboxStatus.OK);
            assertThat(run.stdoutTruncated()).isFalse();
            // the spy prints a report, not the answer, and the comparison outside the sandbox says so
            assertThat(result.outcome()).isEqualTo(TestOutcome.FAILED);

            assertThat(values(run, "stdin-has")).as("stdin of " + what).containsExactly("input-" + i);
            assertThat(new TreeSet<>(values(run, "found")))
                    .as("test data reachable by " + what)
                    .containsExactlyInAnyOrder("input-" + i + ":/tmp/stdin-copy", "source:" + sourcePath);
            assertThat(Set.of(value(run, "work").split(",")))
                    .as("/work of " + what)
                    .isEqualTo(work);
            assertThat(Integer.parseInt(value(run, "scanned")))
                    .as("files read by " + what)
                    .isGreaterThan(1_000);
        }
    }

    @Test
    void aPythonSubmissionThatSearchesEverythingItCanReachFindsNoTestData() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        Suite tests = Suite.of(3);
        String spy = tests.spy(PYTHON_SPY, "('%s', '%s')");

        List<TestRunResult> results =
                suite.runAll("PYTHON", List.of(new SourceFile("main.py", spy)), LIMITS, tests.tests());

        assertNoTestDataReached(tests, results, "/work/main.py", Set.of("main.py"));
    }

    @Test
    void aJavaSubmissionThatSearchesEverythingItCanReachFindsNoTestData() {
        SandboxTestImages.ensureBuilt(languages, "JAVA");
        Suite tests = Suite.of(2);
        String spy = tests.spy(JAVA_SPY, "{\"%s\", \"%s\"}");

        List<TestRunResult> results =
                suite.runAll("JAVA", List.of(new SourceFile("Main.java", spy)), LIMITS, tests.tests());

        // the build adds only class files (Main$1 is the spy's file visitor); javac keeps no comments, so the
        // marker is in the source alone
        assertNoTestDataReached(tests, results, "/work/Main.java", Set.of("Main.java", "Main.class", "Main$1.class"));
    }
}
