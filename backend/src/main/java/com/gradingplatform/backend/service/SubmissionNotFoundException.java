package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * There is no such submission, or the user may not see it: 404 Not Found. Status, code and message come from {@link ErrorCode#SUBMISSION_NOT_FOUND}.
 */
public class SubmissionNotFoundException extends ApiException {

    public SubmissionNotFoundException() {
        super(ErrorCode.SUBMISSION_NOT_FOUND);
    }
}
