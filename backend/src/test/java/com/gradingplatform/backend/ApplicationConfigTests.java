package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 2.1a: what `application.yml` says per profile. It reads the YAML documents directly instead of
 * booting a context, so the result does not depend on `SPRING_DATASOURCE_*` variables that happen
 * to be set on the machine running the tests (those always override the file).
 */
class ApplicationConfigTests {

    private final List<PropertySource<?>> documents = loadDocuments();

    @Test
    void thereIsOneConfigSourceNotAlsoAPropertiesFile() {
        assertThat(new ClassPathResource("application.properties").exists()).isFalse();
    }

    @Test
    void commonSettingsApplyToEveryProfile() {
        Map<String, Object> common = documentFor(null);

        assertThat(common)
                .containsEntry("spring.application.name", "backend")
                .containsEntry("spring.jpa.hibernate.ddl-auto", "validate");
    }

    @Test
    void commonSettingsDoNotConfigureADatasource() {
        assertThat(documentFor(null).keySet()).noneMatch(key -> key.startsWith("spring.datasource"));
    }

    @Test
    void devFallsBackToTheComposeUrlAndUsername() {
        assertThat(documentFor("dev"))
                .containsEntry("spring.datasource.url", "jdbc:postgresql://localhost:5432/grading")
                .containsEntry("spring.datasource.username", "grading");
    }

    @Test
    void devHasNoPasswordFallback() {
        assertThat(documentFor("dev")).doesNotContainKey("spring.datasource.password");
    }

    @Test
    void prodHasNoDatasourceFallbackSoMissingVariablesFailFast() {
        assertThat(documentFor("prod").keySet()).noneMatch(key -> key.startsWith("spring.datasource"));
    }

    @Test
    void onlyDevAndProdProfilesAreDefined() {
        List<Object> profiles = documents.stream()
                .map(source -> source.getProperty("spring.config.activate.on-profile"))
                .filter(profile -> profile != null)
                .toList();

        assertThat(profiles).containsExactlyInAnyOrder("dev", "prod");
    }

    /** The properties of the YAML document for a profile, or of the shared document for `null`. */
    private Map<String, Object> documentFor(String profile) {
        PropertySource<?> document = documents.stream()
                .filter(source ->
                        java.util.Objects.equals(source.getProperty("spring.config.activate.on-profile"), profile))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No YAML document for profile " + profile));
        EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) document;
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String name : enumerable.getPropertyNames()) {
            properties.put(name, enumerable.getProperty(name));
        }
        return properties;
    }

    private static List<PropertySource<?>> loadDocuments() {
        try {
            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
