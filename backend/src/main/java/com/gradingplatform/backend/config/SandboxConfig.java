package com.gradingplatform.backend.config;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SandboxProperties}, the languages config, and checks it against what an
 * assignment may say (7.7g): a language no assignment could use stops startup instead of being listed
 * as supported. The check is here, not in the sandbox package, which knows nothing of the API's
 * limits.
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {

    public SandboxConfig(SandboxProperties languages) {
        requireUsableByAssignments(languages);
    }

    /**
     * Every language id must fit an assignment's {@code language} ({@link AssignmentRequest#LANGUAGE_MAX}
     * characters, the size of the column too), and every {@code min-memory-mb} must leave a memory
     * limit an assignment may have ({@link AssignmentRequest#MEMORY_LIMIT_MAX_MB}).
     *
     * @throws IllegalStateException naming the language and the setting when one does not
     */
    static void requireUsableByAssignments(SandboxProperties languages) {
        languages.languages().forEach((id, spec) -> {
            if (id.length() > AssignmentRequest.LANGUAGE_MAX) {
                throw new IllegalStateException("sandbox.languages: the id " + id + " is longer than "
                        + AssignmentRequest.LANGUAGE_MAX + " characters, so no assignment could use it");
            }
            if (spec.minMemoryMb() != null && spec.minMemoryMb() > AssignmentRequest.MEMORY_LIMIT_MAX_MB) {
                throw new IllegalStateException("sandbox.languages." + id + ".min-memory-mb is above "
                        + AssignmentRequest.MEMORY_LIMIT_MAX_MB + ", the largest memory limit an assignment may have");
            }
        });
    }
}
