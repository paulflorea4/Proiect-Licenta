package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An uploaded source file over {@link SubmissionService#SOURCE_FILE_MAX_BYTES}: 413. Status, code and message come from {@link ErrorCode#SOURCE_FILE_TOO_LARGE}.
 */
public class SourceFileTooLargeException extends ApiException {

    public SourceFileTooLargeException() {
        super(ErrorCode.SOURCE_FILE_TOO_LARGE);
    }
}
