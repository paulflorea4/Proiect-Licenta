package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 4.1b: one language's settings hold their rules before anything uses them. */
class LanguageSpecTests {

    private static final List<String> RUN = List.of("python", "main.py");

    @Test
    void aCompiledLanguageHasABuildStepAndAnInterpretedOneDoesNot() {
        assertThat(TestLanguages.java().isCompiled()).isTrue();
        assertThat(TestLanguages.python().isCompiled()).isFalse();
        assertThat(new LanguageSpec("img", "main.py", null, RUN).compileCommand())
                .isEmpty();
    }

    @Test
    void theCommandsAreCopiedAndCannotBeChanged() {
        List<String> run = new ArrayList<>(RUN);
        LanguageSpec spec = new LanguageSpec("img", "main.py", List.of(), run);

        run.add("--evil");

        assertThat(spec.runCommand()).isEqualTo(RUN);
        assertThatThrownBy(() -> spec.runCommand().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRunCommandIsRequired() {
        assertThatThrownBy(() -> new LanguageSpec("img", "main.py", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageSpec("img", "main.py", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noCommandElementMayBeBlankOrMissing() {
        assertThatThrownBy(() -> new LanguageSpec("img", "main.py", List.of(), List.of("python", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageSpec("img", "main.py", List.of(""), RUN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageSpec("img", "main.py", List.of(), Arrays.asList("python", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-v", "--privileged", "a b", "img\n--privileged"})
    void anImageNameThatCouldBeReadAsAnOptionOrSplitIsRefused(String image) {
        assertThatThrownBy(() -> new LanguageSpec(image, "main.py", List.of(), RUN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anImageNameIsRequired() {
        assertThatThrownBy(() -> new LanguageSpec(null, "main.py", List.of(), RUN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new LanguageSpec("registry.example.com:5000/team/img:1.2", "main.py", List.of(), RUN))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../Main.java", "dir/Main.java", "..", "a\\b"})
    void theSourceFileMustBeAPlainFileName(String name) {
        assertThatThrownBy(() -> new LanguageSpec("img", name, List.of(), RUN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingSourceFileNameIsRefused() {
        assertThatThrownBy(() -> new LanguageSpec("img", null, List.of(), RUN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
