package com.gradingplatform.backend.sandbox;

import java.util.Objects;

/**
 * A test case as the suite runner needs it (4.6a), copied out of the entity so the sandbox package does
 * not depend on the database model. {@code input} goes to the program's stdin; {@code expectedOutput}
 * never leaves the backend: it is only compared with, outside the sandbox (4.7d). Nothing here says
 * whether the test is hidden: public and hidden tests are run and judged identically.
 *
 * @param id the test case's id, returned with its result so the caller can match them up
 * @param input the text for stdin; may be empty
 * @param expectedOutput the text stdout is compared with; may be empty
 */
public record TestCaseInput(long id, String input, String expectedOutput) {

    public TestCaseInput {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedOutput, "expectedOutput");
    }

    /** Hidden tests must not leak through a log line. */
    @Override
    public String toString() {
        return "TestCaseInput[id=" + id + ", input=<" + input.length() + " chars>, expectedOutput=<"
                + expectedOutput.length() + " chars>]";
    }
}
