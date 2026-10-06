package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 2.3c: how `AuthService` tells a duplicate email from any other integrity violation. */
class AuthServiceTests {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuthService service = new AuthService(users, passwordEncoder);
    private final SignupRequest request = new SignupRequest("ada@example.com", "correct horse", "Ada");

    @Test
    void theEmailUniqueConstraintBecomesEmailAlreadyUsed() {
        when(users.saveAndFlush(any(User.class))).thenThrow(violationOf("users_email_key"));

        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(EmailAlreadyUsedException.class);
    }

    @Test
    void anyOtherConstraintViolationIsNotMistakenForADuplicateEmail() {
        DataIntegrityViolationException other = violationOf("users_role_not_null");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> service.signup(request)).isSameAs(other);
    }

    @Test
    void anIntegrityViolationWithoutAConstraintNameIsRethrownAsIs() {
        DataIntegrityViolationException plain = new DataIntegrityViolationException("something else");
        when(users.saveAndFlush(any(User.class))).thenThrow(plain);

        assertThatThrownBy(() -> service.signup(request)).isSameAs(plain);
    }

    @Test
    void normalizeEmailTrimsAndLowerCases() {
        assertThat(AuthService.normalizeEmail("  Ada@Example.COM ")).isEqualTo("ada@example.com");
    }

    @Test
    void normalizeEmailIsLocaleIndependent() {
        // In a Turkish locale "I".toLowerCase() would give a dotless i; ROOT must be used.
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(AuthService.normalizeEmail("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        ConstraintViolationException cause =
                new ConstraintViolationException("violation", new SQLException("violation"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
