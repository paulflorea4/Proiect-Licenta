package com.gradingplatform.backend.entity;

/**
 * Whether students may see a test case: {@code PUBLIC} ones in full, {@code HIDDEN} ones as
 * pass/fail only. A hidden test's name, input and expected output never reach a student or the
 * LLM (3.5b masks them in the student-facing DTO).
 */
public enum TestVisibility {
    PUBLIC,
    HIDDEN
}
