package com.gradingplatform.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signin failed: 401 Unauthorized. One exception and one message for "no such email" and "wrong
 * password", so the response never says which. The status is declared here for now; 2.6a's global
 * exception handler will give every error one JSON shape.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
