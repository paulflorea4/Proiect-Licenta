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
 *
 * <p><b>Except while stopping (7.7c):</b> a normal shutdown interrupts the workers, and the interrupt
 * reaches grading as a failure (the sandbox's {@code SANDBOX_ERROR}, then {@code SandboxFailedException}).
 * That is not the submission's doing, so a failure while the worker is stopping ({@link #stop} was
 * called, or the thread is interrupted) finishes nothing: the row stays {@code RUNNING} and the next
 * start re-queues it (5.2c). The flag is there because an interrupt flag can be cleared on the way (any
 * code that catches {@link InterruptedException}); the interrupt check covers an interrupt from
 * elsewhere. A grading that completes while stopping is still {@code GRADED}.
 */
@Component
public class SubmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(SubmissionWorker.class);

    private final SubmissionRepository submissions;
    private final ObjectProvider<SubmissionProcessor> processor;
    private final Clock clock;

    /** Set once, by {@link SubmissionDispatcher#destroy} before it interrupts the workers. */
    private volatile boolean stopping;

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
            // Only when no processor bean exists at all (the application has SubmissionGrader, 5.3a):
            // the claimed submission stays RUNNING.
            return;
        }
        try {
            grading.process(submissionId);
        } catch (Exception | Error e) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                log.warn(
                        "Submission {} was stopped by the shutdown while being graded; it stays RUNNING and is"
                                + " re-queued at the next start ({})",
                        submissionId,
                        e.toString());
                if (e instanceof Error error) {
                    throw error;
                }
                return;
            }
            log.error("Submission {} could not be graded; it ends in ERROR", submissionId, e);
            submissions.finish(submissionId, SubmissionStatus.ERROR, now());
            if (e instanceof Error error) {
                throw error;
            }
            return;
        }
        submissions.finish(submissionId, SubmissionStatus.GRADED, now());
    }

    /**
     * From now on a failed grading leaves its submission {@code RUNNING} instead of {@code ERROR}: the
     * application is shutting down. Never undone.
     */
    public void stop() {
        stopping = true;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
