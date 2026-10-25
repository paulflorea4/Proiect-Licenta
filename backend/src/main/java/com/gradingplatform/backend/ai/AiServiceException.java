package com.gradingplatform.backend.ai;

/**
 * The AI service gave no usable answer (7.1a). The message is fixed text: never the token, the
 * request (it holds the student's code) or the service's response body.
 */
public class AiServiceException extends RuntimeException {

    private final Integer status;

    AiServiceException(String message, Integer status) {
        super(message);
        this.status = status;
    }

    AiServiceException(String message, Throwable cause) {
        super(message, cause);
        this.status = null;
    }

    /** The HTTP status the service answered with, or null when there was no answer to read. */
    public Integer status() {
        return status;
    }
}
