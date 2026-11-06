package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.LanguagesResponse;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `GET /languages` (9.2b, the human's choice over a free-text field): the configured languages for
 * the assignment form. Teachers and admins only, the people who create and change assignments; a
 * student sees an assignment's language on the assignment itself.
 */
@RestController
public class LanguageController {

    private final SandboxProperties properties;

    public LanguageController(SandboxProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/languages")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public LanguagesResponse languages() {
        return LanguagesResponse.of(properties);
    }
}
