package com.gradingplatform.backend.dto;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Every kind of API error, as the stable machine-readable {@code code} of {@link ErrorResponse}
 * (2.6a). The frontend branches on the code, never on the message text. A name here is part of the
 * API: rename or remove one only together with the frontend.
 *
 * <p>The first group is generic (one per situation Spring itself can report); the second is
 * specific to our own rules. Add a constant for each new rule that a client must tell apart.
 */
public enum ErrorCode {
    // Generic.
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request is malformed"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request has invalid fields"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to do this"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request was rejected"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),

    // Specific.
    PAGE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Page is out of range"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    ACCOUNT_NO_LONGER_EXISTS(HttpStatus.UNAUTHORIZED, "Account no longer exists"),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "An account with this email already exists"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    LAST_ADMIN(HttpStatus.CONFLICT, "The last remaining admin cannot be demoted");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    /** Fixed, safe text: never built from the request or from an exception message. */
    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * The generic code for an HTTP status Spring reported by itself (an unsupported method, a
     * missing handler...). Statuses without a code of their own become {@link #REQUEST_REJECTED}
     * (4xx) or {@link #INTERNAL_ERROR} (anything else).
     */
    public static ErrorCode forStatus(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }
}
