package com.gradingplatform.backend.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The requested page starts beyond any row the database layer can address (offset above
 * {@link Integer#MAX_VALUE}): 400 Bad Request. Without it the offset would overflow inside JPA and
 * end as a 500. The status is declared here for now; 2.6a's global exception handler will give
 * every error one JSON shape.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PageOutOfRangeException extends RuntimeException {

    public PageOutOfRangeException() {
        super("Page is out of range");
    }
}
