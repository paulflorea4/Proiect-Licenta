package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The criterion is not a `MANUAL` one, so it cannot be scored by hand: 400 Bad Request. Status, code and message come from {@link ErrorCode#CRITERION_NOT_MANUAL}.
 */
public class CriterionNotManualException extends ApiException {

    public CriterionNotManualException() {
        super(ErrorCode.CRITERION_NOT_MANUAL);
    }
}
