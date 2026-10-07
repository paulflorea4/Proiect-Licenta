package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.User;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 2.7c: what the auth endpoints must refuse, over real HTTP and a real database. A duplicate
 * signup is rejected and cannot take over the existing account, even when the requests arrive at
 * the same moment; a wrong password is a 401 that is indistinguishable from an unknown email and
 * issues nothing. The endpoint-level rules have their own fast tests (`SignupValidationTests`,
 * `SigninEndpointTests`); this is the end-to-end proof, including the error codes of 2.6a.
 */
class AuthRejectionIntegrationTests extends RealHttpTestBase {

    private static final String PASSWORD = "correct horse";
    private static final String OTHER_PASSWORD = "battery staple";

    // --- duplicate signup ------------------------------------------------------------------

    @Test
    void aSecondSignupWithTheSameEmailIs409WithItsOwnCode() throws Exception {
        assertThat(signup("ada@example.com", PASSWORD, "Ada L").statusCode()).isEqualTo(201);

        HttpResponse<String> duplicate = signup("ada@example.com", PASSWORD, "Ada L");

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(duplicate.body(), "$.code")).isEqualTo("EMAIL_ALREADY_USED");
        assertThat(JsonPath.<Integer>read(duplicate.body(), "$.status")).isEqualTo(409);
    }

    @Test
    void theSameEmailInAnotherCaseOrWithSpacesIsTheSameAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        assertThat(signup("ADA@Example.com", PASSWORD, "Someone Else").statusCode())
                .isEqualTo(409);
        assertThat(signup("  ada@example.com  ", PASSWORD, "Someone Else").statusCode())
                .isEqualTo(409);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void aRejectedDuplicateCannotTakeOverTheAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");
        String hashBefore = hashOf("ada@example.com");

        // An attacker signs up with the victim's email and a password of their own.
        assertThat(signup("ada@example.com", OTHER_PASSWORD, "Mallory").statusCode())
                .isEqualTo(409);

        assertThat(users.count()).isEqualTo(1);
        User stored = users.findByEmail("ada@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(stored.getFullName()).isEqualTo("Ada L");
        assertThat(signin("ada@example.com", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(signin("ada@example.com", OTHER_PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void theConflictAnswerDoesNotTellAnythingAboutTheExistingAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        String body = signup("ada@example.com", OTHER_PASSWORD, "Mallory").body();

        assertThat(body)
                .doesNotContain("Ada L")
                .doesNotContain("$2a$")
                .doesNotContain("users_email_key")
                .doesNotContain("Exception")
                .doesNotContain("at com.gradingplatform");
    }

    @RepeatedTest(3)
    void signupsForOneEmailArrivingTogetherLeaveExactlyOneAccount() throws Exception {
        int attempts = 6;
        var ready = new CountDownLatch(attempts);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String fullName = "Racer " + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return signup("race@example.com", PASSWORD, fullName).statusCode();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        // The unique constraint decides: one winner, everyone else a clean 409, never a 500.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(attempts - 1);
        assertThat(users.count()).isEqualTo(1);
    }

    // --- wrong password --------------------------------------------------------------------

    @Test
    void aWrongPasswordIs401WithItsOwnCodeAndIssuesNothing() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        HttpResponse<String> response = signin("ada@example.com", OTHER_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.body()).doesNotContain("token").doesNotContain("Bearer");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Authorization")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Correct Horse", // other capitals
                "CORRECT HORSE",
                "correct horse ", // one character more
                " correct horse",
                "correct hors", // one character less
                "correct  horse",
                "correct horse\u0000",
                "battery staple"
            })
    void anythingButTheExactPasswordIs401(String attempt) throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        assertThat(signin("ada@example.com", attempt).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherUsersPasswordDoesNotOpenThisAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");
        signup("grace@example.com", OTHER_PASSWORD, "Grace H");

        assertThat(signin("ada@example.com", OTHER_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(signin("grace@example.com", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(signin("ada@example.com", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(signin("grace@example.com", OTHER_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void aWrongPasswordLooksExactlyLikeAnUnknownEmail() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        HttpResponse<String> wrongPassword = signin("ada@example.com", OTHER_PASSWORD);
        HttpResponse<String> unknownEmail = signin("nobody@example.com", OTHER_PASSWORD);

        assertThat(unknownEmail.statusCode()).isEqualTo(wrongPassword.statusCode());
        assertThat(unknownEmail.body()).isEqualTo(wrongPassword.body());
        assertThat(unknownEmail.headers().map().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        wrongPassword.headers().map().keySet());
    }

    @Test
    void aFailedSigninChangesNothingAboutTheAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");
        String hashBefore = hashOf("ada@example.com");

        signin("ada@example.com", OTHER_PASSWORD);

        assertThat(hashOf("ada@example.com")).isEqualTo(hashBefore);
        assertThat(signin("ada@example.com", PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void anOversizedPasswordIs400NotAServerErrorOrAnAccept() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada L");

        assertThat(signin("ada@example.com", "a".repeat(73)).statusCode()).isEqualTo(400);
    }

    // --- helpers ---------------------------------------------------------------------------

    private String hashOf(String email) {
        return users.findByEmail(email).orElseThrow().getPasswordHash();
    }

    private HttpResponse<String> signup(String email, String password, String fullName) throws Exception {
        return post(
                "/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"" + fullName + "\"}",
                null);
    }

    private HttpResponse<String> signin(String email, String password) throws Exception {
        return post(
                "/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"" + jsonEscape(password) + "\"}", null);
    }

    /** Only what the attempts above contain: a NUL character must go as an escape, not raw. */
    private static String jsonEscape(String value) {
        return value.replace("\u0000", "\\u0000");
    }
}
