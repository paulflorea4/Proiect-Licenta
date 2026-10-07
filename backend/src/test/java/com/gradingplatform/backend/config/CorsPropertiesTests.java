package com.gradingplatform.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/** 2.4d: the allow-list is read from `CORS_ALLOWED_ORIGINS`, and a bad entry stops startup. */
class CorsPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Configuration
    @EnableConfigurationProperties(CorsProperties.class)
    static class Props {}

    @Test
    void aCommaSeparatedListBecomesOneOriginEach() {
        runner.withPropertyValues("cors.allowed-origins=http://localhost:5173,https://app.example.com")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173", "https://app.example.com"));
    }

    @Test
    void theEnvironmentVariableNameBindsByRelaxedBinding() {
        runner.withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                "testEnvironment",
                                Map.of("CORS_ALLOWED_ORIGINS", "http://localhost:5173,https://app.example.com"))))
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173", "https://app.example.com"));
    }

    @Test
    void spacesAreTrimmedEmptyEntriesIgnoredAndDuplicatesDropped() {
        runner.withPropertyValues("cors.allowed-origins= http://localhost:5173 ,, http://localhost:5173,")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173"));
    }

    @Test
    void unsetMeansNoOriginIsAllowed() {
        runner.withInitializer(
                        context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .isEmpty());
    }

    @Test
    void anExplicitlyEmptyValueMeansNoOriginIsAllowed() {
        runner.withPropertyValues("cors.allowed-origins=")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "http://*.example.com",
                "localhost:5173",
                "http://localhost:5173/",
                "http://localhost:5173/app",
                "http://localhost:5173?x=1",
                "http://localhost:5173#top",
                "ftp://example.com",
                "http://user@example.com",
                "http://exa mple.com",
                "null"
            })
    void anEntryThatIsNotAPlainHttpOriginIsRejected(String value) {
        assertThatThrownBy(() -> new CorsProperties(List.of("http://localhost:5173", value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void aBadEntryFailsStartup() {
        runner.withPropertyValues("cors.allowed-origins=http://localhost:5173,*")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNullEntryIsIgnored() {
        assertThat(new CorsProperties(Arrays.asList("http://localhost:5173", null)).allowedOrigins())
                .containsExactly("http://localhost:5173");
    }

    @Test
    void theConfigurationNeverAllowsCredentialsOrAWildcard() {
        var source = new CorsConfig().corsConfigurationSource(new CorsProperties(List.of("http://localhost:5173")));
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/auth/me"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowedHeaders()).doesNotContain("*");
    }

    @Test
    void withNoConfiguredOriginsTheConfigurationAllowsNone() {
        var source = new CorsConfig().corsConfigurationSource(new CorsProperties(null));
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/auth/me"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cors.checkOrigin("https://evil.example")).isNull();
    }
}
