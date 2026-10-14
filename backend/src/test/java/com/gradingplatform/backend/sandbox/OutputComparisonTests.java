package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 4.5b: the default comparison, as a pure function on strings, plus the rule that decides a run's pass.
 * The longer set of real-world edge cases (4.7c) builds on this.
 */
class OutputComparisonTests {

    private static final OutputComparison COMPARISON = TrailingWhitespaceComparison.INSTANCE;

    private static boolean matches(String expected, String actual) {
        return COMPARISON.matches(expected, actual);
    }

    private static SandboxResult result(SandboxStatus status, String stdout, boolean stdoutTruncated) {
        Integer exit = status == SandboxStatus.OK ? Integer.valueOf(0) : null;
        return new SandboxResult(status, exit, stdout, "", stdoutTruncated, false, Duration.ofMillis(5));
    }

    // --- equal after normalising ------------------------------------------------------------------

    @Test
    void identicalTextMatches() {
        assertThat(matches("7\n", "7\n")).isTrue();
        assertThat(matches("hello world", "hello world")).isTrue();
    }

    @Test
    void aMissingOrExtraFinalNewlineMakesNoDifference() {
        assertThat(matches("7\n", "7")).isTrue();
        assertThat(matches("7", "7\n")).isTrue();
        assertThat(matches("7\n", "7\n\n\n")).isTrue();
        assertThat(matches("1\n2\n", "1\n2")).isTrue();
    }

    @Test
    void trailingSpacesAndTabsOnEveryLineMakeNoDifference() {
        assertThat(matches("1\n2\n3\n", "1  \n2\t\n3 \t \n")).isTrue();
        assertThat(matches("a b", "a b   ")).isTrue();
    }

    @Test
    void windowsLineEndingsAreTheSameAsUnixOnes() {
        assertThat(matches("1\n2\n3\n", "1\r\n2\r\n3\r\n")).isTrue();
        assertThat(matches("1\r\n2\r\n", "1\n2\n")).isTrue();
        assertThat(matches("1\r\n2", "1\n2\n")).isTrue();
        assertThat(matches("1\n2\n", "1\r\n2\n")).isTrue(); // a mix
    }

    @Test
    void emptyOutputMatchesEmptyAndOnlyWhitespace() {
        assertThat(matches("", "")).isTrue();
        assertThat(matches("", "\n")).isTrue();
        assertThat(matches("", " \n\t\r\n  \n")).isTrue();
        assertThat(matches("\n\n", "")).isTrue();
    }

    @Test
    void aWhitespaceOnlyLastLineIsDropped() {
        assertThat(matches("a\nb", "a\nb\n   \n\t\n")).isTrue();
    }

    // --- still different --------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} vs {1}")
    @CsvSource(
            quoteCharacter = '"',
            value = {
                "\"7\", \"8\"",
                "\"hello\", \"Hello\"",
                "\"1 2\", \"1  2\"",
                "\"1 2\", \"12\"",
                "\"a\", \"\"",
                "\"\", \"a\"",
                "\"1\", \"1 1\"",
                "\"1\", \"1\\n1\""
            })
    void textThatDiffersInContentDoesNotMatch(String expected, String actual) {
        assertThat(matches(expected.replace("\\n", "\n"), actual.replace("\\n", "\n")))
                .isFalse();
    }

    @Test
    void leadingWhitespaceCounts() {
        assertThat(matches("1", " 1")).isFalse();
        assertThat(matches("1", "\t1")).isFalse();
        assertThat(matches("a\nb", "a\n b")).isFalse();
    }

    @Test
    void leadingAndInteriorBlankLinesCount() {
        assertThat(matches("1\n2", "\n1\n2")).isFalse();
        assertThat(matches("1\n2", "1\n\n2")).isFalse();
        assertThat(matches("1\n\n2", "1\n2")).isFalse();
    }

