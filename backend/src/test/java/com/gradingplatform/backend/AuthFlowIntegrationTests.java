package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.security.JwtProperties;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 2.7b: the whole path a new user takes, over real HTTP and a real database: sign up, sign in, then
 * use the token on protected endpoints. Each piece has its own tests (2.3 signup, 2.4a signin, 2.4b
 * the filter, 2.4c `/auth/me`); this is the test that they agree with each other, using only what
 * one response hands to the next request.
 */
class AuthFlowIntegrationTests extends RealHttpTestBase {

    private static final String PASSWORD = "correct horse";

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void aNewUserSignsUpSignsInAndIsRecognisedByTheirToken() throws Exception {
        HttpResponse<String> signup = signup("ada@example.com", "Ada L");
        assertThat(signup.statusCode()).isEqualTo(201);
        long id = JsonPath.<Number>read(signup.body(), "$.id").longValue();
        assertThat(JsonPath.<String>read(signup.body(), "$.role")).isEqualTo("STUDENT");

        HttpResponse<String> signin = signin("ada@example.com");
        assertThat(signin.statusCode()).isEqualTo(200);
        String token = JsonPath.read(signin.body(), "$.token");
        assertThat(JsonPath.<String>read(signin.body(), "$.tokenType")).isEqualTo("Bearer");
        assertThat(token).isNotBlank();

        HttpResponse<String> me = get("/auth/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(me.body(), "$.id").longValue()).isEqualTo(id);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("ada@example.com");
        assertThat(JsonPath.<String>read(me.body(), "$.fullName")).isEqualTo("Ada L");
        assertThat(JsonPath.<String>read(me.body(), "$.role")).isEqualTo("STUDENT");
    }

    @Test
    void theUserInTheSigninResponseIsTheOneSignupCreated() throws Exception {
        String signupBody = signup("ada@example.com", "Ada L").body();

        String signinBody = signin("ada@example.com").body();

        assertThat(JsonPath.<Number>read(signinBody, "$.user.id").longValue())
                .isEqualTo(JsonPath.<Number>read(signupBody, "$.id").longValue());
        assertThat(JsonPath.<String>read(signinBody, "$.user.email")).isEqualTo("ada@example.com");
        assertThat(JsonPath.<String>read(signinBody, "$.user.role")).isEqualTo("STUDENT");
    }

    @Test
    void theIssuedTokenCarriesTheNewUsersIdentityAndTheConfiguredLifetime() throws Exception {
        long id = JsonPath.<Number>read(signup("ada@example.com", "Ada L").body(), "$.id")
                .longValue();
        String signinBody = signin("ada@example.com").body();

        // The server's own reading of the token must give back exactly the signed-up account.
        assertThat(jwtService.parse(JsonPath.read(signinBody, "$.token")))
                .contains(new AuthenticatedUser(id, "ada@example.com", Role.STUDENT));
        assertThat(JsonPath.<Number>read(signinBody, "$.expiresIn").longValue())
                .isEqualTo(jwtProperties.expiration().toSeconds());
    }

    @Test
    void theTokenOpensEveryProtectedEndpointItsRoleAllows() throws Exception {
        signup("ada@example.com", "Ada L");
        String token = tokenOf(signin("ada@example.com"));

        // Authenticated (not 401), but a student is not an admin (403): the same token is accepted
        // by the filter and judged by the role rule.
        assertThat(get("/auth/me", token).statusCode()).isEqualTo(200);
        assertThat(get("/admin/users", token).statusCode()).isEqualTo(403);
    }

    @Test
    void withoutTheTokenOrWithAnAlteredOneTheSameCallsAre401() throws Exception {
        signup("ada@example.com", "Ada L");
        String token = tokenOf(signin("ada@example.com"));
        String altered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(get("/auth/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/auth/me", altered).statusCode()).isEqualTo(401);
        assertThat(get("/auth/me", token).statusCode()).isEqualTo(200);
    }

    @Test
    void eachUsersTokenIsTheirOwn() throws Exception {
        signup("ada@example.com", "Ada L");
        signup("grace@example.com", "Grace H");
        String ada = tokenOf(signin("ada@example.com"));
        String grace = tokenOf(signin("grace@example.com"));

        assertThat(JsonPath.<String>read(get("/auth/me", ada).body(), "$.email"))
                .isEqualTo("ada@example.com");
        assertThat(JsonPath.<String>read(get("/auth/me", grace).body(), "$.email"))
                .isEqualTo("grace@example.com");
    }

    @Test
    void signingInAgainGivesAnotherTokenAndTheFirstKeepsWorking() throws Exception {
        signup("ada@example.com", "Ada L");
        String first = tokenOf(signin("ada@example.com"));
        Thread.sleep(1100); // tokens are stamped to the second: wait so the two differ

        String second = tokenOf(signin("ada@example.com"));

        assertThat(second).isNotEqualTo(first);
        assertThat(get("/auth/me", first).statusCode()).isEqualTo(200);
        assertThat(get("/auth/me", second).statusCode()).isEqualTo(200);
    }

    @Test
    void theEmailIsMatchedTheSameWayAtSignupAndSignin() throws Exception {
        // Signup stores it trimmed and lower-case; signin finds it however it is typed.
        assertThat(signup("  Ada@Example.COM ", "Ada L").statusCode()).isEqualTo(201);

        HttpResponse<String> signin = signin("ADA@example.com");
        assertThat(signin.statusCode()).isEqualTo(200);

        assertThat(JsonPath.<String>read(get("/auth/me", tokenOf(signin)).body(), "$.email"))
                .isEqualTo("ada@example.com");
    }

    @Test
    void signingInBeforeSigningUpFailsAndGivesNoToken() throws Exception {
        HttpResponse<String> signin = signin("ada@example.com");

        assertThat(signin.statusCode()).isEqualTo(401);
        assertThat(signin.body()).doesNotContain("token");
    }

    @Test
    void nothingInTheFlowEverExposesThePasswordOrItsHash() throws Exception {
        HttpResponse<String> signup = signup("ada@example.com", "Ada L");
        HttpResponse<String> signin = signin("ada@example.com");
        HttpResponse<String> me = get("/auth/me", tokenOf(signin));

        for (HttpResponse<String> response : java.util.List.of(signup, signin, me)) {
            assertThat(response.body())
                    .doesNotContain(PASSWORD)
                    .doesNotContain("$2a$")
                    .doesNotContain("assword");
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private HttpResponse<String> signup(String email, String fullName) throws Exception {
        return post(
                "/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"" + fullName + "\"}",
                null);
    }

    private HttpResponse<String> signin(String email) throws Exception {
        return post("/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
    }

    private static String tokenOf(HttpResponse<String> signin) {
        return JsonPath.read(signin.body(), "$.token");
    }
}
