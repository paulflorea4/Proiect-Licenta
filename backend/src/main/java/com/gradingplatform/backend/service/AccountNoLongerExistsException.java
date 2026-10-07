package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A valid token whose user has since been deleted: 401 Unauthorized, the same status as a token
 * that does not verify, so the client's reaction is the same (sign in again). Status, code and message come from
 * {@link ErrorCode#ACCOUNT_NO_LONGER_EXISTS}.
 */
public class AccountNoLongerExistsException extends ApiException {

    public AccountNoLongerExistsException() {
        super(ErrorCode.ACCOUNT_NO_LONGER_EXISTS);
    }
}
