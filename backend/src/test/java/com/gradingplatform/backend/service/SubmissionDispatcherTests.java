package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 5.2a: the pool on its own, against an in-memory stand-in for the {@code QUEUED} rows (a claim
 * removes the row, as {@code SubmissionRepository.claim} turns it {@code RUNNING}). The same flow
 * against the real database is in {@code RealHttpSubmissionWorkerTests}.
 */
class SubmissionDispatcherTests {

    /** The submissions still {@code QUEUED}. */
    private final NavigableSet<Long> queuedRows = new ConcurrentSkipListSet<>();

    /** Each claimed submission waits here for a permit. */
    private final Semaphore permits = new Semaphore(0);

    private final List<Long> worked = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger mostAtOnce = new AtomicInteger();

    private Long failing;
    private SubmissionDispatcher dispatcher;

    @AfterEach
    void stop() {
        permits.release(1000);
        dispatcher.destroy();
    }

    private SubmissionDispatcher dispatcher(int poolSize, int queueCapacity) {
        dispatcher = new SubmissionDispatcher(
                new SubmissionWorkerProperties(poolSize, queueCapacity),
                this::work,
                limit -> queuedRows.stream().limit(limit).toList());
        return dispatcher;
    }

    /** What {@code SubmissionWorker.work} does: claim (only a queued row), then process. */
    private void work(long id) {
        calls.add(id);
        if (!queuedRows.remove(id)) {
            return;
        }
        if (Long.valueOf(id).equals(failing)) {
            throw new IllegalStateException("grading crashed");
        }
        mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.decrementAndGet();
        }
        worked.add(id);
    }

    /** Stores the rows and offers them as their commits would, in order. */
    private List<Boolean> submit(long... ids) {
        List<Boolean> accepted = new ArrayList<>();
        for (long id : ids) {
            queuedRows.add(id);
            accepted.add(dispatcher.offer(id));
        }
        return accepted;
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void aBurstBeyondThePoolAndItsQueueWaitsInTheTableAndIsAllGradedOnce() throws Exception {
        dispatcher(2, 3);

        List<Boolean> accepted = submit(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        eventually("two running", () -> running.get() == 2);

        assertThat(accepted.subList(0, 5)).containsOnly(true);
        assertThat(accepted.subList(5, 12))
                .as("the pool is full; they stay QUEUED")
                .containsOnly(false);
        assertThat(queuedRows).as("the rest is still waiting").hasSize(10);

        permits.release(12);
        eventually("all twelve graded", () -> worked.size() == 12);
        assertThat(worked).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        assertThat(mostAtOnce.get()).as("never more than the pool at once").isEqualTo(2);
        assertThat(queuedRows).isEmpty();
    }

    @Test
    void theWaitingAreTakenOldestFirst() throws Exception {
        dispatcher(1, 1);
        submit(1, 2, 3, 4, 5, 6);
        eventually("one running", () -> running.get() == 1);

        for (int done = 1; done <= 6; done++) {
            permits.release();
            int expected = done;
            eventually("graded " + done, () -> worked.size() == expected);
        }
        assertThat(worked).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void anOlderSubmissionNobodyOfferedIsPickedUpWhenTheNextOneStarts() throws Exception {
        dispatcher(1, 1);
        queuedRows.add(1L); // QUEUED in the table, but its offer never happened (a restart, a full pool)

        submit(2);
        permits.release(2);

        eventually("both graded", () -> worked.size() == 2);
        assertThat(worked).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aSubmissionOfferedTwiceIsWorkedOnce() throws Exception {
        dispatcher(2, 5);
        queuedRows.add(1L);

        assertThat(dispatcher.offer(1)).isTrue();
        assertThat(dispatcher.offer(1)).as("already handed over").isTrue();
        eventually("running", () -> running.get() == 1);
        permits.release(5);
        eventually("graded", () -> worked.size() == 1);

        Thread.sleep(100);
        assertThat(worked).containsExactly(1L);
        assertThat(calls).containsExactly(1L);
    }

    @Test
    void aCrashOnOneSubmissionDoesNotStopTheWorkers() throws Exception {
        dispatcher(1, 1);
        failing = 1L;

        submit(1, 2, 3);
        permits.release(2);

        eventually("the others graded", () -> worked.size() == 2);
        assertThat(worked).containsExactly(2L, 3L);
    }

    /**
     * A refused offer must never hide a submission from a pickup running at the same moment. Each
     * round, with the worker idle, offers two waiting rows back to back as startup recovery does: the
     * first fills the queue, the second is usually refused, and the worker taking the first offers the
     * second again. If the refused offer still counted as handed over while the worker looked, both
     * skipped it and it was never graded (seen on CI, PR #108).
     */
    @Test
    void aRefusedOfferNeverHidesASubmissionFromAPickupAtTheSameMoment() throws Exception {
        dispatcher(1, 1);
        permits.release(Integer.MAX_VALUE / 2); // nothing waits: this is about the hand-over only

        for (long round = 0; round < 3000; round++) {
            long first = 10 + 2 * round;
            long second = first + 1;
            queuedRows.add(first);
            queuedRows.add(second);

            dispatcher.pickUpQueued();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!worked.contains(second) || !worked.contains(first)) {
                assertThat(System.nanoTime())
                        .as("round %d: %d and %d graded (still queued: %s)", round, first, second, queuedRows)
                        .isLessThan(deadline);
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void destroyTellsTheWorkItIsStoppingBeforeInterruptingIt() throws Exception {
        // 7.7c: the worker must already know when the interrupt reaches the grading, or it writes ERROR.
        AtomicBoolean stopped = new AtomicBoolean();
        List<Boolean> stoppedWhenInterrupted = Collections.synchronizedList(new ArrayList<>());
        dispatcher = new SubmissionDispatcher(
                new SubmissionWorkerProperties(1, 1),
                id -> {
                    queuedRows.remove(id);
                    running.incrementAndGet();
                    try {
                        new Semaphore(0).acquire(); // a grading that only an interrupt ends
                    } catch (InterruptedException e) {
                        stoppedWhenInterrupted.add(stopped.get());
                    } finally {
                        running.decrementAndGet();
                    }
                },
                limit -> queuedRows.stream().limit(limit).toList(),
                () -> stopped.set(true));
        submit(1, 2);
        eventually("the first is being graded", () -> running.get() == 1);

        dispatcher.destroy();

        eventually("the worker was interrupted", () -> !stoppedWhenInterrupted.isEmpty());
        assertThat(stoppedWhenInterrupted).containsExactly(true);
        assertThat(queuedRows).as("the one waiting in memory is still QUEUED").containsExactly(2L);
    }

    @Test
    void thePoolSizeAndQueueCapacityMustBeSetAndPositive() {
        assertThatThrownBy(() -> new SubmissionWorkerProperties(null, 1)).hasMessageContaining("pool-size");
        assertThatThrownBy(() -> new SubmissionWorkerProperties(0, 1)).hasMessageContaining("pool-size");
        assertThatThrownBy(() -> new SubmissionWorkerProperties(1, null)).hasMessageContaining("queue-capacity");
        assertThatThrownBy(() -> new SubmissionWorkerProperties(1, 0)).hasMessageContaining("queue-capacity");
        dispatcher(1, 1);
    }
}
