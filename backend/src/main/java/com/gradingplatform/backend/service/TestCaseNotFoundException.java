package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * There is no such test case in this assignment: 404 Not Found. Status, code and message come from {@link ErrorCode#TEST_CASE_NOT_FOUND}.
 */
public class TestCaseNotFoundException extends ApiException {

    public TestCaseNotFoundException() {
        super(ErrorCode.TEST_CASE_NOT_FOUND);
    }
}
