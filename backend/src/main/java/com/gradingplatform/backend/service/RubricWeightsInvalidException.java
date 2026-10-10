package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The rubric weights do not add up to 100, so the assignment cannot be published: 409 Conflict. Status, code and message come from {@link ErrorCode#RUBRIC_WEIGHTS_INVALID}.
 */
public class RubricWeightsInvalidException extends ApiException {

    public RubricWeightsInvalidException() {
        super(ErrorCode.RUBRIC_WEIGHTS_INVALID);
    }
}
