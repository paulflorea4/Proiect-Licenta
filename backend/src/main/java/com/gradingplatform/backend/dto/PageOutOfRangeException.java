package com.gradingplatform.backend.dto;

/**
 * The requested page starts beyond any row the database layer can address (offset above
 * {@link Integer#MAX_VALUE}): 400 Bad Request. Without it the offset would overflow inside JPA and
 * end as a 500. Status, code and message come from
 * {@link ErrorCode#PAGE_OUT_OF_RANGE}.
 */
public class PageOutOfRangeException extends ApiException {

    public PageOutOfRangeException() {
        super(ErrorCode.PAGE_OUT_OF_RANGE);
    }
}
