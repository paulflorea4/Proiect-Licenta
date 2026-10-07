package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SigninRequest;
import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import com.gradingplatform.backend.security.JwtService;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** The unique constraint on `users.email` (Postgres' default name for V1's `UNIQUE`). */
    static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";

    /** A successful signin: who signed in and the token issued for them. */
    public record SigninResult(User user, JwtService.IssuedToken token) {}

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** A real hash to compare against when the email is unknown, so that case costs the same time. */
    private final String unknownUserHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.unknownUserHash = passwordEncoder.encode("not-the-password-of-any-account");
    }

    /**
     * Emails are compared trimmed and lower-case everywhere (signup now, signin in 2.4a), so
     * `Ada@x.com` and `ada@x.com` are one account. The database's unique constraint alone is
     * case-sensitive.
     */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a user. The role is always {@link Role#STUDENT}; only an admin can promote later.
     *
     * @throws EmailAlreadyUsedException if the (normalised) email is taken
     */
    @Transactional
    public User signup(SignupRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(normalizeEmail(request.email()), passwordHash, request.fullName(), Role.STUDENT);
        try {
            // The unique constraint is the single source of truth: unlike a check before the
            // insert, it also holds when two signups for one email arrive at the same moment.
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (violatesEmailUniqueness(e)) {
                throw new EmailAlreadyUsedException();
            }
            throw e;
        }
    }

    private static boolean violatesEmailUniqueness(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks the credentials and issues a JWT. Deliberately not `@Transactional`: the BCrypt check
     * takes about 100 ms and must not hold a database connection while it runs.
     *
     * @throws InvalidCredentialsException for an unknown email and for a wrong password alike
     */
    public SigninResult signin(SigninRequest request) {
        User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        // Always run one BCrypt comparison, even for an unknown email: otherwise the response time
        // would tell an attacker which emails have an account.
        String hash = user != null ? user.getPasswordHash() : unknownUserHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return new SigninResult(user, jwtService.issue(user));
    }

    /**
     * The current state of the user a token was issued to. Read from the database rather than the
     * token's claims, so a changed name or role shows up at once.
     *
     * @throws AccountNoLongerExistsException if the user has been deleted since the token was issued
     */
    public User currentUser(long userId) {
        return users.findById(userId).orElseThrow(AccountNoLongerExistsException::new);
    }
}
