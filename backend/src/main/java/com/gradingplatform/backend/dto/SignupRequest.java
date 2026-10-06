package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of `POST /auth/signup`. There is deliberately no `role` field: a role sent by the client is
 * ignored, and signup always creates a student.
 */
public record SignupRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String fullName) {

    /** Without this, the generated `toString` would print the password into any log line. */
    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=<redacted>, fullName=" + fullName + "]";
    }
}
