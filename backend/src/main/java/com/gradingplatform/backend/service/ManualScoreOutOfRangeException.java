package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A manual score below 0 or above the criterion's weight: 400 Bad Request. Status, code and message come from {@link ErrorCode#MANUAL_SCORE_OUT_OF_RANGE}.
 */
public class ManualScoreOutOfRangeException extends ApiException {

    public ManualScoreOutOfRangeException() {
        super(ErrorCode.MANUAL_SCORE_OUT_OF_RANGE);
    }
}
