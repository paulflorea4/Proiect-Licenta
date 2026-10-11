package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 4.1b: adding a language must be a config entry plus an image, never a code branch. The cheapest
 * way a branch sneaks in is a language id written as a string literal in production code, so this
 * fails when one appears. (Tests and `application.yml` may name languages; the code may not.)
 */
class NoLanguageBranchTests {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern LANGUAGE_LITERAL = Pattern.compile(
            "\"\\s*(java|python|javascript|c\\+\\+|cpp|kotlin|go|rust)\\s*\"", Pattern.CASE_INSENSITIVE);

    @Test
    void noProductionClassNamesALanguageInAStringLiteral() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> LANGUAGE_LITERAL.matcher(read(path)).find())
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("language ids belong in sandbox.languages (application.yml), not in code")
                .isEmpty();
    }

    @Test
    void theGuardFindsALiteralWhenThereIsOne() {
        assertThat(LANGUAGE_LITERAL.matcher("if (lang.equals(\"JAVA\")) {").find())
                .isTrue();
        assertThat(LANGUAGE_LITERAL.matcher("x = \" python \";").find()).isTrue();
        assertThat(LANGUAGE_LITERAL.matcher("\"javac\" and \"Main.java\"").find())
                .isFalse();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
