package com.gradingplatform.backend.service;

/**
 * A submission was stored as {@code QUEUED}. Published inside the accepting transaction and handled
 * by {@link SubmissionDispatcher} only once that transaction has committed, so the worker never
 * looks for a row it cannot see yet.
 */
public record SubmissionQueuedEvent(long submissionId) {}
