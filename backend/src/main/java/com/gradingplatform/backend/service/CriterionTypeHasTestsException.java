package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A `TESTS` criterion that still has test cases cannot become another type: 409 Conflict. Status, code and message come from {@link ErrorCode#CRITERION_TYPE_HAS_TESTS}.
 */
public class CriterionTypeHasTestsException extends ApiException {

    public CriterionTypeHasTestsException() {
        super(ErrorCode.CRITERION_TYPE_HAS_TESTS);
    }
}
