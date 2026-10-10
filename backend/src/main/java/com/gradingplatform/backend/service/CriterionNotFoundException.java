package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * There is no such rubric criterion in this assignment: 404 Not Found. Status, code and message come from {@link ErrorCode#CRITERION_NOT_FOUND}.
 */
public class CriterionNotFoundException extends ApiException {

    public CriterionNotFoundException() {
        super(ErrorCode.CRITERION_NOT_FOUND);
    }
}
