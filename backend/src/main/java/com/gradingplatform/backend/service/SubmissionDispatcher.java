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
 * refuses one. Nothing runs at startup: the sandbox sweep must come first, and re-queueing what a
 * restart left behind is 5.2c.
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
    private final int window;

    /** Offered to the pool and not finished yet: never offered twice at the same time. */
    private final Set<Long> handedOver = ConcurrentHashMap.newKeySet();

    @Autowired
    public SubmissionDispatcher(
            SubmissionWorkerProperties properties, SubmissionWorker worker, SubmissionRepository submissions) {
        this(properties, worker::work, submissions::findQueuedIds);
    }

    /**
     * @param work one submission's turn on a worker thread
     * @param oldestQueued the ids of up to that many {@code QUEUED} submissions, oldest first
     */
    SubmissionDispatcher(
            SubmissionWorkerProperties properties, LongConsumer work, IntFunction<List<Long>> oldestQueued) {
        this.work = work;
        this.oldestQueued = oldestQueued;
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
     * @return false only when the pool and its queue are full (the submission stays {@code QUEUED})
     */
    boolean offer(long submissionId) {
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
            // This task just left the in-memory queue: its place goes to the oldest one waiting.
            pickUpQueued();
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
        // Waiting submissions stay QUEUED and a running one RUNNING; 5.2c takes them up again.
        pool.shutdown();
    }
}
