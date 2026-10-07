package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /auth/signin`. Only presence and the size limits that no valid credential can
 * exceed are checked here: the password policy is not repeated, so a wrong password is a 401 like
 * any other and never reveals the rules.
 */
public record SigninRequest(
        @NotBlank @Size(max = 255) String email,
        @NotBlank @MaxUtf8Bytes(PasswordPolicy.MAX_BYTES) String password) {

    /** As in signup: stray surrounding whitespace on the email is dropped before validation. */
    public SigninRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    /** Without this, the generated `toString` would print the password into any log line. */
    @Override
    public String toString() {
        return "SigninRequest[email=" + email + ", password=<redacted>]";
    }
}
