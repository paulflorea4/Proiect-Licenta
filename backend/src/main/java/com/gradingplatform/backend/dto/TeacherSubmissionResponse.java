package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.entity.TestVisibility;
import com.gradingplatform.backend.sandbox.TestOutcome;
import com.gradingplatform.backend.service.SubmissionViewService.SubmissionDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A submission as the people who run its course see it (5.5b): everything stored, hidden tests in
 * full with their results, and the ids a teacher needs to score a {@code MANUAL} criterion (5.4d).
 * <b>Never send this to a student or build a student-facing shape from it</b>; that is {@link
 * StudentSubmissionResponse}.
 *
 * <p>As in the student's view, {@code criteria} and {@code tests} are empty and {@code grade} and
 * {@code compilerOutput} null until the submission is {@code GRADED}: a grade or results stored
 * for a submission that is not (yet) graded are not its result. {@code hintsUsed} is the same
 * count as the student sees (7.4a), at any status, and {@code hints} are those hints, level 1 first,
 * with the student's report on each (7.4b), the shape of {@code GET /submissions/{id}/hints}.
 */
public record TeacherSubmissionResponse(
        long id,
        long assignmentId,
        long studentId,
        int attemptNo,
        SubmissionStatus status,
        String language,
        String sourceCode,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        String compilerOutput,
        GradeView grade,
        List<CriterionView> criteria,
        List<TestResultView> tests,
        int hintsUsed,
        List<HintsResponse.TeacherHint> hints)
        implements SubmissionResponse {

    /** One rubric criterion and its score; {@code score} is null while it is pending (no row, V9). */
    public record CriterionView(
            long id,
            String name,
            CriterionType type,
            int weight,
            BigDecimal score,
            BigDecimal maxScore,
            String comment) {}

    /** One test case in full and its result; the result fields are null if it has none (not run). */
    public record TestResultView(
            long id,
            long criterionId,
            String name,
            String input,
            String expectedOutput,
            TestVisibility visibility,
            int weight,
            int position,
            TestOutcome status,
            String actualOutput,
            String stderr,
            Integer runtimeMs) {}

    public static TeacherSubmissionResponse of(SubmissionDetails details) {
        Submission s = details.submission();
        boolean graded = s.getStatus() == SubmissionStatus.GRADED;
        return new TeacherSubmissionResponse(
                s.getId(),
                s.getAssignmentId(),
                s.getStudentId(),
                s.getAttemptNo(),
                s.getStatus(),
                s.getLanguage(),
                s.getSourceCode(),
                s.getSubmittedAt(),
                s.getStartedAt(),
                s.getFinishedAt(),
                graded ? SubmissionResponse.compilerOutput(details.results()) : null,
                graded ? details.grade().map(GradeView::of).orElse(null) : null,
                graded ? criteria(details.rubric(), details.scores()) : List.of(),
                graded && !details.results().isEmpty() ? tests(details.tests(), details.results()) : List.of(),
                details.hintsUsed(),
                details.hints().stream().map(HintsResponse.TeacherHint::of).toList());
    }

    private static List<CriterionView> criteria(List<RubricCriterion> rubric, List<CriterionScore> scores) {
        Map<Long, CriterionScore> byCriterion =
                scores.stream().collect(Collectors.toMap(CriterionScore::getCriterionId, Function.identity()));
        return rubric.stream()
                .map(c -> {
                    CriterionScore score = byCriterion.get(c.getId());
                    return new CriterionView(
                            c.getId(),
                            c.getName(),
                            c.getType(),
                            c.getWeight(),
                            score == null ? null : score.getScore(),
                            BigDecimal.valueOf(c.getWeight()).setScale(2),
                            score == null ? null : score.getComment());
                })
                .toList();
    }

    private static List<TestResultView> tests(List<TestCase> tests, List<TestResult> results) {
        Map<Long, TestResult> byTest =
                results.stream().collect(Collectors.toMap(TestResult::getTestCaseId, Function.identity()));
        return tests.stream()
                .map(t -> {
                    TestResult r = byTest.get(t.getId());
                    return new TestResultView(
                            t.getId(),
                            t.getCriterionId(),
                            t.getName(),
                            t.getInput(),
                            t.getExpectedOutput(),
                            t.getVisibility(),
                            t.getWeight(),
                            t.getPosition(),
                            r == null ? null : r.getStatus(),
                            r == null ? null : r.getActualOutput(),
                            r == null ? null : r.getStderr(),
                            r == null ? null : r.getRuntimeMs());
                })
                .toList();
    }
}
