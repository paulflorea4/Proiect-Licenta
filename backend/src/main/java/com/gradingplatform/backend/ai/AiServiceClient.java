package com.gradingplatform.backend.ai;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the AI service's `POST /feedback` (7.1a). One `RestClient` for the application's lifetime,
 * built with the base URL, the bearer token as a default header (so every call carries it) and the
 * connect and read timeouts of {@link AiServiceProperties}.
 *
 * <p>Never part of grading, and never inside an open database transaction: a call can take as long
 * as the read timeout, and holding a connection and its locks for that long would stall the
 * submission pipeline. {@link #feedback} refuses to run inside one, so 7.2a cannot forget it.
 */
public class AiServiceClient {

    static final String FEEDBACK_PATH = "/feedback";

    private final RestClient rest;

    public AiServiceClient(AiServiceProperties properties) {
        this.rest = RestClient.builder()
                .baseUrl(properties.url().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(AiServiceProperties properties) {
        var factory = new JdkClientHttpRequestFactory(httpClient(properties));
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /** The JDK client with the connect timeout; redirects are never followed, so the token cannot be forwarded. */
    static HttpClient httpClient(AiServiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The hint for this request.
     *
     * @throws IllegalStateException when called inside an open database transaction
     * @throws AiServiceException when the service cannot be reached, does not answer in time, answers
     *     with an error status, or answers with something that is not a {@link FeedbackResponse}
     */
    public FeedbackResponse feedback(FeedbackRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The AI service must not be called inside a database transaction");
        }
        FeedbackResponse response;
        try {
            response = rest.post()
                    .uri(FEEDBACK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    // Any status but 2xx, a redirect included (it is never followed): the status only,
                    // never the body, which could echo the request.
                    .onStatus(status -> !status.is2xxSuccessful(), (sent, answer) -> {
                        int status = answer.getStatusCode().value();
                        throw new AiServiceException("The AI service answered HTTP " + status, status);
                    })
                    .body(FeedbackResponse.class);
        } catch (ResourceAccessException e) {
            String message = e.getCause() instanceof HttpTimeoutException
                    ? "The AI service did not answer in time"
                    : "The AI service could not be reached";
            // Chained for the log: its message holds the URL only (the token is a header).
            throw new AiServiceException(message, e);
        } catch (RestClientException e) {
            throw new AiServiceException("The AI service's answer could not be read", (Integer) null);
        }
        if (response == null || response.hint() == null || response.hint().isBlank() || response.fallback() == null) {
            throw new AiServiceException("The AI service's answer could not be read", (Integer) null);
        }
        return response;
    }
}
