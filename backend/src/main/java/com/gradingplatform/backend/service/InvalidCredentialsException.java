package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * Signin failed: 401 Unauthorized. One exception and one message for "no such email" and "wrong
 * password", so the response never says which. Status, code and message come from
 * {@link ErrorCode#INVALID_CREDENTIALS}.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}
