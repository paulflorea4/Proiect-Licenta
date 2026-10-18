package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The submission is not `GRADED` (waiting, being graded, or failed), so it cannot be scored by hand: 409 Conflict. Status, code and message come from {@link ErrorCode#SUBMISSION_NOT_GRADED}.
 */
public class SubmissionNotGradedException extends ApiException {

    public SubmissionNotGradedException() {
        super(ErrorCode.SUBMISSION_NOT_GRADED);
    }
}
