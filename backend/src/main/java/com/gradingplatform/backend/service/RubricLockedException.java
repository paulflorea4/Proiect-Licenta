package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment already has submissions, so its criteria and weights can no longer be changed (existing grades would silently stop matching them): 409 Conflict. Status, code and message come from {@link ErrorCode#RUBRIC_LOCKED}.
 */
public class RubricLockedException extends ApiException {

    public RubricLockedException() {
        super(ErrorCode.RUBRIC_LOCKED);
    }
}
