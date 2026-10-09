package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ApiException;
import com.gradingplatform.backend.dto.ErrorCode;

/**
 * There is no such course <b>for this caller</b>: 404 Not Found. It is thrown both when the id
 * does not exist and when the course exists but the caller may not see it, with the same status,
 * code and message, so ids cannot be probed for which courses exist. Status, code and message come
 * from {@link ErrorCode#COURSE_NOT_FOUND}.
 */
public class CourseNotFoundException extends ApiException {

    public CourseNotFoundException() {
        super(ErrorCode.COURSE_NOT_FOUND);
    }
}
