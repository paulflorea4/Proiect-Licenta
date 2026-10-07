package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The change would leave the system with no admin, and nobody could promote a teacher any more:
 * 409 Conflict. Status, code and message come from
 * {@link ErrorCode#LAST_ADMIN}.
 */
public class LastAdminException extends ApiException {

    public LastAdminException() {
        super(ErrorCode.LAST_ADMIN);
    }
}
