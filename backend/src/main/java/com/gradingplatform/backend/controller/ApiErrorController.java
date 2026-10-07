package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.ErrorCode;
import com.gradingplatform.backend.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the servlet container sends an error that never went through Spring MVC (a request the
 * container itself refused, such as a malformed URL). Replaces Spring Boot's default error page so
 * those answers also use {@link ErrorResponse}. Errors raised inside MVC are shaped by
 * {@link GlobalExceptionHandler} and do not come here. The path is public in the security chain
 * (2.4a).
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        HttpStatusCode status = statusOf(request);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(ErrorCode.forStatus(status)));
    }

    private static HttpStatusCode statusOf(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
