package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestCase;
import java.util.List;

/** An assignment's tests for its teacher, in run order (not paginated: an assignment has a handful). */
public record TestCasesResponse(List<TestCaseResponse> tests) {

    public static TestCasesResponse of(List<TestCase> tests) {
        return new TestCasesResponse(tests.stream().map(TestCaseResponse::from).toList());
    }
}
