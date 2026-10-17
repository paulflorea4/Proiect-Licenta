package com.gradingplatform.backend.service;

/**
 * What the worker does with a submission it has claimed ({@code RUNNING}, 5.2a). The application's
 * implementation is {@link SubmissionGrader} (5.3a); a test may replace it with a {@code @Primary}
 * one.
 */
public interface SubmissionProcessor {

    /**
     * Called on a worker thread, outside any transaction, at most once per claim. Returning means the
     * submission was graded ({@code GRADED}); throwing means it could not be ({@code ERROR}, 5.2b).
     * The status is the worker's to write, never the processor's.
     */
    void process(long submissionId);
}
