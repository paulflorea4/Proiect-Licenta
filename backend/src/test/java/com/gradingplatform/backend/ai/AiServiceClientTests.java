package com.gradingplatform.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.gradingplatform.backend.ai.FeedbackRequest.HiddenTestResult;
import com.gradingplatform.backend.ai.FeedbackRequest.PublicTestResult;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 7.1a: the client against a fake AI service on a local port (the JDK's own HTTP server). */
class AiServiceClientTests {

    private static final String TOKEN = "token-for-the-fake-ai-service";
    private static final String CODE_MARKER = "STUDENT-CODE-MARKER";
    private static final String BODY_MARKER = "RESPONSE-BODY-MARKER";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final FeedbackRequest REQUEST = new FeedbackRequest(
            "Print the sum of two integers.",
            "PYTHON",
            "a, b = map(int, input().split())\nprint(a * b)  # " + CODE_MARKER + "\n",
            null,
            List.of(new PublicTestResult("small numbers", "2 3\n", "5", TestOutcome.FAILED, "6\n", "")),
            List.of(new HiddenTestResult(true), new HiddenTestResult(false)),
            2);

    private FakeAiService ai;

    @BeforeEach
    void start() throws IOException {
        ai = new FakeAiService();
    }

    @AfterEach
    void stop() {
        ai.stop();
    }

    // ---- the request ----

    @Test
    void oneCallIsOnePostToFeedbackWithTheTokenAndTheRequestAsJson() {
        ai.answer(200, "{\"hint\": \"Look at the operator.\", \"fallback\": false}");

        client(ai.url()).feedback(REQUEST);

        assertThat(ai.received).hasSize(1);
        Received call = ai.received.peek();
        assertThat(call.method).isEqualTo("POST");
        assertThat(call.path).isEqualTo("/feedback");
        assertThat(call.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(call.header("Content-Type")).startsWith("application/json");
        assertThat(call.header("Accept")).contains("application/json");
    }

    @Test
    void theBodyIsTheContractInCamelCaseWithHiddenTestsAsPassedOnly() {
        ai.answer(200, "{\"hint\": \"Look.\", \"fallback\": false}");

        client(ai.url()).feedback(REQUEST);

        JsonNode body = JSON.readTree(ai.received.peek().body);
        assertThat(names(body))
                .containsExactlyInAnyOrder(
                        "assignmentDescription",
                        "language",
                        "code",
                        "compilerOutput",
                        "publicTests",
                        "hiddenTests",
                        "hintLevel");
        assertThat(body.get("code").asString()).isEqualTo(REQUEST.code());
        assertThat(body.get("compilerOutput").isNull()).isTrue();
        assertThat(body.get("hintLevel").asInt()).isEqualTo(2);
        JsonNode test = body.get("publicTests").get(0);
        assertThat(names(test))
                .containsExactlyInAnyOrder("name", "input", "expectedOutput", "status", "actualOutput", "stderr");
        assertThat(test.get("status").asString()).isEqualTo("FAILED");
        assertThat(test.get("input").asString()).isEqualTo("2 3\n");
        assertThat(body.get("hiddenTests").toString()).isEqualTo("[{\"passed\":true},{\"passed\":false}]");
    }

    @Test
    void everyCallCarriesTheToken() {
        ai.answer(200, "{\"hint\": \"One.\", \"fallback\": false}");
        ai.answer(200, "{\"hint\": \"Two.\", \"fallback\": false}");
        AiServiceClient client = client(ai.url());

        client.feedback(REQUEST);
        client.feedback(REQUEST);

        assertThat(ai.received).extracting(call -> call.header("Authorization")).containsOnly("Bearer " + TOKEN);
        assertThat(ai.received).hasSize(2);
    }

    @Test
    void aPathPrefixInTheUrlIsKept() {
        ai.answer(200, "{\"hint\": \"Look.\", \"fallback\": false}");

        client(ai.url() + "/ai/").feedback(REQUEST);

        assertThat(ai.received.peek().path).isEqualTo("/ai/feedback");
    }

    // ---- the answer ----

    @Test
    void theHintAndTheFallbackFlagAreReturned() {
        ai.answer(200, "{\"hint\": \"Look at the operator.\", \"fallback\": false}");
        ai.answer(200, "{\"hint\": \"Trace it by hand.\", \"fallback\": true}");
        AiServiceClient client = client(ai.url());

        assertThat(client.feedback(REQUEST)).isEqualTo(new FeedbackResponse("Look at the operator.", false));
        assertThat(client.feedback(REQUEST)).isEqualTo(new FeedbackResponse("Trace it by hand.", true));
    }

    @Test
    void aFieldTheContractDoesNotHaveYetIsIgnored() {
        // A tolerant reader: the AI service may add a field before the backend reads it.
        ai.answer(200, "{\"hint\": \"Look.\", \"fallback\": false, \"model\": \"x\"}");

        assertThat(client(ai.url()).feedback(REQUEST)).isEqualTo(new FeedbackResponse("Look.", false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not json " + BODY_MARKER,
                "<html>" + BODY_MARKER + "</html>",
                "{}",
                "[]",
                "{\"hint\": \"Look.\"}",
                "{\"fallback\": false}",
                "{\"hint\": null, \"fallback\": false}",
                "{\"hint\": \"   \", \"fallback\": false}",
                "{\"hint\": \"Look.\", \"fallback\": null}",
                "{\"hint\": [\"" + BODY_MARKER + "\"], \"fallback\": false}",
            })
    void anAnswerThatIsNotAFeedbackResponseIsAnError(String body) {
        ai.answer(200, body);

        AiServiceException error = catchThrowableOfType(
                AiServiceException.class, () -> client(ai.url()).feedback(REQUEST));

        assertThat(error.getMessage()).isEqualTo("The AI service's answer could not be read");
        assertThat(error.status()).isNull();
        assertNothingSecretIn(error);
    }

    // ---- no answer ----

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 422, 500, 502, 503})
    void anErrorStatusIsAnErrorWithTheStatusOnly(int status) {
        ai.answer(status, "{\"detail\": \"" + BODY_MARKER + "\"}");

        AiServiceException error = catchThrowableOfType(
                AiServiceException.class, () -> client(ai.url()).feedback(REQUEST));

        assertThat(error.getMessage()).isEqualTo("The AI service answered HTTP " + status);
        assertThat(error.status()).isEqualTo(status);
        assertNothingSecretIn(error);
    }

    @Test
    void aRedirectIsNotFollowedSoTheTokenGoesNowhereElse() throws IOException {
        FakeAiService elsewhere = new FakeAiService();
        try {
            ai.redirectTo(elsewhere.url() + "/feedback");

            AiServiceException error = catchThrowableOfType(
                    AiServiceException.class, () -> client(ai.url()).feedback(REQUEST));

            assertThat(error.status()).isEqualTo(307);
            assertThat(elsewhere.received).isEmpty();
        } finally {
            elsewhere.stop();
        }
    }

    @Test
    void anAnswerLaterThanTheReadTimeoutIsATimeout() {
        ai.answerAfter(Duration.ofSeconds(3), 200, "{\"hint\": \"Too late.\", \"fallback\": false}");
        AiServiceClient client = new AiServiceClient(properties(ai.url(), Duration.ofMillis(300)));
        long start = System.nanoTime();

        AiServiceException error = catchThrowableOfType(AiServiceException.class, () -> client.feedback(REQUEST));

        assertThat(error.getMessage()).isEqualTo("The AI service did not answer in time");
        assertThat(error.status()).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertNothingSecretIn(error);
    }

    @Test
    void aServiceThatIsNotRunningCannotBeReached() {
        String url = ai.url();
        ai.stop();

        AiServiceException error =
                catchThrowableOfType(AiServiceException.class, () -> client(url).feedback(REQUEST));

        assertThat(error.getMessage()).isEqualTo("The AI service could not be reached");
        assertThat(error.status()).isNull();
        assertNothingSecretIn(error);
    }

    @Test
    void theHttpClientHasTheConnectTimeoutAndFollowsNoRedirect() {
        HttpClient http = AiServiceClient.httpClient(properties("http://localhost:8000", Duration.ofSeconds(70)));

        assertThat(http.connectTimeout()).contains(Duration.ofSeconds(4));
        assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    // ---- never inside a transaction ----

    @Test
    void aCallInsideAnOpenDatabaseTransactionIsRefusedBeforeAnythingIsSent() {
        ai.answer(200, "{\"hint\": \"Look.\", \"fallback\": false}");
        AiServiceClient client = client(ai.url());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.feedback(REQUEST))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(ai.received).isEmpty();
    }

    // ---- helpers ----

    private static AiServiceClient client(String url) {
        return new AiServiceClient(properties(url, Duration.ofSeconds(10)));
    }

    private static AiServiceProperties properties(String url, Duration readTimeout) {
        return new AiServiceProperties(URI.create(url), TOKEN, Duration.ofSeconds(4), readTimeout);
    }

    private static List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** Neither the token, the student's code nor the service's answer, in the message or the trace. */
    private static void assertNothingSecretIn(Throwable error) {
        var trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(TOKEN, CODE_MARKER, BODY_MARKER);
    }

    private record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> String.join(",", entry.getValue()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** A stand-in for the AI service: answers each request with the next scripted answer. */
    private static final class FakeAiService {

        private record Answer(int status, String body, Duration delay, String location) {}

        final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Answer> script = new ConcurrentLinkedQueue<>();
        private final HttpServer server;
        private boolean stopped;

        FakeAiService() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void answer(int status, String body) {
            script.add(new Answer(status, body, Duration.ZERO, null));
        }

        void answerAfter(Duration delay, int status, String body) {
            script.add(new Answer(status, body, delay, null));
        }

        void redirectTo(String location) {
            script.add(new Answer(307, "", Duration.ZERO, location));
        }

        void stop() {
            if (!stopped) {
                stopped = true;
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    body));
            Answer answer = script.poll();
            if (answer == null) {
                answer = new Answer(500, "no answer scripted", Duration.ZERO, null);
            }
            try {
                Thread.sleep(answer.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (answer.location() != null) {
                exchange.getResponseHeaders().add("Location", answer.location());
            }
            try {
                exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException e) {
                // The client gave up (the timeout test): nothing to answer.
            } finally {
                exchange.close();
            }
        }
    }
}
