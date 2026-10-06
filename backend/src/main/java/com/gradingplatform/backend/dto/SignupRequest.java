package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /auth/signup`. There is deliberately no `role` field: a role sent by the client is
 * ignored, and signup always creates a student. The length limits on `email` and `fullName` match
 * their VARCHAR(255) columns, so an over-long value is a 400 instead of a database error.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH) @MaxUtf8Bytes(PasswordPolicy.MAX_BYTES)
        String password,

        @NotBlank @Size(max = 255) String fullName) {

    /**
     * Surrounding whitespace on the email (a typical copy-paste slip) is dropped here, before
     * validation runs, so it is not reported as an invalid address. Lower-casing happens in
     * `AuthService.normalizeEmail`.
     */
    public SignupRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    /** Without this, the generated `toString` would print the password into any log line. */
    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=<redacted>, fullName=" + fullName + "]";
    }
}
