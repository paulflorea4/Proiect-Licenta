package com.gradingplatform.backend.entity;

/**
 * Where a submission is in grading (`submissions.status`, stored by name). A new submission is {@code
 * QUEUED}; the worker (5.2a) moves it to {@code RUNNING} and then to {@code GRADED} or {@code ERROR}
 * (5.2b).
 */
public enum SubmissionStatus {
    QUEUED,
    RUNNING,
    GRADED,
    ERROR
}
