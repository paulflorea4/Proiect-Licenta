package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gradingplatform.backend.dto.PasswordPolicy;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 2.3c: signup validation. Not `@Transactional` on purpose: a duplicate email makes Postgres abort
 * the transaction it happens in, so a test-wide transaction could not query afterwards. The tests
 * clean up after themselves instead (the cached test context and its database are shared).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SignupValidationTests {

    private static final String PASSWORD = "correct horse";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void emptyUsersTable() {
        users.deleteAll();
    }

    // --- duplicate email -------------------------------------------------------------------

    @Test
    void aSecondSignupWithTheSameEmailIsRejectedWithConflict() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada").andExpect(status().isCreated());

        signup("ada@example.com", "another password", "Impostor").andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void theFirstAccountIsLeftUntouchedByTheRejectedSignup() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada").andExpect(status().isCreated());
        signup("ada@example.com", "another password", "Impostor").andExpect(status().isConflict());

        User original = users.findByEmail("ada@example.com").orElseThrow();
        assertThat(original.getFullName()).isEqualTo("Ada");
        assertThat(passwordEncoder.matches(PASSWORD, original.getPasswordHash()))
                .isTrue();
    }

    @Test
    void theSameEmailInAnotherCaseOrWithSpacesCountsAsTheSameAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada").andExpect(status().isCreated());

        signup("ADA@Example.COM", PASSWORD, "Ada").andExpect(status().isConflict());
        signup("  ada@example.com  ", PASSWORD, "Ada").andExpect(status().isConflict());

        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void theEmailIsStoredAndReturnedTrimmedAndLowerCase() throws Exception {
        signup("  Grace@Example.COM ", PASSWORD, "Grace")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("grace@example.com"));

        assertThat(users.findByEmail("grace@example.com")).isPresent();
    }

    @Test
    void aConflictBodyDoesNotLeakAnythingAboutTheOtherAccount() throws Exception {
        signup("ada@example.com", PASSWORD, "Ada").andExpect(status().isCreated());

        String body = signup("ada@example.com", PASSWORD, "Ada")
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("Ada").doesNotContain("users_email_key").doesNotContain("$2");
    }

    // --- email format and size -------------------------------------------------------------

    @Test
    void anEmailWithoutAnAtSignIsRejected() throws Exception {
        signup("not-an-email", PASSWORD, "Ada").andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    @Test
    void anEmailLongerThanTheColumnIsRejectedNotADatabaseError() throws Exception {
        String tooLong = "a".repeat(250) + "@example.com";

        signup(tooLong, PASSWORD, "Ada").andExpect(status().isBadRequest());
    }

    @Test
    void aFullNameLongerThanTheColumnIsRejectedNotADatabaseError() throws Exception {
        signup("ada@example.com", PASSWORD, "A".repeat(256)).andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    // --- password length -------------------------------------------------------------------

    @Test
    void aPasswordShorterThanTheMinimumIsRejected() throws Exception {
        signup("ada@example.com", "a".repeat(PasswordPolicy.MIN_LENGTH - 1), "Ada")
                .andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    @Test
    void aPasswordOfExactlyTheMinimumLengthIsAccepted() throws Exception {
        signup("ada@example.com", "a".repeat(PasswordPolicy.MIN_LENGTH), "Ada").andExpect(status().isCreated());
    }

    @Test
    void aPasswordOfExactlyTheMaximumBytesIsAccepted() throws Exception {
        signup("ada@example.com", "a".repeat(PasswordPolicy.MAX_BYTES), "Ada").andExpect(status().isCreated());

        User saved = users.findByEmail("ada@example.com").orElseThrow();
        assertThat(passwordEncoder.matches("a".repeat(PasswordPolicy.MAX_BYTES), saved.getPasswordHash()))
                .isTrue();
    }

    @Test
    void aPasswordOverTheMaximumBytesIsRejectedNotAServerError() throws Exception {
        // This used to end as a 500: BCrypt throws for more than 72 bytes.
        signup("ada@example.com", "a".repeat(PasswordPolicy.MAX_BYTES + 1), "Ada")
                .andExpect(status().isBadRequest());

        assertThat(users.count()).isZero();
    }

    @Test
    void theMaximumIsInBytesNotCharacters() throws Exception {
        // 'é' is 2 bytes in UTF-8: 36 of them are exactly 72 bytes, 37 are 74 bytes but 37 characters.
        signup("ada@example.com", "é".repeat(PasswordPolicy.MAX_BYTES / 2), "Ada")
                .andExpect(status().isCreated());
        signup("grace@example.com", "é".repeat(PasswordPolicy.MAX_BYTES / 2 + 1), "Grace")
                .andExpect(status().isBadRequest());
    }

    // --- helper ----------------------------------------------------------------------------

    private ResultActions signup(String email, String password, String fullName) throws Exception {
        String json =
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"" + fullName + "\"}";
        return mockMvc.perform(
                post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
