package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * Source code over {@link SubmissionService#SOURCE_MAX_BYTES} bytes of UTF-8, as text or as an uploaded file: 413. Status, code and message come from {@link ErrorCode#SOURCE_TOO_LARGE}.
 */
public class SourceTooLargeException extends ApiException {

    public SourceTooLargeException() {
        super(ErrorCode.SOURCE_TOO_LARGE);
    }
}
