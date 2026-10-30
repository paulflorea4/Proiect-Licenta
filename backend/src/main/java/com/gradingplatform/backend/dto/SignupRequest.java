package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Body of `POST /auth/signup`. There is deliberately no `role` field: a role sent by the client is
 * ignored, and signup always creates a student. The length limits on `email` and `fullName` match
 * their VARCHAR(255) columns, so an over-long value is a 400 instead of a database error.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) @NoNul String email,

        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH) @MaxUtf8Bytes(PasswordPolicy.MAX_BYTES)
        String password,

        @NotBlank @Size(max = 255) @NoNul String fullName) {

    /**
     * The email is normalized here, before validation runs, exactly as `AuthService.normalizeEmail`
     * does (`SignupRequestTests` keeps the two the same): surrounding whitespace (a typical
     * copy-paste slip) is dropped, so it is not reported as an invalid address, and it is
     * lower-cased, so `@Size` measures what is stored. Lower-casing can lengthen it (`İ` becomes
     * two characters), which used to pass the check and fail the insert with a 500 (8.0d).
     */
    public SignupRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Without this, the generated `toString` would print the password into any log line. */
    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=<redacted>, fullName=" + fullName + "]";
    }
}
