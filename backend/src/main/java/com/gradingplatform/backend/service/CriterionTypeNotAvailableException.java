package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The criterion type exists but no phase has enabled it yet: 400 Bad Request. Status, code and message come from {@link ErrorCode#CRITERION_TYPE_NOT_AVAILABLE}.
 */
public class CriterionTypeNotAvailableException extends ApiException {

    public CriterionTypeNotAvailableException() {
        super(ErrorCode.CRITERION_TYPE_NOT_AVAILABLE);
    }
}
