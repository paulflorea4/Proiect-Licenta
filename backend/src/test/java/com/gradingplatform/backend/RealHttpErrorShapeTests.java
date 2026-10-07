package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 2.6a: every kind of error over a real HTTP connection has the one shape
 * `{status, code, message[, fieldErrors]}`, and none of it leaks internals.
 */
class RealHttpErrorShapeTests extends RealHttpTestBase {

    /** Nested in a test class, so component scanning skips it; see RealHttpRoleSecurityTests. */
    @Controller
    @RequestMapping("/test-errors")
    public static class Probe {
        @GetMapping("/boom")
        @ResponseBody
        public String boom() {
            throw new IllegalStateException("secret-internal-detail jdbc:postgresql://db:5432/grading");
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    private String adminToken;

    @BeforeEach
    void startWithOneAdmin() {
        adminToken = tokenFor(saved("admin@example.com", Role.ADMIN));
    }

    // --- security --------------------------------------------------------------------------

    @Test
    void anAnonymousCallerGets401InTheStandardShape() throws Exception {
        HttpResponse<String> response = send("GET", "/auth/me", null, null);

        assertError(response, 401, "UNAUTHENTICATED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void aBadTokenGets401InTheStandardShape() throws Exception {
        assertError(send("GET", "/admin/users", "not.a.jwt", null), 401, "UNAUTHENTICATED");
    }

    @Test
    void aWrongRoleGets403InTheStandardShapeWithoutNamingTheRole() throws Exception {
        String student = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> response = send("GET", "/admin/users", student, null);

        assertError(response, 403, "ACCESS_DENIED");
        assertThat(response.body()).doesNotContain("ADMIN").doesNotContain("Exception");
    }

    // --- our own rules -----------------------------------------------------------------------

    @Test
    void ourOwnRulesCarryTheirOwnCode() throws Exception {
        String signupBody = "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}";
        assertThat(send("POST", "/auth/signup", null, signupBody).statusCode()).isEqualTo(201);

        assertError(send("POST", "/auth/signup", null, signupBody), 409, "EMAIL_ALREADY_USED");
        assertError(
                send("POST", "/auth/signin", null, "{\"email\":\"ada@example.com\",\"password\":\"wrong password\"}"),
                401,
                "INVALID_CREDENTIALS");
        assertError(
                send("PATCH", "/admin/users/987654321/role", adminToken, "{\"role\":\"TEACHER\"}"),
                404,
                "USER_NOT_FOUND");
        long adminId = users.findByEmail("admin@example.com").orElseThrow().getId();
        assertError(
                send("PATCH", "/admin/users/" + adminId + "/role", adminToken, "{\"role\":\"STUDENT\"}"),
                409,
                "LAST_ADMIN");
        assertError(send("GET", "/admin/users?page=2147483647&size=100", adminToken, null), 400, "PAGE_OUT_OF_RANGE");
    }

    @Test
    void aTokenOfADeletedAccountIs401WithItsOwnCode() throws Exception {
        String token = tokenFor(saved("gone@example.com", Role.STUDENT));
        users.deleteAll();

        assertError(send("GET", "/auth/me", token, null), 401, "ACCOUNT_NO_LONGER_EXISTS");
    }

    // --- validation and malformed requests ---------------------------------------------------

    @Test
    void invalidFieldsAreListedWithoutTheRejectedValues() throws Exception {
        HttpResponse<String> response = send(
                "POST", "/auth/signup", null, "{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}");

        assertError(response, 400, "VALIDATION_FAILED");
        List<String> fields = JsonPath.read(response.body(), "$.fieldErrors[*].field");
        assertThat(fields).contains("email", "password", "fullName");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].message"))
                .allSatisfy(message -> assertThat(message).isNotBlank());
        assertThat(response.body()).doesNotContain("short").doesNotContain("not-an-email");
    }

    @Test
    void fieldErrorsAreInAStableOrder() throws Exception {
        String body = send("POST", "/auth/signup", null, "{}").body();

        List<String> fields = JsonPath.read(body, "$.fieldErrors[*].field");
        assertThat(fields).isSorted();
    }

    @Test
    void aBadRequestParameterNamesTheParameter() throws Exception {
        HttpResponse<String> response = send("GET", "/admin/users?size=0", adminToken, null);

        assertError(response, 400, "VALIDATION_FAILED");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.fieldErrors[*].field"))
                .containsExactly("size");
    }

    @Test
    void malformedJsonAndWrongTypesAreMalformedRequestsThatLeakNoParserText() throws Exception {
        for (String body : List.of("not json", "{\"email\":", "", "[1,2]", "{\"role\":\"SUPERUSER\"}")) {
            HttpResponse<String> response = send("PATCH", "/admin/users/1/role", adminToken, body);

            assertError(response, 400, "MALFORMED_REQUEST");
            assertThat(response.body())
                    .doesNotContain("Unexpected")
                    .doesNotContain("JSON parse")
                    .doesNotContain("SUPERUSER")
                    .doesNotContain("com.gradingplatform")
                    .doesNotContain("Role");
        }
        assertError(
                send("PATCH", "/admin/users/abc/role", adminToken, "{\"role\":\"TEACHER\"}"), 400, "MALFORMED_REQUEST");
        assertError(send("GET", "/admin/users?page=abc", adminToken, null), 400, "MALFORMED_REQUEST");
    }

    // --- Spring's own errors -----------------------------------------------------------------

    @Test
    void anUnknownPathIsA404InTheStandardShape() throws Exception {
        assertError(send("GET", "/no/such/path", adminToken, null), 404, "NOT_FOUND");
    }

    @Test
    void aWrongMethodIsA405WithTheAllowHeaderKept() throws Exception {
        HttpResponse<String> response = send("PUT", "/auth/me", adminToken, "{}");

        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Allow"))
                .hasValueSatisfying(v -> assertThat(v).contains("GET"));
    }

    @Test
    void aWrongContentTypeIsA415() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/signin"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build();

        assertError(http.send(request, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    // --- the unexpected ----------------------------------------------------------------------

    @Test
    void anUnexpectedExceptionIsAGeneric500ThatLeaksNothing() throws Exception {
        HttpResponse<String> response = send("GET", "/test-errors/boom", adminToken, null);

        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body())
                .doesNotContain("secret-internal-detail")
                .doesNotContain("jdbc")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.gradingplatform");
    }

    // --- shape ---------------------------------------------------------------------------------

    @Test
    void theBodyHasExactlyStatusCodeAndMessageUnlessItIsAValidationError() throws Exception {
        String plain = send("GET", "/auth/me", null, null).body();
        String validation = send("POST", "/auth/signup", null, "{}").body();

        assertThat(JsonPath.<Map<String, Object>>read(plain, "$").keySet())
                .containsExactlyInAnyOrder("status", "code", "message");
        assertThat(JsonPath.<Map<String, Object>>read(validation, "$").keySet())
                .containsExactlyInAnyOrder("status", "code", "message", "fieldErrors");
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Status line, JSON content type, and a body whose status and code agree with it. */
    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isNotBlank();
    }
}
