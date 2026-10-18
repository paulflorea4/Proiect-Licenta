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
    REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request body is too large"),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request was rejected"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),

    // Specific.
    PAGE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Page is out of range"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    ACCOUNT_NO_LONGER_EXISTS(HttpStatus.UNAUTHORIZED, "Account no longer exists"),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "An account with this email already exists"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    LAST_ADMIN(HttpStatus.CONFLICT, "The last remaining admin cannot be demoted"),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Course not found"),
    ENROLL_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "No course has this enrollment code"),
    COURSE_HAS_ASSIGNMENTS(
            HttpStatus.CONFLICT, "The course has assignments and cannot be deleted; delete its assignments first"),
    ASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Assignment not found"),
    ASSIGNMENT_HAS_SUBMISSIONS(HttpStatus.CONFLICT, "The assignment has submissions and cannot be deleted"),
    ASSIGNMENT_LANGUAGE_LOCKED(
            HttpStatus.CONFLICT, "The assignment has submissions, so its language can no longer be changed"),
    CRITERION_NOT_FOUND(HttpStatus.NOT_FOUND, "Rubric criterion not found"),
    CRITERION_TYPE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "This criterion type is not available yet"),
    RUBRIC_LOCKED(HttpStatus.CONFLICT, "The assignment has submissions, so its rubric can no longer be changed"),
    CRITERION_HAS_TESTS(
            HttpStatus.CONFLICT, "The criterion has test cases and cannot be deleted; delete its tests first"),
    CRITERION_TYPE_HAS_TESTS(
            HttpStatus.CONFLICT,
            "The criterion has test cases, so it must stay a tests criterion; delete its tests first"),
    RUBRIC_WEIGHTS_INVALID(
            HttpStatus.CONFLICT, "The rubric weights must add up to 100 before the assignment can be published"),
    CRITERION_HAS_NO_TESTS(
            HttpStatus.CONFLICT,
            "Every tests criterion needs at least one test case before the assignment can be published"),
    ASSIGNMENT_PUBLISHED(
            HttpStatus.CONFLICT, "The assignment is published; unpublish it before changing its rubric or tests"),
    TESTS_LOCKED(HttpStatus.CONFLICT, "The assignment has submissions, so its test cases can no longer be changed"),
    TEST_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "Test case not found"),
    TEST_CRITERION_INVALID(HttpStatus.BAD_REQUEST, "The criterion must be a tests criterion of this assignment"),
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "The language is not supported"),
    DEADLINE_NOT_IN_FUTURE(HttpStatus.BAD_REQUEST, "The deadline must be in the future"),
    ONE_SOURCE_FILE_EXPECTED(HttpStatus.BAD_REQUEST, "Exactly one source file is expected, in the part named file"),
    SOURCE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The source code is too large"),
    SOURCE_NOT_TEXT(HttpStatus.BAD_REQUEST, "The source code must be UTF-8 text without NUL characters"),
    SOURCE_FILE_EMPTY(HttpStatus.BAD_REQUEST, "The source file is empty"),
    SOURCE_FILE_EXTENSION_MISMATCH(
            HttpStatus.BAD_REQUEST, "The file's extension does not match the assignment's language"),
    SUBMISSION_DEADLINE_PASSED(
            HttpStatus.CONFLICT, "The deadline for this assignment has passed; submissions are no longer accepted"),
    ATTEMPT_LIMIT_REACHED(HttpStatus.CONFLICT, "All the attempts allowed for this assignment have been used"),
    SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Submission not found"),
    SUBMISSION_NOT_GRADED(HttpStatus.CONFLICT, "The submission has not been graded, so it cannot be scored by hand"),
    CRITERION_NOT_MANUAL(HttpStatus.BAD_REQUEST, "Only a manual criterion can be scored by hand"),
    MANUAL_SCORE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "The score must be between 0 and the criterion's weight");

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
            case 413 -> REQUEST_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }
}
