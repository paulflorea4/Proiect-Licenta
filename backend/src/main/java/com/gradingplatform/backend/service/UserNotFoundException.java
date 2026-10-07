package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * No user has this id: 404 Not Found. Status, code and message come from
 * {@link ErrorCode#USER_NOT_FOUND}.
 */
public class UserNotFoundException extends ApiException {

    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND);
    }
}
