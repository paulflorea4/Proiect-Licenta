package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment already has submissions, so its time and memory limits can no longer be changed
 * (9.0b: a submission still queued would otherwise be graded against limits it was not accepted
 * under): 409 Conflict. Status, code and message come from {@link
 * ErrorCode#ASSIGNMENT_LIMITS_LOCKED}.
 */
public class AssignmentLimitsLockedException extends ApiException {

    public AssignmentLimitsLockedException() {
        super(ErrorCode.ASSIGNMENT_LIMITS_LOCKED);
    }
}
