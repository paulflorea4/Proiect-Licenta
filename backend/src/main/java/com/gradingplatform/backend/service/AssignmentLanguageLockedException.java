package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment already has submissions, so its language can no longer be changed
 * (existing grades would silently stop matching it): 409 Conflict. Status, code and message come
 * from {@link ErrorCode#ASSIGNMENT_LANGUAGE_LOCKED}.
 */
public class AssignmentLanguageLockedException extends ApiException {

    public AssignmentLanguageLockedException() {
        super(ErrorCode.ASSIGNMENT_LANGUAGE_LOCKED);
    }
}
