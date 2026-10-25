package com.gradingplatform.backend.ai;

/**
 * The AI service's answer to `POST /feedback` (6.3a, 6.4b).
 *
 * @param hint the hint text, never blank
 * @param fallback true when the hint is the service's generic safe hint because both of its answers
 *     leaked a solution; 7.2a must not count it as a used level
 */
public record FeedbackResponse(String hint, Boolean fallback) {}
