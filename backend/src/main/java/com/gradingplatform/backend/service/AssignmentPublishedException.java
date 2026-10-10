package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The assignment is published, so its rubric and tests cannot be changed until it is unpublished: 409 Conflict. Status, code and message come from {@link ErrorCode#ASSIGNMENT_PUBLISHED}.
 */
public class AssignmentPublishedException extends ApiException {

    public AssignmentPublishedException() {
        super(ErrorCode.ASSIGNMENT_PUBLISHED);
    }
}
