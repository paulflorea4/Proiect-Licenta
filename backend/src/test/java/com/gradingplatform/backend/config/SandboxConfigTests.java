package com.gradingplatform.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.AssignmentRequest;
import com.gradingplatform.backend.sandbox.LanguageSpec;
import com.gradingplatform.backend.sandbox.SandboxProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 7.7g: a languages config that no assignment could use stops startup. The real {@link SandboxConfig}
 * binds the given properties, as the application does with {@code application.yml}.
 */
class SandboxConfigTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SandboxConfig.class);

    /** One language with a run command, plus any extra settings ({@code key=value} under the language). */
    private static String[] language(String id, String... extra) {
        String prefix = "sandbox.languages." + id + ".";
        List<String> properties = new ArrayList<>(List.of(
                prefix + "image=grading-sandbox-python:dev",
                prefix + "source-file-name=main.py",
                prefix + "run-command[0]=python",
                prefix + "run-command[1]=main.py"));
        for (String setting : extra) {
            properties.add(prefix + setting);
        }
        return properties.toArray(String[]::new);
    }

    @Test
    void aLanguageWithinTheAssignmentLimitsStarts() {
        runner.withPropertyValues(language("PYTHON", "min-memory-mb=" + AssignmentRequest.MEMORY_LIMIT_MAX_MB))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LanguageSpec python = context.getBean(SandboxProperties.class)
                            .find("PYTHON")
                            .orElseThrow();
                    assertThat(python.allowsMemoryLimit(AssignmentRequest.MEMORY_LIMIT_MAX_MB))
                            .isTrue();
                });
    }

    @Test
    void aMinimumAboveTheLargestAssignmentLimitStopsStartupNamingTheLanguage() {
        runner.withPropertyValues(language("PYTHON", "min-memory-mb=" + (AssignmentRequest.MEMORY_LIMIT_MAX_MB + 1)))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("sandbox.languages.PYTHON.min-memory-mb")
                            .hasMessageContaining(String.valueOf(AssignmentRequest.MEMORY_LIMIT_MAX_MB));
                });
    }

    @Test
    void anIdAtTheLongestAnAssignmentTakesStarts() {
        String id = "L".repeat(AssignmentRequest.LANGUAGE_MAX);

        runner.withPropertyValues(language(id))
                .run(context -> assertThat(
                                context.getBean(SandboxProperties.class).ids())
                        .containsExactly(id));
    }

    @Test
    void anIdLongerThanAnAssignmentTakesStopsStartupNamingIt() {
        String id = "L".repeat(AssignmentRequest.LANGUAGE_MAX + 1);

        runner.withPropertyValues(language(id)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(id)
                    .hasMessageContaining(String.valueOf(AssignmentRequest.LANGUAGE_MAX));
        });
    }

    @Test
    void oneUnusableLanguageAmongUsableOnesStillStopsStartup() {
        List<String> properties = new ArrayList<>(List.of(language("PYTHON")));
        properties.addAll(List.of(language("JAVA", "min-memory-mb=4096")));

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("sandbox.languages.JAVA.min-memory-mb"));
    }

    @Test
    void theSandboxFloorLeavesRoomBelowTheLargestLimit() {
        // The application's own file passes: every @SpringBootTest starts with it. The other constant
        // this rests on is the sandbox's floor (7.7f), which must not exceed the largest limit either.
        assertThat(LanguageSpec.SANDBOX_MIN_MEMORY_MB).isLessThanOrEqualTo(AssignmentRequest.MEMORY_LIMIT_MAX_MB);
    }
}
