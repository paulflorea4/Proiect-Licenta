package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 4.1b: the languages config, as written in `application.yml` and as the loader treats it. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SandboxPropertiesTests {

    @Autowired
    SandboxProperties configured;

    // --- the real file ------------------------------------------------------------------------

    @Test
    void theApplicationConfiguresJavaAndPythonInThatOrder() {
        assertThat(configured.ids()).containsExactly("JAVA", "PYTHON");
    }

    @Test
    void javaIsCompiledWithJavacAndRunFromTheWorkingDirectory() {
        LanguageSpec java = configured.find("java").orElseThrow();

        assertThat(java.sourceFileName()).isEqualTo("Main.java");
        assertThat(java.compileCommand()).containsExactly("javac", "-encoding", "UTF-8", "Main.java");
        assertThat(java.runCommand()).containsExactly("java", "-cp", ".", "Main");
        assertThat(java.image()).isNotBlank();
    }

    @Test
    void pythonHasNoBuildStep() {
        LanguageSpec python = configured.find("Python").orElseThrow();

        assertThat(python.sourceFileName()).isEqualTo("main.py");
        assertThat(python.isCompiled()).isFalse();
        assertThat(python.runCommand()).containsExactly("python", "main.py");
    }

    @Test
    void everyConfiguredSourceFileIsAcceptedAsASandboxSourceFile() {
        for (String id : configured.ids()) {
            LanguageSpec spec = configured.find(id).orElseThrow();

            assertThat(new SourceFile(spec.sourceFileName(), "x").name()).isEqualTo(spec.sourceFileName());
        }
    }

    // --- the loader ---------------------------------------------------------------------------

    private static SandboxProperties of(String... ids) {
        Map<String, LanguageSpec> map = new LinkedHashMap<>();
        for (String id : ids) {
            map.put(id, TestLanguages.python());
        }
        return new SandboxProperties(map);
    }

    @Test
    void idsAreTrimmedAndUpperCased() {
        assertThat(of(" java", "python ").ids()).containsExactly("JAVA", "PYTHON");
    }

    @Test
    void twoKeysThatBecomeTheSameIdStopTheApplication() {
        assertThatThrownBy(() -> of("java", "JAVA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("java", " Java ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankIdOrMissingSettingsStopTheApplication() {
        assertThatThrownBy(() -> of(" ")).isInstanceOf(IllegalArgumentException.class);
        Map<String, LanguageSpec> noSpec = new LinkedHashMap<>();
        noSpec.put("JAVA", null);
        assertThatThrownBy(() -> new SandboxProperties(noSpec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noLanguagesAtAllStopsTheApplication() {
        assertThatThrownBy(() -> new SandboxProperties(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTypedLanguageMatchesWhateverItsCaseAndSpacing() {
        SandboxProperties properties = TestLanguages.javaAndPython();

        assertThat(properties.supports("java")).isTrue();
        assertThat(properties.supports("  Python ")).isTrue();
        assertThat(properties.supports("COBOL")).isFalse();
        assertThat(properties.supports("")).isFalse();
        assertThat(properties.supports(null)).isFalse();
        assertThat(properties.find("cobol")).isEmpty();
        assertThat(properties.find(null)).isEmpty();
    }

    @Test
    void theLanguageMapCannotBeChangedAfterwards() {
        Map<String, LanguageSpec> source = new LinkedHashMap<>(Map.of("JAVA", TestLanguages.java()));
        SandboxProperties properties = new SandboxProperties(source);

        source.put("EVIL", TestLanguages.python());

        assertThat(properties.ids()).containsExactly("JAVA");
        assertThatThrownBy(() -> properties.languages().put("EVIL", TestLanguages.python()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(List.copyOf(properties.languages().keySet())).containsExactly("JAVA");
    }

    @Test
    void upperCasingDoesNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // In a Turkish locale "i".toUpperCase() is a dotted capital I, which would not match.
            assertThat(of("pythoni").supports("pythoni")).isTrue();
            assertThat(SandboxProperties.normalize("id")).isEqualTo("ID");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
