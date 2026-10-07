package com.gradingplatform.backend.dto;

/**
 * Base of every error our own code raises on purpose (2.6a). The {@link ErrorCode} fixes both the
 * HTTP status and the {@code code} the client sees; {@code GlobalExceptionHandler} turns it into an
 * {@link ErrorResponse}. The message is shown to the client, so it must be fixed text, never built
 * from user input.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    protected ApiException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
