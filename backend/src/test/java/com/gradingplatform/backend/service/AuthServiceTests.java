package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gradingplatform.backend.dto.SigninRequest;
import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.security.JwtService;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 2.3c / 2.4a: how `AuthService` handles integrity violations on signup and credentials on signin. */
class AuthServiceTests {

    private static final String UNKNOWN_USER_HASH = "hash-compared-when-the-email-is-unknown";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final SignupRequest signup = new SignupRequest("ada@example.com", "correct horse", "Ada");

    private AuthService service;

    @BeforeEach
    void createService() {
        when(passwordEncoder.encode(any())).thenReturn(UNKNOWN_USER_HASH);
        service = new AuthService(users, passwordEncoder, jwtService);
    }

    // --- signup: duplicate email -------------------------------------------------------------

    @Test
    void theEmailUniqueConstraintBecomesEmailAlreadyUsed() {
        when(users.saveAndFlush(any(User.class))).thenThrow(violationOf("users_email_key"));

        assertThatThrownBy(() -> service.signup(signup)).isInstanceOf(EmailAlreadyUsedException.class);
    }

    @Test
    void anyOtherConstraintViolationIsNotMistakenForADuplicateEmail() {
        DataIntegrityViolationException other = violationOf("users_role_not_null");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> service.signup(signup)).isSameAs(other);
    }

    @Test
    void anIntegrityViolationWithoutAConstraintNameIsRethrownAsIs() {
        DataIntegrityViolationException plain = new DataIntegrityViolationException("something else");
        when(users.saveAndFlush(any(User.class))).thenThrow(plain);

        assertThatThrownBy(() -> service.signup(signup)).isSameAs(plain);
    }

    // --- email normalisation -----------------------------------------------------------------

    @Test
    void normalizeEmailTrimsAndLowerCases() {
        assertThat(AuthService.normalizeEmail("  Ada@Example.COM ")).isEqualTo("ada@example.com");
    }

    @Test
    void normalizeEmailIsLocaleIndependent() {
        // In a Turkish locale "I".toLowerCase() would give a dotless i; ROOT must be used.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(AuthService.normalizeEmail("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    // --- signin ------------------------------------------------------------------------------

    @Test
    void signinWithTheRightPasswordReturnsTheUserAndAToken() {
        User ada = new User("ada@example.com", "ada-hash", "Ada", Role.STUDENT);
        JwtService.IssuedToken token = new JwtService.IssuedToken("jwt", Instant.EPOCH, Duration.ofHours(24));
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(passwordEncoder.matches("correct horse", "ada-hash")).thenReturn(true);
        when(jwtService.issue(ada)).thenReturn(token);

        AuthService.SigninResult result = service.signin(new SigninRequest("ada@example.com", "correct horse"));

        assertThat(result.user()).isSameAs(ada);
        assertThat(result.token()).isSameAs(token);
    }

    @Test
    void signinLooksTheEmailUpNormalised() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.signin(new SigninRequest("  ADA@Example.com ", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(users).findByEmail("ada@example.com");
    }

    @Test
    void aWrongPasswordIsRejectedAndNoTokenIsIssued() {
        User ada = new User("ada@example.com", "ada-hash", "Ada", Role.STUDENT);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(passwordEncoder.matches("wrong", "ada-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.signin(new SigninRequest("ada@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtService, never()).issue(any());
    }

    @Test
    void anUnknownEmailIsRejectedLikeAWrongPasswordAndStillCostsOneBcryptComparison() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        // Even if the encoder said "matches", an unknown email must never sign in.
        when(passwordEncoder.matches(any(), eq(UNKNOWN_USER_HASH))).thenReturn(true);

        assertThatThrownBy(() -> service.signin(new SigninRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(new InvalidCredentialsException().getMessage());

        verify(passwordEncoder).matches("whatever", UNKNOWN_USER_HASH);
        verify(jwtService, never()).issue(any());
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        ConstraintViolationException cause =
                new ConstraintViolationException("violation", new SQLException("violation"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
