package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The submission did not end in `ERROR` (waiting, being graded, graded, or re-run by someone else a
 * moment ago), so it cannot be re-run (9.0a): 409 Conflict. Status, code and message come from
 * {@link ErrorCode#SUBMISSION_NOT_IN_ERROR}.
 */
public class SubmissionNotInErrorException extends ApiException {

    public SubmissionNotInErrorException() {
        super(ErrorCode.SUBMISSION_NOT_IN_ERROR);
    }
}
