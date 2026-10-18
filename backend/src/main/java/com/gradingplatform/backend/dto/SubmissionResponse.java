package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.sandbox.TestOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The answer to `GET /submissions/{id}`: the student's own view ({@link StudentSubmissionResponse},
 * hidden tests masked, 5.5a) or the view of the people who run the course ({@link
 * TeacherSubmissionResponse}, everything, 5.5b). Which one a caller gets follows from the token's
 * role, never from a parameter.
 */
public sealed interface SubmissionResponse permits StudentSubmissionResponse, TeacherSubmissionResponse {

    /** A submission's total; provisional while a criterion is pending (5.4e). The same for every reader. */
    record GradeView(BigDecimal totalScore, BigDecimal maxScore, boolean provisional, Instant gradedAt) {

        static GradeView of(Grade g) {
            return new GradeView(g.getTotalScore(), g.getMaxScore(), g.isProvisional(), g.getGradedAt());
        }
    }

    /**
     * The compiler's text when the build failed (the same on every test's row, 5.3b), else null. It
     * comes from the student's own code, before any test input exists, so every reader may see it.
     */
    static String compilerOutput(List<TestResult> results) {
        return results.stream()
                .filter(r -> r.getStatus() == TestOutcome.COMPILE_ERROR)
                .map(TestResult::getStderr)
                .findFirst()
                .orElse(null);
    }
}
