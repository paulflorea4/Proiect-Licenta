package com.gradingplatform.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An account with this email already exists: 409 Conflict. The status is declared here for now;
 * 2.6a's global exception handler will give every error one JSON shape.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException() {
        super("An account with this email already exists");
    }
}
