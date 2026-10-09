package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/** The deadline is not after the current time: 400. Code and message come from {@link ErrorCode#DEADLINE_NOT_IN_FUTURE}. */
public class DeadlineNotInFutureException extends ApiException {

    public DeadlineNotInFutureException() {
        super(ErrorCode.DEADLINE_NOT_IN_FUTURE);
    }
}
