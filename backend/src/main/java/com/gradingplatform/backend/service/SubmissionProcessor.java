package com.gradingplatform.backend.service;

/**
 * What the worker does with a submission it has claimed ({@code RUNNING}, 5.2a). There is no
 * implementation yet: until grading exists (5.2b, 5.3a) a claimed submission stays {@code RUNNING}.
 */
public interface SubmissionProcessor {

    /** Called on a worker thread, outside any transaction, at most once per claim. */
    void process(long submissionId);
}
