package com.gradingplatform.backend.security;

import com.gradingplatform.backend.entity.Role;

/**
 * The caller as a verified token describes them: the principal of an authenticated request. Built
 * from the token's claims alone (no database lookup), so it is only as fresh as the token.
 */
public record AuthenticatedUser(long id, String email, Role role) {}
