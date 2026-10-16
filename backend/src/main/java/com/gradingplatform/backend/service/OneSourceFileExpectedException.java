package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An upload without a file in the part {@code file}, or with more than one: 400. Status, code and message come from {@link ErrorCode#ONE_SOURCE_FILE_EXPECTED}.
 */
public class OneSourceFileExpectedException extends ApiException {

    public OneSourceFileExpectedException() {
        super(ErrorCode.ONE_SOURCE_FILE_EXPECTED);
    }
}
