package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthCookie;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * 8.2c: the JWT filter reads the token from the {@link AuthCookie} when no `Authorization` header
 * is sent, over real HTTP (Tomcat parses the `Cookie` header). `GET /auth/me` tells who the request
 * was authenticated as. The bearer header's own cases stay in {@link RealHttpJwtFilterTests}.
 */
class RealHttpCookieAuthTests extends RealHttpTestBase {

    // --- the cookie alone ------------------------------------------------------------------

    @Test
    void aValidTokenInTheAuthCookieAuthenticates() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me = getWith("/auth/me", "Cookie", cookie(token));

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("ada@example.com");
    }

    @Test
    void theCookieThatSigninSetsIsWhatSignsTheBrowserIn() throws Exception {
        post(
                "/auth/signup",
                "{\"email\":\"ada@example.com\",\"password\":\"correct horse\",\"fullName\":\"Ada\"}",
                null);
        HttpResponse<String> signin =
                post("/auth/signin", "{\"email\":\"ada@example.com\",\"password\":\"correct horse\"}", null);

        // What a browser sends back: the cookie's name=value, without its attributes.
        String nameAndValue = SigninCookies.headerOf(signin).split(";", 2)[0];
        HttpResponse<String> me = getWith("/auth/me", "Cookie", nameAndValue);

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("ada@example.com");
    }

    @Test
    void theRoleFromTheCookiesTokenIsWhatTheRoleRulesSee() throws Exception {
        String admin = tokenFor(saved("root@example.com", Role.ADMIN));
        String student = tokenFor(saved("ada@example.com", Role.STUDENT));

        assertThat(getWith("/admin/users", "Cookie", cookie(admin)).statusCode())
                .isEqualTo(200);
        assertThat(getWith("/admin/users", "Cookie", cookie(student)).statusCode())
                .isEqualTo(403);
    }

    @Test
    void otherCookiesBesideItDoNotMatter() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me = getWith("/auth/me", "Cookie", "theme=dark; " + cookie(token) + "; lang=ro");

        assertThat(me.statusCode()).isEqualTo(200);
    }

    @Test
    void cookieAuthenticationCreatesNoSessionAndSetsNoCookie() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me = getWith("/auth/me", "Cookie", cookie(token));

        assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
    }

    // --- a cookie that does not authenticate -----------------------------------------------

    @Test
    void aBadTokenInTheCookieIsAnonymous() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        for (String value : new String[] {"not.a.jwt", "", tampered}) {
            HttpResponse<String> me = getWith("/auth/me", "Cookie", AuthCookie.NAME + "=" + value);

            assertThat(me.statusCode()).as(value).isEqualTo(401);
            assertThat(JsonPath.<String>read(me.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void aValidTokenUnderAnotherCookieNameIsAnonymous() throws Exception {
        String token = tokenFor(saved("ada@example.com", Role.STUDENT));

        assertThat(getWith("/auth/me", "Cookie", "token=" + token).statusCode()).isEqualTo(401);
        assertThat(getWith("/auth/me", "Cookie", "ACCESS_TOKEN=" + token).statusCode())
                .isEqualTo(401);
    }

    @Test
    void twoAuthCookiesAreAmbiguousAndAnonymousEvenWhenBothAreValid() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));
        String bob = tokenFor(saved("bob@example.com", Role.STUDENT));

        assertThat(getWith("/auth/me", "Cookie", cookie(ada) + "; " + cookie(bob))
                        .statusCode())
                .isEqualTo(401);
        assertThat(getWith("/auth/me", "Cookie", cookie(ada) + "; " + cookie(ada))
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    void aBadCookieDoesNotBlockThePublicEndpoints() throws Exception {
        assertThat(getWith("/health", "Cookie", AuthCookie.NAME + "=not.a.jwt").statusCode())
                .isEqualTo(200);
    }

    // --- header and cookie together: the header decides -----------------------------------

    @Test
    void whenBothAreValidTheHeadersUserIsTheOneAuthenticated() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));
        String tom = tokenFor(saved("tom@example.com", Role.TEACHER));

        HttpResponse<String> me = getWith("/auth/me", "Authorization", "Bearer " + ada, "Cookie", cookie(tom));

        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("ada@example.com");
    }

    @Test
    void aValidHeaderIsNotSpoiltByABadCookie() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me =
                getWith("/auth/me", "Authorization", "Bearer " + ada, "Cookie", AuthCookie.NAME + "=not.a.jwt");

        assertThat(me.statusCode()).isEqualTo(200);
    }

    @Test
    void aBadHeaderIsNotRescuedByAValidCookie() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me = getWith("/auth/me", "Authorization", "Bearer not.a.jwt", "Cookie", cookie(ada));

        assertThat(me.statusCode()).isEqualTo(401);
    }

    @Test
    void anotherSchemeInTheHeaderIsNotRescuedByAValidCookie() throws Exception {
        String ada = tokenFor(saved("ada@example.com", Role.STUDENT));

        HttpResponse<String> me = getWith("/auth/me", "Authorization", "Basic YWRhOnB3", "Cookie", cookie(ada));

        assertThat(me.statusCode()).isEqualTo(401);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String cookie(String token) {
        return AuthCookie.NAME + "=" + token;
    }

    /** A GET with the given header name/value pairs and nothing else. */
    private HttpResponse<String> getWith(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .headers(headers)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
