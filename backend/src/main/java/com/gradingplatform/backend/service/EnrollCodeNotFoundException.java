package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * No course has this enrollment code: 404 Not Found, not 403, so the answer cannot be used to tell
 * a wrong code from one that exists but is refused. Status, code and message come from
 * {@link ErrorCode#ENROLL_CODE_NOT_FOUND}.
 */
public class EnrollCodeNotFoundException extends ApiException {

    public EnrollCodeNotFoundException() {
        super(ErrorCode.ENROLL_CODE_NOT_FOUND);
    }
}
