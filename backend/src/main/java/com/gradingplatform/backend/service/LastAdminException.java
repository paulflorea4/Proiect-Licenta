package com.gradingplatform.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The change would leave the system with no admin, and nobody could promote a teacher any more:
 * 409 Conflict. The status is declared here for now; 2.6a's global exception handler will give
 * every error one JSON shape.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class LastAdminException extends RuntimeException {

    public LastAdminException() {
        super("The last remaining admin cannot be demoted");
    }
}
