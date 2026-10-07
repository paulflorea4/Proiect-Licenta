package com.gradingplatform.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;
import com.gradingplatform.backend.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 2.6a: the pieces of the error contract that do not need a server. */
class ErrorPiecesTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // --- ErrorCode -----------------------------------------------------------------------------

    @Test
    void everyCodeHasAnErrorStatusAndAFixedMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status().isError()).as(code.name()).isTrue();
            assertThat(code.defaultMessage()).as(code.name()).isNotBlank();
        }
    }

    @Test
    void aSpringStatusMapsToItsGenericCode() {
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_REQUEST)).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        // No code of their own: 4xx are "rejected", everything else is an internal error.
        assertThat(ErrorCode.forStatus(HttpStatus.PAYLOAD_TOO_LARGE)).isEqualTo(ErrorCode.REQUEST_REJECTED);
        assertThat(ErrorCode.forStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatusCode.valueOf(599))).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void anErrorResponseCopiesTheStatusAndNameOfItsCode() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.LAST_ADMIN);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("LAST_ADMIN");
        assertThat(response.message()).isEqualTo(ErrorCode.LAST_ADMIN.defaultMessage());
        assertThat(response.fieldErrors()).isEmpty();
    }

    // --- GlobalExceptionHandler ----------------------------------------------------------------

    @Test
    void theHandlerCoversEverySpringWebException() {
        // Extending the base class is what makes Spring's own exceptions use our shape.
        assertThat(handler).isInstanceOf(ResponseEntityExceptionHandler.class);
    }

    @Test
    void anApiExceptionIsAnswerWithItsCodeAndStatus() {
        var response = handler.handleApiException(new TestApiException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(ErrorResponse.of(ErrorCode.EMAIL_ALREADY_USED));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
    }

    @Test
    void anAuthenticationFailureIs401() {
        var response = handler.handleSecurity(new BadCredentialsException("secret detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(ErrorResponse.of(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void aRefusalIs401ForAnonymousAnd403ForASignedInCaller() {
        try {
            SecurityContextHolder.clearContext();
            assertThat(handler.handleSecurity(new AccessDeniedException("x")).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            var signedIn = new TestingAuthenticationToken("ada", "pw", "ROLE_STUDENT");
            SecurityContextHolder.getContext().setAuthentication(signedIn);
            var response = handler.handleSecurity(new AccessDeniedException("x"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(ErrorResponse.of(ErrorCode.ACCESS_DENIED));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anUnexpectedExceptionIsAGenericMessage() {
        var response = handler.handleUnexpected(new IllegalStateException("jdbc:postgresql://secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    // --- ApiErrorController --------------------------------------------------------------------

    @Test
    void theContainerErrorPageUsesTheStatusTheContainerRecorded() {
        var controller = new ApiErrorController();

        assertThat(controller.error(requestWithErrorStatus(400)).getBody())
                .isEqualTo(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
        assertThat(controller.error(requestWithErrorStatus(404)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aMissingOrNonErrorStatusBecomes500() {
        var controller = new ApiErrorController();

        assertThat(controller.error(new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.error(requestWithErrorStatus(200)).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.error(requestWithErrorStatus(999)).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(controller.error(requestWithErrorStatus(500)).getBody())
                .isEqualTo(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    private static MockHttpServletRequest requestWithErrorStatus(int status) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return request;
    }

    private static class TestApiException extends ApiException {
        TestApiException() {
            super(ErrorCode.EMAIL_ALREADY_USED);
        }
    }
}
