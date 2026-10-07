package com.gradingplatform.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A valid token whose user has since been deleted: 401 Unauthorized, the same answer as a token
 * that does not verify, so the client's reaction is the same (sign in again). The status is
 * declared here for now; 2.6a's global exception handler will give every error one JSON shape.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class AccountNoLongerExistsException extends RuntimeException {

    public AccountNoLongerExistsException() {
        super("Account no longer exists");
    }
}
