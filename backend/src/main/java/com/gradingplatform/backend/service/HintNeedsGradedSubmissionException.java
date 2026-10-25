package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The submission is not `GRADED` yet (waiting, being graded, or failed), so it gets no hint (7.2a): 409 Conflict. Status, code and message come from {@link ErrorCode#HINT_NEEDS_GRADED_SUBMISSION}.
 */
public class HintNeedsGradedSubmissionException extends ApiException {

    public HintNeedsGradedSubmissionException() {
        super(ErrorCode.HINT_NEEDS_GRADED_SUBMISSION);
    }
}
