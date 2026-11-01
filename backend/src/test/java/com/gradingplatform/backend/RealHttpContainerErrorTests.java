package com.gradingplatform.backend;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.controller.ApiErrorController;
import com.gradingplatform.backend.dto.ErrorCode;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 8.0j: the container error path over a real HTTP connection. {@link ApiErrorController} was only
 * unit-tested, and {@code MockMvc} does not perform the container's error dispatch, so nothing
 * checked that an error the container sends to {@code /error} comes out as the standard body.
 *
 * <p>Spring Security's firewall refuses a URL with a semicolon, a doubled slash, a {@code ..} or an
 * encoded {@code %25} before MVC sees it, and the refusal is a {@code sendError(400)}, so the
 * container dispatches it to {@code /error}. Nothing of the request (path, token) is read by then,
 * so the answer is the same for everyone.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealHttpContainerErrorTests extends RealHttpTestBase {

    private User student;

    @BeforeEach
    void world() {
        student = saved("student@example.com", Role.STUDENT);
    }

    static Stream<String> pathsTheFirewallRefuses() {
        return Stream.of(
                "/auth/me;x",
                "/auth/me;jsessionid=1",
                "/health;x",
                "//auth/me",
                "/auth//me",
                "/auth/%2e%2e/me",
                "/auth/%2E/me",
                "/auth/me%25");
    }

    @ParameterizedTest
    @MethodSource("pathsTheFirewallRefuses")
    void aPathTheFirewallRefusesIsA400InTheStandardBody(String path) throws Exception {
        HttpResponse<String> response = get(path, null);

        assertStandardBadRequest(response);
    }

    @ParameterizedTest
    @MethodSource("pathsTheFirewallRefuses")
    void everyCallerGetsTheSameBodyBecauseNothingOfTheRequestWasRead(String path) throws Exception {
        String anonymous = get(path, null).body();

        assertThat(get(path, tokenFor(student)).body()).isEqualTo(anonymous);
        assertThat(get(path, "not.a.jwt").body()).isEqualTo(anonymous);
        assertThat(send("POST", path, null, "{}").body()).isEqualTo(anonymous);
    }

    @Test
    void theAnswerHoldsOnlyTheStandardFieldsAndEchoesNothing() throws Exception {
        HttpResponse<String> response = get("/auth/me;secret-marker", tokenFor(student));

        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("status", "code", "message");
        assertThat(response.body()).doesNotContain("secret-marker").doesNotContain("/auth/me");
        assertThat(JsonPath.<String>read(response.body(), "$.message"))
                .isEqualTo(ErrorCode.MALFORMED_REQUEST.defaultMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/me;x", "/health;x"})
    void aProtectedAndAPublicPathAreRefusedAlike(String path) throws Exception {
        // The firewall runs ahead of the authorization rules, so a protected path is no 401 here.
        assertThat(get(path, null).statusCode()).isEqualTo(400);
    }

    @Test
    void askingForTheErrorPathItselfIsTheGeneric500OfAMissingStatus() throws Exception {
        // As ErrorPiecesTests pins on the controller: no recorded error means the generic 500, in the
        // standard body. The path is public (2.4a), so it answers whoever asks.
        HttpResponse<String> response = get("/error", null);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(contentType(response)).startsWith("application/json");
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INTERNAL_ERROR");
    }

    /**
     * Known gap, not a goal: a request Tomcat's own parser refuses (an encoded slash, backslash or NUL
     * in the path, a bare {@code %}, a non-ASCII byte, a URL over 8 KB) never reaches the application,
     * and Tomcat answers with its own HTML page instead of the standard body. The contract here is
     * only that it stays a 400 and reveals nothing of the application (see docs/suggestions.md).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/auth/me%2f", "/auth/me%5c", "/auth/me%00", "/%"})
    void aRequestTomcatRefusesItselfIsStillA400AndRevealsNothing(String target) throws Exception {
        assertTomcatRefusal(target);
    }

    @Test
    void aBackslashOrANonAsciiByteInThePathIsRefusedByTomcatToo() throws Exception {
        assertTomcatRefusal("/auth/me" + (char) 92 + "x");
        assertTomcatRefusal("/auth/" + (char) 0xe9);
    }

    // --- helpers ----------------------------------------------------------------------------

    private void assertStandardBadRequest(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(contentType(response)).startsWith("application/json");
        assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    /** Sends the request line exactly as given (the JDK client would reject or rewrite some of these). */
    private void assertTomcatRefusal(String target) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            String head = "GET " + target + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            String reply = out.toString(ISO_8859_1);

            assertThat(reply).as(target).startsWith("HTTP/1.1 400");
            assertThat(reply).as(target).doesNotContain("Exception").doesNotContain("com.gradingplatform");
        }
    }
}
