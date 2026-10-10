package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * There is no such assignment <b>for this caller</b>: 404 Not Found. Thrown when the id does not
 * exist, when its course is not visible to the caller, and when a student asks for an unpublished
 * assignment, always with the same status, code and message, so a student cannot tell a draft from
 * a missing id. Status, code and message come from {@link ErrorCode#ASSIGNMENT_NOT_FOUND}.
 */
public class AssignmentNotFoundException extends ApiException {

    public AssignmentNotFoundException() {
        super(ErrorCode.ASSIGNMENT_NOT_FOUND);
    }
}
