package com.gradingplatform.backend.sandbox;

/**
 * The sandbox itself failed while a submission's tests were being run (Docker unavailable, an image
 * missing...): this says nothing about the student's code, so it is never recorded as a test result or
 * a grade (4.6a). Whoever grades catches it and puts the submission in {@code ERROR} to be retried
 * (5.2b). The message is fixed text; docker's own text is in the log.
 */
public class SandboxFailedException extends RuntimeException {

    public SandboxFailedException() {
        super("the sandbox failed while running the tests");
    }
}
