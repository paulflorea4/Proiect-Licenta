package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The AI service gave no usable hint (down, too slow, an error status or an unreadable answer,
 * 7.3a): 503 Service Unavailable. Nothing was stored and the submission and its grade are untouched.
 * Status, code and message come from {@link ErrorCode#HINTS_UNAVAILABLE}; the reason goes to the log only.
 */
public class HintsUnavailableException extends ApiException {

    public HintsUnavailableException() {
        super(ErrorCode.HINTS_UNAVAILABLE);
    }
}
