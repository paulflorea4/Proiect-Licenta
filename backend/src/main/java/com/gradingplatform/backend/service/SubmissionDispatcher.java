package com.gradingplatform.backend.service;

import com.gradingplatform.backend.repository.SubmissionRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The bounded worker pool (5.2a): {@link SubmissionWorkerProperties#poolSize} threads and an
 * in-memory queue of {@link SubmissionWorkerProperties#queueCapacity}, so a burst at a deadline
 * waits instead of starting more runs than the host can take.
 *
 * <p><b>The database is the queue of record</b> (the human's decision): a submission is offered to
 * the pool when its insert commits ({@link SubmissionQueuedEvent}); if the pool and its queue are
 * full it is not refused and not lost, it just stays {@code QUEUED} in the table. Each time a worker
 * takes a task from the in-memory queue, which frees one place in it, it first offers the oldest
 * {@code QUEUED} submissions (by id, i.e. arrival) that are not handed over yet, until the pool
 * refuses one. The dispatcher starts nothing at startup by itself: {@link SubmissionRecovery} (5.2c)
 * offers what a restart left behind, once the sandbox sweep is done.
 *
 * <p>The pool is owned here rather than a bean, so it never becomes the application's default
 * executor. One backend instance is assumed (as for the sandbox sweep, 4.3e); several would need a
 * claim that skips locked rows, not this in-process set.
 */
@Component
public class SubmissionDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SubmissionDispatcher.class);

    private final ThreadPoolTaskExecutor pool;
    private final LongConsumer work;
    private final IntFunction<List<Long>> oldestQueued;
    private final Runnable stopWork;
    private final int window;

    /** Offered to the pool and not finished yet: never offered twice at the same time. */
    private final Set<Long> handedOver = ConcurrentHashMap.newKeySet();

    @Autowired
    public SubmissionDispatcher(
            SubmissionWorkerProperties properties, SubmissionWorker worker, SubmissionRepository submissions) {
        this(properties, worker::work, submissions::findQueuedIds, worker::stop);
    }

    SubmissionDispatcher(
            SubmissionWorkerProperties properties, LongConsumer work, IntFunction<List<Long>> oldestQueued) {
        this(properties, work, oldestQueued, () -> {});
    }

    /**
     * @param work one submission's turn on a worker thread
     * @param oldestQueued the ids of up to that many {@code QUEUED} submissions, oldest first
     * @param stopWork tells {@code work} the application is stopping, before the workers are interrupted
     */
    SubmissionDispatcher(
            SubmissionWorkerProperties properties,
            LongConsumer work,
            IntFunction<List<Long>> oldestQueued,
            Runnable stopWork) {
        this.work = work;
        this.oldestQueued = oldestQueued;
        this.stopWork = stopWork;
        // Everything handed over is within this many of the oldest QUEUED rows, so the window
        // always reaches past them to the next ones waiting.
        this.window = properties.poolSize() + properties.queueCapacity();
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(properties.poolSize());
        pool.setMaxPoolSize(properties.poolSize());
        pool.setQueueCapacity(properties.queueCapacity());
        pool.setThreadNamePrefix("submission-worker-");
        pool.initialize();
    }

    @TransactionalEventListener
    void onQueued(SubmissionQueuedEvent event) {
        offer(event.submissionId());
    }

    /**
     * Hands the submission to the pool unless it is there already.
     *
     * <p>Synchronized: between marking a submission handed over and the pool refusing it, a worker
     * that just took a task may be offering the same submission. Without the lock it would see it
     * marked, skip it, and nobody would hold it once the mark is undone: it would wait {@code QUEUED}
     * until the next submission (seen on CI, PR #108). With it, "handed over" only ever means "in the
     * pool".
     *
     * @return false only when the pool and its queue are full (the submission stays {@code QUEUED})
     */
    synchronized boolean offer(long submissionId) {
        if (!handedOver.add(submissionId)) {
            return true;
        }
        try {
            pool.execute(() -> run(submissionId));
            return true;
        } catch (TaskRejectedException full) {
            handedOver.remove(submissionId);
            return false;
        }
    }

    /** Offers the oldest waiting submissions until the pool is full or none is left. */
    void pickUpQueued() {
        for (long id : oldestQueued.apply(window)) {
            if (!offer(id)) {
                return;
            }
        }
    }

    private void run(long submissionId) {
        try {
            // This task just left the in-memory queue: its place goes to the oldest one waiting. A
            // refill that fails (the database, say) must not cost this task its own submission (7.7d):
            // the ones it could not offer stay QUEUED for the next task's refill.
            try {
                pickUpQueued();
            } catch (RuntimeException e) {
                log.error("The waiting submissions could not be offered before submission {}", submissionId, e);
            }
            work.accept(submissionId);
        } catch (RuntimeException e) {
            // Nothing may stop the thread. A failed grading is already ERROR (SubmissionWorker); what
            // gets here is the database failing to claim or finish, and that row is left for 5.2c.
            log.error("Submission {} failed on its worker", submissionId, e);
        } finally {
            handedOver.remove(submissionId);
        }
    }

    @Override
    public void destroy() {
        // Waiting submissions stay QUEUED and a running one RUNNING; SubmissionRecovery takes them
        // up again at the next start. The pool's shutdown interrupts the running workers, which their
        // grading sees as a failure: the worker is told first, so it does not write ERROR (7.7c).
        stopWork.run();
        pool.shutdown();
    }
}
