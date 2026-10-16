package com.gradingplatform.backend.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The submission worker pool (5.2a), {@code submissions.worker} in {@code application.yml}. Both
 * values are required there (no default in code) and are placeholders pending the human's tuning.
 *
 * @param poolSize how many submissions are graded at the same time, at least 1. Each runs its tests
 *     one after the other in containers of one CPU each, so this is roughly the CPUs grading uses.
 * @param queueCapacity how many more submissions wait in memory for a free worker, at least 1.
 *     Beyond it a submission is not refused: it stays {@code QUEUED} in the database and is picked up
 *     when a slot frees ({@link SubmissionDispatcher}). At least 1, because a slot is refilled when
 *     a worker takes a task from this queue.
 */
@ConfigurationProperties("submissions.worker")
public record SubmissionWorkerProperties(Integer poolSize, Integer queueCapacity) {

    public SubmissionWorkerProperties {
        if (poolSize == null || poolSize < 1) {
            throw new IllegalArgumentException("submissions.worker.pool-size must be set to at least 1");
        }
        if (queueCapacity == null || queueCapacity < 1) {
            throw new IllegalArgumentException("submissions.worker.queue-capacity must be set to at least 1");
        }
    }
}
