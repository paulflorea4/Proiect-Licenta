package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * Source code that a {@code TEXT} column cannot store faithfully: a file that is not valid UTF-8, or text (either way) with a NUL character or an unpaired surrogate: 400. Status, code and message come from {@link ErrorCode#SOURCE_NOT_TEXT}.
 */
public class SourceNotTextException extends ApiException {

    public SourceNotTextException() {
        super(ErrorCode.SOURCE_NOT_TEXT);
    }
}
