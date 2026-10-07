package com.gradingplatform.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No user has this id: 404 Not Found. The status is declared here for now; 2.6a's global exception
 * handler will give every error one JSON shape.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User not found");
    }
}
