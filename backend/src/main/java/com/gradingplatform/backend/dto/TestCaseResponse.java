package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;

/**
 * A test case as its teacher sees it: everything, hidden tests included. <b>Never build a
 * student-facing or LLM-facing shape from this record</b>; those are separate types that leave a
 * hidden test's name, input and expected output out (3.5b).
 */
public record TestCaseResponse(
        Long id,
        Long assignmentId,
        Long criterionId,
        String name,
        String input,
        String expectedOutput,
        TestVisibility visibility,
        int weight,
        int position) {

    public static TestCaseResponse from(TestCase t) {
        return new TestCaseResponse(
                t.getId(),
                t.getAssignmentId(),
                t.getCriterionId(),
                t.getName(),
                t.getInput(),
                t.getExpectedOutput(),
                t.getVisibility(),
                t.getWeight(),
                t.getPosition());
    }
}
