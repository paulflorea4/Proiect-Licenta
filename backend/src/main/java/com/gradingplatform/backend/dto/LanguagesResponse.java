package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.sandbox.SandboxProperties;
import java.util.List;

/**
 * `GET /languages` (9.2b): the languages an assignment may use, in the order of the config, so the
 * teacher's form can offer them without the frontend naming any (adding a language stays a config
 * entry, 4.1b). Each with the smallest memory limit it accepts ({@code minMemoryMb}, 7.1c and 7.7f).
 * Images and commands are not shown: they are the server's business.
 */
public record LanguagesResponse(List<Language> languages) {

    public record Language(String id, int minMemoryMb) {}

    public static LanguagesResponse of(SandboxProperties properties) {
        return new LanguagesResponse(properties.ids().stream()
                .map(id -> new Language(id, properties.find(id).orElseThrow().effectiveMinMemoryMb()))
                .toList());
    }
}
