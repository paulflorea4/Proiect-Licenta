package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An account with this email already exists: 409 Conflict. Status, code and message come from
 * {@link ErrorCode#EMAIL_ALREADY_USED}.
 */
public class EmailAlreadyUsedException extends ApiException {

    public EmailAlreadyUsedException() {
        super(ErrorCode.EMAIL_ALREADY_USED);
    }
}
