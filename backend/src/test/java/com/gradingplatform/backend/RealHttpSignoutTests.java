package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthCookie;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 8.2e: `POST /auth/signout` is public and answers 204 with a `Set-Cookie` that clears the auth
 * cookie; a valid cookie needs the CSRF header (8.2d), so another site cannot sign a user out. The
 * token is not revoked (stateless). Over real HTTP, so the header is what Tomcat sends.
 */
class RealHttpSignoutTests extends RealHttpTestBase {

    @Test
    void withACookieAndTheCsrfHeaderItIs204AndClearsTheCookie() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> signout = signout("Cookie", cookie(token), AuthCookie.CSRF_HEADER, "fetch");

        assertThat(signout.statusCode()).isEqualTo(204);
        assertThat(signout.body()).isEmpty();
        assertClears(signout);
    }

    @Test
    void theClearingCookieMatchesTheIssuedOneSoTheBrowserDropsIt() throws Exception {
        post(
                "/auth/signup",
                "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}",
                null);
        HttpResponse<String> signin =
                post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}", null);

        String issued = SigninCookies.headerOf(signin);
        String cleared = SigninCookies.headerOf(signout());

        // A browser deletes by name + path + domain: those, and the security attributes, must match.
        assertThat(attributes(cleared)).containsAll(List.of("Path=/", "Secure", "HttpOnly", "SameSite=Strict"));
        assertThat(attributes(issued)).containsAll(List.of("Path=/", "Secure", "HttpOnly", "SameSite=Strict"));
        assertThat(cleared).doesNotContainIgnoringCase("domain=");
        assertThat(issued).doesNotContainIgnoringCase("domain=");
    }

    @Test
    void withoutAnyCookieItIsStill204SoSignoutIsIdempotent() throws Exception {
        HttpResponse<String> signout = signout();

        assertThat(signout.statusCode()).isEqualTo(204);
        assertClears(signout);
    }

    @Test
    void aBadCookieIsStill204AndCleared() throws Exception {
        HttpResponse<String> signout = signout("Cookie", AuthCookie.NAME + "=not.a.jwt");

        assertThat(signout.statusCode()).isEqualTo(204);
        assertClears(signout);
    }

    @Test
    void withABearerHeaderItIs204() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        assertThat(signout("Authorization", "Bearer " + token).statusCode()).isEqualTo(204);
    }

    @Test
    void aValidCookieWithoutTheCsrfHeaderIsRefusedAndStaysSet() throws Exception {
        // Another site posting to /auth/signout cannot sign the user out.
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> signout = signout("Cookie", cookie(token));

        assertThat(signout.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(signout.body(), "$.code")).isEqualTo("CSRF_HEADER_MISSING");
        assertThat(signout.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void theTokenItselfIsNotRevokedAndStaysValidUntilItExpires() throws Exception {
        // Pinned on purpose: the API is stateless, so signout only removes the browser's copy.
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        signout("Cookie", cookie(token), AuthCookie.CSRF_HEADER, "fetch");

        assertThat(get("/auth/me", token).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyPostSignsOut() throws Exception {
        HttpResponse<String> get = get("/auth/signout", null);

        assertThat(get.statusCode()).isEqualTo(401);
        assertThat(get.headers().allValues("Set-Cookie")).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static void assertClears(HttpResponse<String> signout) {
        String header = SigninCookies.headerOf(signout);
        assertThat(header).startsWith(AuthCookie.NAME + "=;");
        assertThat(attributes(header)).contains("Max-Age=0", "Path=/", "Secure", "HttpOnly", "SameSite=Strict");
    }

    private static List<String> attributes(String setCookie) {
        return List.of(setCookie.split("; "));
    }

    private static String cookie(String token) {
        return AuthCookie.NAME + "=" + token;
    }

    /** `POST /auth/signout`, no body, exactly the given header name/value pairs. */
    private HttpResponse<String> signout(String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/signout"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
