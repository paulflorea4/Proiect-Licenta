package com.gradingplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON shape of every error the API answers (2.6a, chosen by the human), whoever produces
 * it: a controller, validation, the security chain or Spring itself.
 *
 * <pre>{"status":409,"code":"EMAIL_ALREADY_USED","message":"An account with this email already exists"}
 * {"status":400,"code":"VALIDATION_FAILED","message":"...","fieldErrors":[{"field":"email","message":"must not be blank"}]}</pre>
 *
 * @param status the HTTP status, repeated in the body
 * @param code stable machine-readable name, see {@link ErrorCode}; clients branch on this
 * @param message fixed human-readable text for the code; never user input, a stack trace or an SQL
 *     message
 * @param fieldErrors which request fields were invalid and why; present only for
 *     {@link ErrorCode#VALIDATION_FAILED}, left out otherwise. The rejected value is never echoed
 *     (it may be a password).
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> fieldErrors) {

    /** One invalid field (or request parameter) and the reason. */
    public record FieldError(String field, String message) {}

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.status().value(), code.name(), code.defaultMessage(), List.of());
    }

    public static ErrorResponse validation(List<FieldError> fieldErrors) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ErrorResponse(code.status().value(), code.name(), code.defaultMessage(), fieldErrors);
    }
}
