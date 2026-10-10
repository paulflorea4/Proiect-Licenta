package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment already has submissions, so its test cases can no longer be added, changed or deleted (existing results refer to them): 409 Conflict. Status, code and message come from {@link ErrorCode#TESTS_LOCKED}.
 */
public class TestsLockedException extends ApiException {

    public TestsLockedException() {
        super(ErrorCode.TESTS_LOCKED);
    }
}
