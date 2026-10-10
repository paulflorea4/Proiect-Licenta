package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment already has submissions, so it cannot be deleted
 * (existing grades would silently stop matching it): 409 Conflict. Status, code and message come
 * from {@link ErrorCode#ASSIGNMENT_HAS_SUBMISSIONS}.
 */
public class AssignmentHasSubmissionsException extends ApiException {

    public AssignmentHasSubmissionsException() {
        super(ErrorCode.ASSIGNMENT_HAS_SUBMISSIONS);
    }
}
