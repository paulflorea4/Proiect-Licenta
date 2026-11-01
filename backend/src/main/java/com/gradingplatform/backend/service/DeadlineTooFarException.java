package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The deadline is more than {@code AssignmentRequest.DEADLINE_MAX_AHEAD} after the current time
 * (8.0e): 400. Code and message come from {@link ErrorCode#DEADLINE_TOO_FAR}; the deadline and the
 * limit are not echoed.
 */
public class DeadlineTooFarException extends ApiException {

    public DeadlineTooFarException() {
        super(ErrorCode.DEADLINE_TOO_FAR);
    }
}
