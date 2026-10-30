package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 8.0d: the signup body normalizes the email before validation, so `@Size` measures what is
 * stored; the normalization must stay the one `AuthService` uses everywhere else.
 */
class SignupRequestTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ada@example.com",
                "  Ada@Example.COM\t",
                "İnci@example.com",
                "STRASSE@EXAMPLE.COM",
                "ΣΊΣΥΦΟΣ@example.com",
                " nbsp@example.com"
            })
    void theEmailIsNormalizedExactlyAsAuthServiceDoes(String typed) {
        assertThat(new SignupRequest(typed, "correct horse", "Ada").email())
                .isEqualTo(AuthService.normalizeEmail(typed));
    }

    @Test
    void aDottedCapitalIBecomesTwoCharactersSoTheLengthIsTheNormalizedOne() {
        assertThat(new SignupRequest("İ@x.io", "correct horse", "Ada").email())
                .hasSize(7)
                .isEqualTo("i̇@x.io");
    }

    @Test
    void aMissingEmailIsLeftForTheNotBlankCheck() {
        assertThat(new SignupRequest(null, "correct horse", "Ada").email()).isNull();
    }
}
