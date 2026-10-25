package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment's memory limit is below its language's {@code min-memory-mb} (7.1c): 400. Status,
 * code and message come from {@link ErrorCode#MEMORY_LIMIT_BELOW_LANGUAGE_MINIMUM}; neither the value
 * nor the minimum is echoed.
 */
public class MemoryLimitBelowLanguageMinimumException extends ApiException {

    public MemoryLimitBelowLanguageMinimumException() {
        super(ErrorCode.MEMORY_LIMIT_BELOW_LANGUAGE_MINIMUM);
    }
}
