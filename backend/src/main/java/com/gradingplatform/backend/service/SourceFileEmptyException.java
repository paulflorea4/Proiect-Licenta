package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An uploaded source file that is empty or only whitespace, so it does not cost an attempt: 400. Status, code and message come from {@link ErrorCode#SOURCE_FILE_EMPTY}.
 */
public class SourceFileEmptyException extends ApiException {

    public SourceFileEmptyException() {
        super(ErrorCode.SOURCE_FILE_EMPTY);
    }
}
