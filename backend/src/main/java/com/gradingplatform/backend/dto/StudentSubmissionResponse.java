package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.service.SubmissionViewService.SubmissionDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A student's view of their own submission (5.5a): the code, the status, the grade, the rubric
 * breakdown and the test results. The tests come from {@link StudentTestCasesResponse#of}, the one
 * place a test case becomes student-visible, so hidden tests are masked by type ({@link
 * StudentTestResultResponse}). The rubric breakdown (criterion names, scores and a teacher's
 * comments) is shown to students since 5.5a, the human's decision; which test belongs to which
 * criterion is not.
 *
 * <p>Until the submission is {@code GRADED}, {@code criteria} and {@code tests} are empty and
 * {@code grade} is null. {@code grade} also stays null for a graded submission that has none yet
 * (an assignment without tests, until a teacher's first score, 5.4e). {@code compilerOutput} is the
 * compiler's text when the build failed (the same on every test, 5.3b), else null: it comes from the
 * student's own code, before any test input exists.
 *
 * <p>{@code hintsUsed} is how many hint levels the student has received for this submission
 * (7.4a), counted from its stored {@code ai_feedback} rows (a fallback is not stored, so it does not
 * count); 0 to 3, at any status. It never affects the score.
 */
public record StudentSubmissionResponse(
        long id,
        long assignmentId,
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
        List<StudentTestResultResponse> tests,
        int hintsUsed)
        implements SubmissionResponse {

    /** One rubric criterion's score; {@code score} is null while it is pending (no row, V9). */
    public record CriterionView(
            String name, CriterionType type, BigDecimal score, BigDecimal maxScore, String comment) {}

    public static StudentSubmissionResponse of(SubmissionDetails details) {
        Submission s = details.submission();
        boolean graded = s.getStatus() == SubmissionStatus.GRADED;
        return new StudentSubmissionResponse(
                s.getId(),
                s.getAssignmentId(),
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
                graded ? tests(details) : List.of(),
                details.hintsUsed());
    }

    private static List<CriterionView> criteria(List<RubricCriterion> rubric, List<CriterionScore> scores) {
        Map<Long, CriterionScore> byCriterion =
                scores.stream().collect(Collectors.toMap(CriterionScore::getCriterionId, Function.identity()));
        return rubric.stream()
                .map(c -> {
                    CriterionScore score = byCriterion.get(c.getId());
                    return new CriterionView(
                            c.getName(),
                            c.getType(),
                            score == null ? null : score.getScore(),
                            BigDecimal.valueOf(c.getWeight()).setScale(2),
                            score == null ? null : score.getComment());
                })
                .toList();
    }

    private static List<StudentTestResultResponse> tests(SubmissionDetails details) {
        if (details.results().isEmpty()) {
            return List.of();
        }
        Map<Long, TestResult> byTest =
                details.results().stream().collect(Collectors.toMap(TestResult::getTestCaseId, Function.identity()));
        List<StudentTestResultResponse> out = new ArrayList<>();
        for (StudentTestCaseResponse test :
                StudentTestCasesResponse.of(details.tests()).tests()) {
            TestResult result = byTest.get(test.id());
            if (test instanceof StudentTestCaseResponse.PublicTest p) {
                out.add(StudentTestResultResponse.PublicResult.of(p, result));
            } else {
                out.add(StudentTestResultResponse.HiddenResult.of((StudentTestCaseResponse.HiddenTest) test, result));
            }
        }
        return List.copyOf(out);
    }
}
