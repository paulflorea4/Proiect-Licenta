package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * Test cases still belong to the criterion, so it cannot be deleted: 409 Conflict (no silent cascade over the teacher's tests). Status, code and message come from {@link ErrorCode#CRITERION_HAS_TESTS}.
 */
public class CriterionHasTestsException extends ApiException {

    public CriterionHasTestsException() {
        super(ErrorCode.CRITERION_HAS_TESTS);
    }
}
