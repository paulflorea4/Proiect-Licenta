package com.gradingplatform.backend.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The programming languages an assignment may use (3.3a), from `assignments.languages` in
 * `application.yml`. A stand-in: the real languages config (image, compile and run commands, source
 * file name) arrives with 4.1b, which takes over as the source of this list. Identifiers are
 * upper-case (`JAVA`, `PYTHON`), as stored in `assignments.language`.
 *
 * <p>An empty list stops the application at startup: no assignment could ever be created.
 *
 * @param languages upper-case identifiers, trimmed and de-duplicated, in the order given
 */
@ConfigurationProperties("assignments")
public record AssignmentProperties(List<String> languages) {

    public AssignmentProperties {
        List<String> cleaned = new ArrayList<>();
        if (languages != null) {
            for (String language : languages) {
                String id = language == null ? "" : normalize(language);
                if (!id.isEmpty() && !cleaned.contains(id)) {
                    cleaned.add(id);
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("assignments.languages must list at least one language");
        }
        languages = List.copyOf(cleaned);
    }

    /** The identifier a typed language is compared as: trimmed and upper-cased (locale independent). */
    public static String normalize(String typed) {
        return typed.trim().toUpperCase(Locale.ROOT);
    }

    public boolean supports(String typed) {
        return typed != null && languages.contains(normalize(typed));
    }
}
