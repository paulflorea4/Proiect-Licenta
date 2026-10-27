package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The submission has no stored hint at this level (7.4b): 404 Not Found. A level the student never
 * received, a fallback that was not stored, and a level outside 1-3 all look alike. Status, code and message come from {@link ErrorCode#HINT_NOT_FOUND}.
 */
public class HintNotFoundException extends ApiException {

    public HintNotFoundException() {
        super(ErrorCode.HINT_NOT_FOUND);
    }
}
