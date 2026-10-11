package com.gradingplatform.backend.sandbox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The languages the sandbox can run (4.1b), from {@code sandbox.languages} in {@code application.yml}
 * and the one source of which languages an assignment may use (it replaced 3.3a's stand-in list).
 * Each key is a language id, upper-case ({@code JAVA}, {@code PYTHON}), as stored in
 * {@code assignments.language}; keys are trimmed and upper-cased, and two keys that become the same id
 * stop the application at startup. An empty map stops it too: no assignment could ever be created.
 *
 * @param languages id to how the language is built and run, in the order given
 */
@ConfigurationProperties("sandbox")
public record SandboxProperties(Map<String, LanguageSpec> languages) {

    public SandboxProperties {
        Map<String, LanguageSpec> cleaned = new LinkedHashMap<>();
        if (languages != null) {
            languages.forEach((key, spec) -> {
                String id = key == null ? "" : normalize(key);
                if (id.isEmpty()) {
                    throw new IllegalArgumentException("a language id must not be blank");
                }
                if (spec == null) {
                    throw new IllegalArgumentException("language " + id + " has no settings");
                }
                if (cleaned.put(id, spec) != null) {
                    throw new IllegalArgumentException("language " + id + " is defined twice");
                }
            });
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("sandbox.languages must define at least one language");
        }
        languages = Collections.unmodifiableMap(cleaned);
    }

    /** The identifier a typed language is compared as: trimmed and upper-cased (locale independent). */
    public static String normalize(String typed) {
        return typed.trim().toUpperCase(Locale.ROOT);
    }

    /** The supported language ids, in the order of the config. */
    public List<String> ids() {
        return List.copyOf(languages.keySet());
    }

    public boolean supports(String typed) {
        return find(typed).isPresent();
    }

    /** How to build and run a typed language (any case or spacing), or empty when it is not configured. */
    public Optional<LanguageSpec> find(String typed) {
        return typed == null ? Optional.empty() : Optional.ofNullable(languages.get(normalize(typed)));
    }
}
