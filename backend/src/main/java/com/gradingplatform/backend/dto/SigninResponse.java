package com.gradingplatform.backend.dto;

/**
 * Body of a successful signin. The token itself is not here: it is set as an {@code httpOnly}
 * cookie ({@code security.AuthCookie}, 8.2b), so no script ever reads it, this response included.
 *
 * @param expiresIn seconds until the token (and its cookie) expire (there is no refresh flow)
 * @param user who signed in
 */
public record SigninResponse(long expiresIn, UserResponse user) {}
