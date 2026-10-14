package com.gradingplatform.backend.sandbox;

/**
 * Decides whether what a program printed is what a test case expects (4.5b). It is the one place that
 * rule lives, and it is an interface so a stricter or looser rule (teachers will eventually want a strict
 * mode) can replace the default without touching the code that grades. It is pure: the same two strings
 * always give the same answer, and it never sees which test it is for, so a hidden test is compared
 * exactly like a public one. The comparison happens in the backend, outside the sandbox (4.7d).
 *
 * <p>The default is {@link TrailingWhitespaceComparison}.
 */
@FunctionalInterface
public interface OutputComparison {

    /**
     * @param expected the test case's expected stdout
     * @param actual what the program wrote to stdout
     */
    boolean matches(String expected, String actual);

    /**
     * Whether a run passes a test case: the run must have completed cleanly ({@link
     * SandboxResult#completedCleanly()}: status {@code OK} and stdout not cut at the size cap) <b>and</b>
     * its stdout must match. A cut output is never compared, because a prefix that happens to equal the
     * expected text would otherwise pass, and a crash after the right output is not a pass either.
     */
    default boolean passes(SandboxResult result, String expected) {
        return result.completedCleanly() && matches(expected, result.stdout());
    }
}