    @Test
    void theOrderOfLinesCounts() {
        assertThat(matches("1\n2", "2\n1")).isFalse();
    }

    @Test
    void aLoneCarriageReturnInsideALineIsAnOrdinaryCharacter() {
        assertThat(matches("ab", "a\rb")).isFalse();
        assertThat(matches("a\rb", "a\rb")).isTrue();
    }

    @Test
    void aNoBreakSpaceIsNotTrailingWhitespaceItIsAVisibleDifference() {
        assertThat(matches("a", "a ")).isFalse();
    }

    @Test
    void unicodeTextIsComparedAsIs() {
        assertThat(matches("ă€\n", "ă€")).isTrue();
        assertThat(matches("ă€", "a€")).isFalse();
    }

    // --- shape of the function --------------------------------------------------------------------

    @Test
    void normalizeGivesNoTrailingNewlineAndNoTrailingSpaces() {
        assertThat(TrailingWhitespaceComparison.normalize("a  \r\n\nb \t\n\n\n"))
                .isEqualTo("a\n\nb");
        assertThat(TrailingWhitespaceComparison.normalize("")).isEmpty();
        assertThat(TrailingWhitespaceComparison.normalize("\n\n")).isEmpty();
    }

    @Test
    void nullIsRefused() {
        assertThatThrownBy(() -> matches(null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> matches("", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void randomTextKeepsTheAlgebraOfTheRule() {
        Random random = new Random(45);
        String alphabet = "ab \t\r\n";
        for (int i = 0; i < 2_000; i++) {
            StringBuilder builder = new StringBuilder();
            for (int j = random.nextInt(20); j > 0; j--) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = builder.toString();
            String normal = TrailingWhitespaceComparison.normalize(text);

            assertThat(TrailingWhitespaceComparison.normalize(normal))
                    .as("idempotent: %s", text)
                    .isEqualTo(normal);
            assertThat(matches(text, text)).isTrue();
            assertThat(matches(text, normal))
                    .as("a text matches its normal form: %s", text)
                    .isTrue();
            assertThat(matches(normal, text + " \t\r\n\n"))
                    .as("trailing junk: %s", text)
                    .isTrue();
            assertThat(matches(text, normal + "x")).isFalse();
            assertThat(matches(normal + "x", text)).isFalse();
        }
    }

    // --- a run passes only if it completed cleanly -----------------------------------------------

    @Test
    void aCleanRunWithTheRightOutputPasses() {
        assertThat(COMPARISON.passes(result(SandboxStatus.OK, "7\r\n", false), "7\n"))
                .isTrue();
    }

    @Test
    void aCleanRunWithTheWrongOutputFails() {
        assertThat(COMPARISON.passes(result(SandboxStatus.OK, "8\n", false), "7\n"))
                .isFalse();
    }

    @Test
    void aCutOutputNeverPassesEvenWhenThePrefixEqualsTheExpectedText() {
        assertThat(COMPARISON.passes(result(SandboxStatus.OK, "7\n", true), "7\n"))
                .isFalse();
    }

    @Test
    void aRunThatDidNotFinishNormallyNeverPassesEvenWithTheRightOutput() {
        for (SandboxStatus status : SandboxStatus.values()) {
            if (status != SandboxStatus.OK) {
                assertThat(COMPARISON.passes(result(status, "7\n", false), "7\n"))
                        .as(status.name())
                        .isFalse();
            }
        }
    }

    // --- swappable --------------------------------------------------------------------------------

    @Test
    void aStricterRuleCanBeUsedInItsPlaceThroughTheInterface() {
        OutputComparison strict = String::equals;

        assertThat(strict.passes(result(SandboxStatus.OK, "7\n", false), "7\n")).isTrue();
        assertThat(strict.passes(result(SandboxStatus.OK, "7", false), "7\n")).isFalse();
        assertThat(strict.passes(result(SandboxStatus.OK, "7\n", true), "7\n")).isFalse(); // still the shared rule
    }
}
