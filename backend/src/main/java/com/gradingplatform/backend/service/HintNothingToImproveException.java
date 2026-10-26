package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * Every test of the graded submission passed, so it gets no hint (7.2c): 409 Conflict. Judged on the test results, not on the total. Status, code and message come from {@link ErrorCode#HINT_NOTHING_TO_IMPROVE}.
 */
public class HintNothingToImproveException extends ApiException {

    public HintNothingToImproveException() {
        super(ErrorCode.HINT_NOTHING_TO_IMPROVE);
    }
}
