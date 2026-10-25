package com.gradingplatform.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.config.AiServiceConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 7.1a: where the AI service is and its token are required at startup; the token is never printed. */
class AiServicePropertiesTests {

    private static final String TOKEN = "a-shared-secret-for-the-ai-service";

    // Surefire sets AI_SERVICE_URL and AI_SERVICE_TOKEN in the environment (like production), and a
    // context runner reads it, so the environment is taken out to control every value here.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(
                    context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
            .withUserConfiguration(AiServiceConfig.class)
            .withPropertyValues("ai.service.connect-timeout=5s", "ai.service.read-timeout=70s");

    @Test
    void aUrlAndATokenStartFineAndGiveTheClient() {
        runner.withPropertyValues("ai.service.url=http://localhost:8000", "ai.service.token=" + TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AiServiceClient.class);
                    AiServiceProperties properties = context.getBean(AiServiceProperties.class);
                    assertThat(properties.url()).isEqualTo(URI.create("http://localhost:8000"));
                    assertThat(properties.token()).isEqualTo(TOKEN);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(70));
                });
    }

    @Test
    void theEnvironmentVariablesBindToTheProperties() {
        // AI_SERVICE_URL / AI_SERVICE_TOKEN are what backend/.env.example and the deployment set; the
        // real environment is replaced by one holding just these.
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .replace(
                                "systemEnvironment",
                                new SystemEnvironmentPropertySource(
                                        "systemEnvironment",
                                        Map.of(
                                                "AI_SERVICE_URL", "https://ai.internal:9000",
                                                "AI_SERVICE_TOKEN", TOKEN,
                                                "AI_SERVICE_CONNECTTIMEOUT", "2s",
                                                "AI_SERVICE_READTIMEOUT", "90s"))))
                .withUserConfiguration(AiServiceConfig.class)
                .run(context -> {
                    AiServiceProperties properties = context.getBean(AiServiceProperties.class);
                    assertThat(properties.url()).isEqualTo(URI.create("https://ai.internal:9000"));
                    assertThat(properties.token()).isEqualTo(TOKEN);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void aMissingUrlFailsStartup() {
        runner.withPropertyValues("ai.service.token=" + TOKEN)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("AI_SERVICE_URL"));
    }

    @Test
    void aMissingTokenFailsStartup() {
        runner.withPropertyValues("ai.service.url=http://localhost:8000")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("AI_SERVICE_TOKEN"));
    }

    @Test
    void aBlankTokenFailsStartup() {
        runner.withPropertyValues("ai.service.url=http://localhost:8000", "ai.service.token=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost:8000",
                "ftp://localhost:8000",
                "file:///tmp/ai",
                "/feedback",
                "http://localhost:8000?debug=1",
                "http://localhost:8000#top",
            })
    void aUrlThatIsNotAPlainHttpAddressFailsStartup(String url) {
        runner.withPropertyValues("ai.service.url=" + url, "ai.service.token=" + TOKEN)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void aConnectTimeoutThatIsNotPositiveFailsStartup(String timeout) {
        runner.withPropertyValues(
                        "ai.service.url=http://localhost:8000",
                        "ai.service.token=" + TOKEN,
                        "ai.service.connect-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void aReadTimeoutThatIsNotPositiveFailsStartup(String timeout) {
        runner.withPropertyValues(
                        "ai.service.url=http://localhost:8000",
                        "ai.service.token=" + TOKEN,
                        "ai.service.read-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aTrailingSlashIsDroppedAndAPathPrefixKept() {
        assertThat(properties("http://localhost:8000/").url()).isEqualTo(URI.create("http://localhost:8000"));
        assertThat(properties("https://gateway.example/ai/").url()).isEqualTo(URI.create("https://gateway.example/ai"));
    }

    @Test
    void theTokenIsNeverPrinted() {
        AiServiceProperties properties = properties("http://localhost:8000");

        assertThat(properties.toString()).doesNotContain(TOKEN).contains("<redacted>");
    }

    @Test
    void aFailedStartupDoesNotPrintTheToken() {
        runner.withPropertyValues("ai.service.url=not a url at all", "ai.service.token=" + TOKEN)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain(TOKEN);
                });
    }

    private static AiServiceProperties properties(String url) {
        return new AiServiceProperties(URI.create(url), TOKEN, Duration.ofSeconds(5), Duration.ofSeconds(70));
    }

    private static String stackTraceOf(Throwable failure) {
        var out = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
