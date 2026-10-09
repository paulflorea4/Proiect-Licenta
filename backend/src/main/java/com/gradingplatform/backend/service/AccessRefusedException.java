package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The caller can see the thing but may not do this to it: 403 Forbidden. Only for a caller who
 * already knows the thing exists (otherwise the answer is a 404, see {@link CourseNotFoundException}).
 * Status, code and message come from {@link ErrorCode#ACCESS_DENIED}.
 */
public class AccessRefusedException extends ApiException {

    public AccessRefusedException() {
        super(ErrorCode.ACCESS_DENIED);
    }
}
