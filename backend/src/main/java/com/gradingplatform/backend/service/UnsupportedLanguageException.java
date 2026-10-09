package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The language is not in the supported list: 400. Status, code and message come from
 * {@link ErrorCode#UNSUPPORTED_LANGUAGE}; the rejected value is not echoed.
 */
public class UnsupportedLanguageException extends ApiException {

    public UnsupportedLanguageException() {
        super(ErrorCode.UNSUPPORTED_LANGUAGE);
    }
}
