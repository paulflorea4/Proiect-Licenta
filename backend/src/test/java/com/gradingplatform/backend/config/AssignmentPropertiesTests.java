package com.gradingplatform.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 3.3a: the stand-in list of languages. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AssignmentPropertiesTests {

    @Autowired
    AssignmentProperties configured;

    @Test
    void theApplicationListsJavaAndPython() {
        assertThat(configured.languages()).containsExactly("JAVA", "PYTHON");
    }

    @Test
    void identifiersAreTrimmedUpperCasedAndDeduplicated() {
        AssignmentProperties properties = new AssignmentProperties(List.of(" java", "Java", "python ", ""));

        assertThat(properties.languages()).containsExactly("JAVA", "PYTHON");
    }

    @Test
    void aTypedLanguageMatchesWhateverItsCaseAndSpacing() {
        AssignmentProperties properties = new AssignmentProperties(List.of("JAVA", "PYTHON"));

        assertThat(properties.supports("java")).isTrue();
        assertThat(properties.supports("  Python ")).isTrue();
        assertThat(properties.supports("COBOL")).isFalse();
        assertThat(properties.supports("")).isFalse();
        assertThat(properties.supports(null)).isFalse();
    }

    @Test
    void upperCasingDoesNotDependOnTheDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            // In a Turkish locale "i".toUpperCase() is a dotted capital I, which would not match.
            assertThat(new AssignmentProperties(List.of("java", "python")).supports("python"))
                    .isTrue();
            assertThat(AssignmentProperties.normalize("id")).isEqualTo("ID");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void anEmptyListStopsTheApplication() {
        assertThatThrownBy(() -> new AssignmentProperties(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentProperties(Arrays.asList(" ", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
