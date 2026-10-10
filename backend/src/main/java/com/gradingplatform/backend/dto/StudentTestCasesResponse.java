package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;
import java.util.ArrayList;
import java.util.List;

/**
 * An assignment's tests as a student sees them, in the order given (run order): public tests in
 * full, hidden tests masked (see {@link StudentTestCaseResponse}).
 */
public record StudentTestCasesResponse(List<StudentTestCaseResponse> tests) implements TestListResponse {

    /** The only place a {@link TestCase} becomes something a student may see. */
    public static StudentTestCasesResponse of(List<TestCase> tests) {
        List<StudentTestCaseResponse> masked = new ArrayList<>();
        int hidden = 0;
        for (TestCase t : tests) {
            if (t.getVisibility() == TestVisibility.HIDDEN) {
                masked.add(StudentTestCaseResponse.HiddenTest.of(t, ++hidden));
            } else {
                masked.add(StudentTestCaseResponse.PublicTest.of(t));
            }
        }
        return new StudentTestCasesResponse(List.copyOf(masked));
    }
}
