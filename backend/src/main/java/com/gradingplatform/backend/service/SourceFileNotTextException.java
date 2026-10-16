package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * An uploaded source file that is not valid UTF-8 or contains a NUL character (which a {@code TEXT} column cannot hold): 400. Status, code and message come from {@link ErrorCode#SOURCE_FILE_NOT_TEXT}.
 */
public class SourceFileNotTextException extends ApiException {

    public SourceFileNotTextException() {
        super(ErrorCode.SOURCE_FILE_NOT_TEXT);
    }
}
