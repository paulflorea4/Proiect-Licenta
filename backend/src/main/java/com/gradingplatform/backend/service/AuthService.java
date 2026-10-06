package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
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

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
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
}
