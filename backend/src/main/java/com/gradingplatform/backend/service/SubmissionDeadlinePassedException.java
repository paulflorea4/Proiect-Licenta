package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A submission that arrived after the assignment's deadline: 409. Status, code and message come from {@link ErrorCode#SUBMISSION_DEADLINE_PASSED}.
 */
public class SubmissionDeadlinePassedException extends ApiException {

    public SubmissionDeadlinePassedException() {
        super(ErrorCode.SUBMISSION_DEADLINE_PASSED);
    }
}
