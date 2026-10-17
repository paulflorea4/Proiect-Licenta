package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.repository.SubmissionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One submission's turn on a worker thread: claim it (5.2a), hand it to the {@link
 * SubmissionProcessor}, then finish it (5.2b). The claim is {@link SubmissionRepository#claim}, one
 * conditional update from {@code QUEUED} to {@code RUNNING} with {@code started_at}, so a submission
 * is taken by at most one worker however often it is offered, and a submission that is no longer
 * {@code QUEUED} (or no longer exists) is left alone.
 *
 * <p>The worker owns every status change. When the processor returns the submission is {@code
 * GRADED}; when it throws anything at all (a checked exception thrown sneakily, an {@link Error}
 * too) it is {@code ERROR}, both with {@code finished_at} ({@link SubmissionRepository#finish}).
 * Only the status and the timestamp change, so the student's code is never lost because grading
 * crashed. If the database itself fails here the row stays {@code RUNNING} until a restart takes it
 * up again (5.2c).
 */
@Component
public class SubmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(SubmissionWorker.class);

    private final SubmissionRepository submissions;
    private final ObjectProvider<SubmissionProcessor> processor;
    private final Clock clock;

    public SubmissionWorker(
            SubmissionRepository submissions, ObjectProvider<SubmissionProcessor> processor, Clock clock) {
        this.submissions = submissions;
        this.processor = processor;
        this.clock = clock;
    }

    /**
     * Claims the submission, processes it and records how that ended; does nothing if someone else
     * has it or it is gone. An {@link Error} is rethrown once the submission is marked.
     */
    public void work(long submissionId) {
        if (submissions.claim(submissionId, now()) == 0) {
            return;
        }
        SubmissionProcessor grading = processor.getIfAvailable();
        if (grading == null) {
            // No grading yet (5.3a adds it): the claimed submission stays RUNNING.
            return;
        }
        try {
            grading.process(submissionId);
        } catch (Exception | Error e) {
            log.error("Submission {} could not be graded; it ends in ERROR", submissionId, e);
            submissions.finish(submissionId, SubmissionStatus.ERROR, now());
            if (e instanceof Error error) {
                throw error;
            }
            return;
        }
        submissions.finish(submissionId, SubmissionStatus.GRADED, now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
