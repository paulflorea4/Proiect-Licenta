package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 4.7c: the output comparison's edge cases, end to end. {@code OutputComparisonTests} (4.5b) has the rule
 * on plain strings; here real programs print each shape in real containers and the {@link
 * SubmissionTestRunner} bean, the path Phase 5 grades through, gives the verdict. Programs write raw bytes
 * ({@code sys.stdout.buffer}), so a {@code \r} is exactly where the test puts it.
 *
 * <p>Windows line endings are the likely real-world failure, from both sides: a program that prints
 * CRLF, and a teacher whose expected output (or input) was pasted on Windows and is stored byte for byte
 * (3.5a). Each case is one test of one suite, so a whole table costs one build.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxOutputComparisonTests {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(5_000, 128);

    private static final int CAP = DockerCliSandboxRunner.OUTPUT_LIMIT_BYTES;

    private static final String LF = "1\n2\n3\n";
    private static final String CRLF = "1\r\n2\r\n3\r\n";

    /** Reads a shape name from stdin and writes exactly those bytes to stdout. */
    private static final String PRINTER = """
            import sys
            out = sys.stdout.buffer
            CAP = %d
            shapes = {
                'lf': b'1\\n2\\n3\\n',
                'crlf': b'1\\r\\n2\\r\\n3\\r\\n',
                'crlf-no-final-newline': b'1\\r\\n2\\r\\n3',
                'mixed-endings': b'1\\r\\n2\\n3\\r\\n',
                'no-final-newline': b'1\\n2\\n3',
                'extra-final-newlines': b'1\\n2\\n3\\n\\n\\n',
                'extra-final-crlf-lines': b'1\\r\\n2\\r\\n3\\r\\n\\r\\n',
                'trailing-spaces': b'1 \\n2  \\n3   \\n',
                'trailing-tabs-and-crlf': b'1\\t\\r\\n2 \\t\\r\\n3\\r\\n',
                'leading-space': b' 1\\n2\\n3\\n',
                'interior-blank-line': b'1\\n\\n2\\n3\\n',
                'lone-cr': b'1\\r2\\n3\\n',
                'no-break-space': b'1\\xc2\\xa0\\n2\\n3\\n',
                'empty': b'',
                'only-newlines': b'\\n\\n',
                'only-whitespace': b' \\r\\n\\t\\n  ',
                'cap-exactly': b'a' * CAP,
                'cap-plus-one': b'a' * (CAP + 1),
                'answer-then-space-flood': b'7\\n' + b' ' * (2 * CAP),
                'answer-then-newline-flood': b'7' + b'\\n' * (2 * CAP),
            }
            mode = sys.stdin.read().strip()
            if mode == 'stderr-noise':
                sys.stderr.write('debug: x = 3\\n')
                out.write(shapes['lf'])
            elif mode == 'stderr-flood':
                sys.stderr.write('e' * (2 * CAP))
                out.write(shapes['lf'])
            else:
                out.write(shapes[mode])
            """.formatted(CAP);

    @Autowired
    SandboxProperties languages;

    @Autowired
    SubmissionTestRunner suite;

    @BeforeEach
    void theImagesExist() {
        SandboxTestImages.ensureBuilt(languages, "PYTHON");
        SandboxTestImages.ensureBuilt(languages, "JAVA");
    }

    /** One test case: what goes to stdin, what the teacher expects, and the verdict this rule must give. */
    private record Case(String input, String expected, TestOutcome outcome) {}

    private static Case pass(String shape, String expected) {
        return new Case(shape, expected, TestOutcome.PASSED);
    }

    private static Case fail(String shape, String expected) {
        return new Case(shape, expected, TestOutcome.FAILED);
    }

    /** Runs every case as one test of one suite and returns the results in the same order. */
    private List<TestRunResult> judge(String language, String fileName, String code, List<Case> cases) {
        List<TestCaseInput> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            tests.add(new TestCaseInput(i, cases.get(i).input(), cases.get(i).expected()));
        }
        return suite.runAll(language, List.of(new SourceFile(fileName, code)), LIMITS, tests);
    }

    private List<TestRunResult> judgePython(String code, List<Case> cases) {
        return judge("PYTHON", "main.py", code, cases);
    }

    private static void assertVerdicts(List<Case> cases, List<TestRunResult> results) {
        assertThat(results).hasSameSizeAs(cases);
        SoftAssertions softly = new SoftAssertions();
        for (int i = 0; i < cases.size(); i++) {
            Case test = cases.get(i);
            TestRunResult result = results.get(i);
            softly.assertThat(result.testCaseId()).isEqualTo(i);
            softly.assertThat(result.outcome())
                    .as(
                            "input %s against expected %s (run %s)",
                            visible(test.input()), visible(test.expected()), result.run())
                    .isEqualTo(test.outcome());
        }
        softly.assertAll();
    }

    /** Shows {@code \r}, {@code \n} and tabs in a failure message; long texts are summarised. */
    private static String visible(String text) {
        if (text.length() > 40) {
            return "<" + text.length() + " chars>";
        }
        return "\"" + text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    // --- Windows line endings ---------------------------------------------------------------------

    @Test
    void crlfAndLfAreTheSameWhicheverSideHasThem() {
        // Every pairing of a program's line endings with a teacher's: the program on Linux prints LF, a
        // Windows habit prints CRLF, and an expected output pasted on Windows is stored with CRLF.
        List<Case> cases = new ArrayList<>();
        for (String shape : List.of("lf", "crlf", "crlf-no-final-newline", "mixed-endings", "extra-final-crlf-lines")) {
            for (String expected : List.of(LF, CRLF, "1\r\n2\r\n3", "1\n2\n3")) {
                cases.add(pass(shape, expected));
            }
        }

        assertVerdicts(cases, judgePython(PRINTER, cases));
    }

    @Test
    void aCarriageReturnThatIsNotALineEndingIsAVisibleDifference() {
        List<Case> cases = List.of(fail("lone-cr", LF), fail("lone-cr", CRLF), pass("lone-cr", "1\r2\n3\n"));

        assertVerdicts(cases, judgePython(PRINTER, cases));
    }

    @Test
    void aTeachersWindowsInputReachesTheProgramByteForByte() {
        // Inputs are not normalised (3.5a stores them as typed). On Linux Python's stdin splits lines at
        // \n only, so input() keeps the \r; int() ignores it, a string comparison does not.
        String echo = """
                import sys
                data = sys.stdin.buffer.read()
                print(len(data), data.count(b'\\r'))
                """;
        String readLine = """
                line = input()
                print(repr(line), int(line.split()[1]) + 1)
                """;
        List<Case> bytes = List.of(pass("3 4\r\n1 2\r\n", "10 2\n"));
        List<Case> lines = List.of(pass("3 4\r\n", "'3 4\\r' 5\n"));

        assertVerdicts(bytes, judgePython(echo, bytes));
        assertVerdicts(lines, judgePython(readLine, lines));
    }

    @Test
    void aJavaSubmissionIsJudgedTheSameWayWithWindowsInputAndOutput() {
        // Java's line readers drop the \r of a CRLF input; print with a trailing space and println are
        // the usual shapes of a Java answer. One build for the whole table.
        String code = """
                import java.io.BufferedReader;
                import java.io.InputStreamReader;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                        String mode = in.readLine();
                        switch (mode) {
                            case "row":
                                for (int i = 1; i <= 3; i++) {
                                    System.out.print(i + " ");
                                }
                                System.out.println();
                                break;
                            case "crlf":
                                System.out.print("1 2 3\\r\\n");
                                break;
                            case "printf":
                                System.out.printf("%d %d %d%n", 1, 2, 3);
                                break;
                            default:
                                System.out.println("unknown " + mode);
                        }
                    }
                }
                """;
        List<Case> cases = new ArrayList<>();
        for (String mode : List.of("row", "row\r\n", "crlf", "printf\r\n")) {
            cases.add(pass(mode, "1 2 3\n"));
            cases.add(pass(mode, "1 2 3\r\n"));
            cases.add(fail(mode, "1 2 3 4\n"));
        }

        assertVerdicts(cases, judge("JAVA", "Main.java", code, cases));
    }

    // --- trailing whitespace ----------------------------------------------------------------------

    @Test
    void trailingWhitespaceAndFinalNewlinesMakeNoDifference() {
        List<Case> cases = List.of(
                pass("trailing-spaces", LF),
                pass("trailing-spaces", "1   \n2\n3"),
                pass("trailing-tabs-and-crlf", LF),
                pass("no-final-newline", LF),
                pass("extra-final-newlines", LF),
                pass("lf", "1\n2\n3\n\n\n"),
                pass("lf", "1 \t\n2\n3 "));

        assertVerdicts(cases, judgePython(PRINTER, cases));
    }

    @Test
    void whitespaceThatIsNotTrailingStillCounts() {
        List<Case> cases = List.of(
                fail("leading-space", LF),
                fail("interior-blank-line", LF),
                fail("no-break-space", LF),
                fail("lf", "1 2 3\n"),
                pass("leading-space", " 1\n2\n3"),
                pass("interior-blank-line", "1\n\n2\n3"));

        assertVerdicts(cases, judgePython(PRINTER, cases));
    }

    // --- empty output -----------------------------------------------------------------------------

    @Test
    void emptyOutputMatchesOnlyAnEmptyExpectation() {
        List<Case> cases = List.of(
                pass("empty", ""),
                pass("empty", "\n"),
                pass("empty", "\r\n"),
                pass("only-newlines", ""),
                pass("only-whitespace", ""),
                pass("only-whitespace", "\n"),
                fail("empty", "0\n"),
                fail("empty", LF),
                fail("only-whitespace", "0"),
                fail("lf", ""));

        assertVerdicts(cases, judgePython(PRINTER, cases));
    }

    @Test
    void aProgramThatPrintsNothingAndCrashesIsNotAnEmptyAnswer() {
        List<TestRunResult> results =
                judgePython("raise SystemExit(3)\n", List.of(new Case("", "", TestOutcome.RUNTIME_ERROR)));

        assertThat(results.get(0).outcome()).isEqualTo(TestOutcome.RUNTIME_ERROR);
        assertThat(results.get(0).run().stdout()).isEmpty();
    }

    // --- stderr is not compared -------------------------------------------------------------------

    @Test
    void whatAProgramWritesToStderrIsNeverCompared() {
        List<Case> cases = List.of(pass("stderr-noise", LF), pass("stderr-flood", LF), fail("stderr-noise", ""));

        List<TestRunResult> results = judgePython(PRINTER, cases);

        assertVerdicts(cases, results);
        assertThat(results.get(1).run().stderrTruncated()).isTrue();
    }

    // --- truncated output -------------------------------------------------------------------------

    @Test
    void outputOfExactlyTheCapIsWholeAndPasses() {
        String expected = "a".repeat(CAP);

        List<TestRunResult> results = judgePython(PRINTER, List.of(pass("cap-exactly", expected)));

        assertThat(results.get(0).run().stdoutTruncated()).isFalse();
        assertThat(results.get(0).outcome()).isEqualTo(TestOutcome.PASSED);
    }

    @Test
    void aCutOutputFailsEvenWhenWhatWasKeptIsExactlyTheExpectedText() {
        // The program printed one byte more than the cap; the kept part equals the expected text, so only
        // the "completed cleanly" half of the rule stops a pass.
        String expected = "a".repeat(CAP);

        List<TestRunResult> results = judgePython(PRINTER, List.of(fail("cap-plus-one", expected)));

        SandboxResult run = results.get(0).run();
        assertThat(run.status()).isEqualTo(SandboxStatus.OK);
        assertThat(run.stdoutTruncated()).isTrue();
        assertThat(run.stdout()).isEqualTo(expected);
        assertThat(results.get(0).outcome()).isEqualTo(TestOutcome.FAILED);
    }

    @Test
    void theRightAnswerFollowedByAFloodOfWhitespaceIsCutAndFails() {
        // After normalising, what was kept would match "7": trailing whitespace is ignored only in an
        // output that arrived whole. A program cannot pass by printing past the cap.
        List<Case> cases = List.of(fail("answer-then-space-flood", "7\n"), fail("answer-then-newline-flood", "7\n"));

        List<TestRunResult> results = judgePython(PRINTER, cases);

        assertVerdicts(cases, results);
        for (TestRunResult result : results) {
            assertThat(result.run().stdoutTruncated()).isTrue();
            assertThat(TrailingWhitespaceComparison.INSTANCE.matches(
                            "7\n", result.run().stdout()))
                    .as("the kept part alone would have matched")
                    .isTrue();
        }
    }
}
