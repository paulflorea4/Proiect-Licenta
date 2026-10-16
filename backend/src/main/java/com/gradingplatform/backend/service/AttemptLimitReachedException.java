package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A submission beyond the assignment's {@code max_attempts} for this student: 409. Status, code and message come from {@link ErrorCode#ATTEMPT_LIMIT_REACHED}.
 */
public class AttemptLimitReachedException extends ApiException {

    public AttemptLimitReachedException() {
        super(ErrorCode.ATTEMPT_LIMIT_REACHED);
    }
}
