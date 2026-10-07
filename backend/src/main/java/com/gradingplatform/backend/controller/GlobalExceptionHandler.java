package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;
import com.gradingplatform.backend.dto.ErrorResponse;
import com.gradingplatform.backend.dto.ErrorResponse.FieldError;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that reaches Spring MVC into the one {@link ErrorResponse} shape (2.6a).
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} covers Spring's own web exceptions (a
 * malformed body, a wrong parameter type, an unsupported method, a missing path...); they all pass
 * through {@link #handleExceptionInternal}, which ignores Spring's message text and answers a fixed
 * one per status, so nothing about the parser, the class names or the rejected value leaks. Our own
 * rules throw an {@link ApiException}. Anything else is a 500 with a generic message; the details go
 * to the log only.
 *
 * <p>Errors that happen outside MVC are shaped elsewhere: the security chain's 401 and 403 in
 * {@code security/JsonAuthenticationEntryPoint} and {@code JsonAccessDeniedHandler}, and container
 * level errors in {@link ApiErrorController}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex) {
        return json(ex.code().status(), ErrorResponse.of(ex.code()), null);
    }

    /**
     * A method-security refusal (`@PreAuthorize`) is thrown from inside the controller call, so it
     * arrives here and not at the security filters. An anonymous caller is 401 and a signed-in one
     * 403, as the filters would have answered.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    ResponseEntity<Object> handleSecurity(Exception ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = ex instanceof AuthenticationException
                || authentication == null
                || trustResolver.isAnonymous(authentication);
        ErrorCode code = anonymous ? ErrorCode.UNAUTHENTICATED : ErrorCode.ACCESS_DENIED;
        return json(code.status(), ErrorResponse.of(code), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        // The only place the cause is recorded; the client gets the generic message.
        log.error("Unhandled exception", ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.of(ErrorCode.INTERNAL_ERROR), null);
    }

    /** A request body that failed bean validation: one entry per invalid field, never the value. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .sorted(FIELD_ORDER)
                .toList();
        return json(HttpStatus.BAD_REQUEST, ErrorResponse.validation(fields), headers);
    }

    /** A request parameter or path variable that failed a constraint such as {@code @Min}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String name = result.getMethodParameter().getParameterName();
                    return result.getResolvableErrors().stream()
                            .map(error -> new FieldError(name == null ? "" : name, error.getDefaultMessage()));
                })
                .sorted(FIELD_ORDER)
                .toList();
        return json(HttpStatus.BAD_REQUEST, ErrorResponse.validation(fields), headers);
    }

    /** Every other Spring web exception: the status Spring chose, our shape and a fixed message. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Server error handled by Spring MVC", ex);
        }
        return json(statusCode, ErrorResponse.of(ErrorCode.forStatus(statusCode)), headers);
    }

    private static final Comparator<FieldError> FIELD_ORDER =
            Comparator.comparing(FieldError::field).thenComparing(error -> String.valueOf(error.message()));

    private static ResponseEntity<Object> json(HttpStatusCode status, ErrorResponse body, HttpHeaders headers) {
        // Keep headers Spring set (for instance `Allow` on a 405), but force our content type.
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, out, status);
    }
}
