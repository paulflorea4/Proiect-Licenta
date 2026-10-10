package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The criterion given for a test case is not a `TESTS` criterion of this assignment (it does not exist, belongs to another assignment, or has another type; the answer is the same for all three): 400 Bad Request. Status, code and message come from {@link ErrorCode#TEST_CRITERION_INVALID}.
 */
public class TestCriterionInvalidException extends ApiException {

    public TestCriterionInvalidException() {
        super(ErrorCode.TEST_CRITERION_INVALID);
    }
}
