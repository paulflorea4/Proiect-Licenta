package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A `TESTS` criterion has no test case, so its score would be 0 / 0 and the assignment cannot be published: 409 Conflict. Status, code and message come from {@link ErrorCode#CRITERION_HAS_NO_TESTS}.
 */
public class CriterionHasNoTestsException extends ApiException {

    public CriterionHasNoTestsException() {
        super(ErrorCode.CRITERION_HAS_NO_TESTS);
    }
}
