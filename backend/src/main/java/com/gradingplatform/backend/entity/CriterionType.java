package com.gradingplatform.backend.entity;

/**
 * What a rubric criterion measures (V5 stores the name as plain text). A type is only usable once
 * the phase that scores it has landed: until then it exists here, so its name is reserved, but
 * {@link #isAvailable()} is false and the API refuses it with a clear "not available yet" error
 * rather than accepting a criterion nothing can score. The phase that adds the scoring flips the
 * flag: `MANUAL` in 5.4d, `STATIC_ANALYSIS` in 13.4a, `OPEN_ANSWER` in 15.2a.
 */
public enum CriterionType {
    TESTS(true),
    STATIC_ANALYSIS(false),
    OPEN_ANSWER(false),
    MANUAL(false);

    private final boolean available;

    CriterionType(boolean available) {
        this.available = available;
    }

    public boolean isAvailable() {
        return available;
    }
}
