package com.gradingplatform.backend.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the AI service is and how to call it (7.1a). `url` and `token` come from the `AI_SERVICE_URL`
 * and `AI_SERVICE_TOKEN` environment variables (relaxed binding to `ai.service.*`) and have no default
 * anywhere: like `JWT_SECRET`, a missing value stops the application at startup instead of making
 * every hint request fail later. The timeouts default in `application.yml`.
 *
 * @param url the service's base URL, `http` or `https` with a host (a path prefix is allowed); a
 *     trailing slash is dropped
 * @param token the shared secret sent as `Authorization: Bearer <token>`, the same value as the AI
 *     service's own `AI_SERVICE_TOKEN`
 * @param connectTimeout how long to wait for the connection
 * @param readTimeout how long to wait for the answer once connected; it must outlast the AI service's
 *     own worst case (two Gemini attempts of up to 30 s each, 6.3c)
 */
@ConfigurationProperties("ai.service")
public record AiServiceProperties(URI url, String token, Duration connectTimeout, Duration readTimeout) {

    public AiServiceProperties {
        // Checked here, not with bean-validation annotations: Spring Boot prints the rejected value in
        // its startup error, which would write the token into the logs.
        if (url == null) {
            throw new IllegalArgumentException("ai.service.url (the AI_SERVICE_URL environment variable) must be set");
        }
        boolean http = "http".equals(url.getScheme()) || "https".equals(url.getScheme());
        if (!http || url.getHost() == null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("ai.service.url (the AI_SERVICE_URL environment variable) must be an "
                    + "http or https URL with a host, such as http://localhost:8000, but is '" + url + "'");
        }
        url = withoutTrailingSlash(url);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "ai.service.token (the AI_SERVICE_TOKEN environment variable) must be set");
        }
        requirePositive("ai.service.connect-timeout", connectTimeout);
        requirePositive("ai.service.read-timeout", readTimeout);
    }

    private static URI withoutTrailingSlash(URI url) {
        String text = url.toString();
        return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : url;
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration, was " + value);
        }
    }

    /** Never print the token, whatever logs or exception messages format this object. */
    @Override
    public String toString() {
        return "AiServiceProperties[url=" + url + ", token=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
