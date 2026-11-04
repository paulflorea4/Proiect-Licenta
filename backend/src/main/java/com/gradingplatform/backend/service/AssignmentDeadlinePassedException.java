package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * A draft without submissions cannot be published once its deadline has passed (9.0e): every
 * submission to it would be refused. 409 Conflict. Status, code and message come from {@link
 * ErrorCode#ASSIGNMENT_DEADLINE_PASSED}.
 */
public class AssignmentDeadlinePassedException extends ApiException {

    public AssignmentDeadlinePassedException() {
        super(ErrorCode.ASSIGNMENT_DEADLINE_PASSED);
    }
}
