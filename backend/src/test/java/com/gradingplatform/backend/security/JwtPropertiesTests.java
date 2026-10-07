package com.gradingplatform.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** 2.4a: a missing or weak signing secret stops the application at startup. */
class JwtPropertiesTests {

    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class);

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Props {}

    @Test
    void aSecretOfAtLeast32CharactersAndAPositiveExpiryStartsFine() {
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET, "jwt.expiration=24h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertThat(properties.secret()).isEqualTo(GOOD_SECRET);
                    assertThat(properties.expiration()).isEqualTo(Duration.ofHours(24));
                });
    }

    @Test
    void aMissingSecretFailsStartup() {
        // Surefire sets JWT_SECRET in the environment (like production), and a context runner reads
        // it, so take the environment out to really have no secret.
        runner.withInitializer(
                        context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
                .withPropertyValues("jwt.expiration=24h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aBlankSecretFailsStartup() {
        runner.withPropertyValues("jwt.secret=   ", "jwt.expiration=24h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aSecretShorterThan32CharactersFailsStartup() {
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET.substring(1), "jwt.expiration=24h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingExpiryFailsStartup() {
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroOrNegativeExpiryFailsStartup() {
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET, "jwt.expiration=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("jwt.secret=" + GOOD_SECRET, "jwt.expiration=-1h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aRejectedSecretIsNeverEchoedInTheStartupError() {
        String tooShort = "almost-long-enough-0123456789";

        runner.withPropertyValues("jwt.secret=" + tooShort, "jwt.expiration=24h")
                .run(context -> {
                    assertThat(context).hasFailed();
                    for (Throwable error = context.getStartupFailure(); error != null; error = error.getCause()) {
                        assertThat(String.valueOf(error.getMessage()))
                                .as(error.getClass().getName())
                                .doesNotContain(tooShort);
                    }
                });
    }

    @Test
    void theSecretIsNeverPrinted() {
        JwtProperties properties = new JwtProperties(GOOD_SECRET, Duration.ofHours(1));

        assertThat(properties.toString()).doesNotContain(GOOD_SECRET);
    }

    @Test
    void theConstructorRejectsANonPositiveExpiry() {
        assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
