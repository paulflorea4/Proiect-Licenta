package com.gradingplatform.backend.service;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Tells which database constraint an exception is about. A unique or foreign-key violation is the
 * one race-free arbiter (a check before the insert cannot see a concurrent request), so services
 * catch it and translate only the constraint they know; any other violation is rethrown.
 */
final class Constraints {

    private Constraints() {}

    /** True if {@code error} or one of its causes is a violation of the constraint with this name. */
    static boolean isViolationOf(Throwable error, String constraintName) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
