package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * The course still has assignments, and with them students' submissions, so it cannot be deleted:
 * 409 Conflict. Status, code and message come from {@link ErrorCode#COURSE_HAS_ASSIGNMENTS}.
 */
public class CourseHasAssignmentsException extends ApiException {

    public CourseHasAssignmentsException() {
        super(ErrorCode.COURSE_HAS_ASSIGNMENTS);
    }
}
