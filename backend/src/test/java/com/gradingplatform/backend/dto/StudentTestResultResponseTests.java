package com.gradingplatform.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.5a: the student shape of a hidden test's result cannot carry anything but pass or fail, and
 * the submission view has no field for the teacher's view of a test.
 */
class StudentTestResultResponseTests {

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    @Test
    void aHiddenResultIsPassOrFailOnly() {
        // No real name, input, expected or actual output, stderr, outcome or runtime.
        assertThat(componentNames(StudentTestResultResponse.HiddenResult.class))
                .containsExactly("id", "name", "visibility", "position", "passed");
    }

    @Test
    void aPublicResultIsThePublicTestAndItsWholeResult() {
        assertThat(componentNames(StudentTestResultResponse.PublicResult.class))
                .containsExactly(
                        "id",
                        "name",
                        "input",
                        "expectedOutput",
                        "visibility",
                        "weight",
                        "position",
                        "status",
                        "actualOutput",
                        "stderr",
                        "runtimeMs");
    }

    @Test
    void theSubmissionViewHoldsTestsOnlyAsTheStudentShape() {
        assertThat(componentNames(StudentSubmissionResponse.class))
                .containsExactly(
                        "id",
                        "assignmentId",
                        "attemptNo",
                        "status",
                        "language",
                        "sourceCode",
                        "submittedAt",
                        "startedAt",
                        "finishedAt",
                        "compilerOutput",
                        "grade",
                        "criteria",
                        "tests");
        assertThat(componentNames(StudentSubmissionResponse.CriterionView.class))
                .as("no criterion id: which test belongs to which criterion stays hidden")
                .containsExactly("name", "type", "score", "maxScore", "comment");
    }
}
