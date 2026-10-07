package com.gradingplatform.backend.dto;

/**
 * Body of a successful signin. The token is returned in the body only (no cookie); the client
 * sends it back as `Authorization: Bearer <token>`.
 *
 * @param token the signed JWT
 * @param tokenType always `Bearer`
 * @param expiresIn seconds until the token expires (there is no refresh flow)
 * @param user who signed in
 */
public record SigninResponse(String token, String tokenType, long expiresIn, UserResponse user) {

    public static final String BEARER = "Bearer";

    /** Never print the token into a log line. */
    @Override
    public String toString() {
        return "SigninResponse[token=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", user=" + user
                + "]";
    }
}
