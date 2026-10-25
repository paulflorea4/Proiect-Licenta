package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The submission already has its three hints (levels 1, 2 and 3, 7.2a): 409 Conflict. Status, code and message come from {@link ErrorCode#HINTS_USED_UP}.
 */
public class HintsUsedUpException extends ApiException {

    public HintsUsedUpException() {
        super(ErrorCode.HINTS_USED_UP);
    }
}
