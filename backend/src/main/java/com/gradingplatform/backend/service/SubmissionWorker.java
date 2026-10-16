package com.gradingplatform.backend.service;

import com.gradingplatform.backend.repository.SubmissionRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One submission's turn on a worker thread (5.2a): claim it, then hand it to the
 * {@link SubmissionProcessor}. The claim is {@link SubmissionRepository#claim}, one conditional
 * update from {@code QUEUED} to {@code RUNNING} with {@code started_at}, so a submission is taken by
 * at most one worker however often it is offered, and a submission that is no longer {@code QUEUED}
 * (or no longer exists) is left alone.
 */
@Component
public class SubmissionWorker {

    private final SubmissionRepository submissions;
    private final ObjectProvider<SubmissionProcessor> processor;
    private final Clock clock;

    public SubmissionWorker(
            SubmissionRepository submissions, ObjectProvider<SubmissionProcessor> processor, Clock clock) {
        this.submissions = submissions;
        this.processor = processor;
        this.clock = clock;
    }

    /** Claims the submission and processes it; does nothing if someone else has it or it is gone. */
    public void work(long submissionId) {
        if (submissions.claim(submissionId, clock.instant().truncatedTo(ChronoUnit.MICROS)) == 0) {
            return;
        }
        processor.ifAvailable(p -> p.process(submissionId));
    }
}
