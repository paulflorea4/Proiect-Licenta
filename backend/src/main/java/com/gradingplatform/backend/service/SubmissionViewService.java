package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.Grade;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestResult;
import com.gradingplatform.backend.repository.CriterionScoreRepository;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import com.gradingplatform.backend.repository.SubmissionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import com.gradingplatform.backend.repository.TestResultRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading one submission with its result (5.5a, 5.5b): the submission, its test results, the rubric
 * with the criterion scores, and the grade. Who may read it is decided here, in one method for every
 * role; what each reader sees is the response type's job, picked by the token's role (the student's
 * shape masks hidden tests, 3.5b; the teacher's has everything).
 */
@Service
public class SubmissionViewService {

    private final SubmissionRepository submissions;
    private final TestCaseRepository testCases;
    private final TestResultRepository testResults;
    private final RubricCriterionRepository rubricCriteria;
    private final CriterionScoreRepository criterionScores;
    private final GradeRepository grades;
    private final AssignmentService assignmentService;

    /** Everything stored about a submission's result, the teacher's view: never sent to a student as is. */
    public record SubmissionDetails(
            Submission submission,
            List<TestCase> tests,
            List<TestResult> results,
            List<RubricCriterion> rubric,
            List<CriterionScore> scores,
            Optional<Grade> grade) {}

    public SubmissionViewService(
            SubmissionRepository submissions,
            TestCaseRepository testCases,
            TestResultRepository testResults,
            RubricCriterionRepository rubricCriteria,
            CriterionScoreRepository criterionScores,
            GradeRepository grades,
            AssignmentService assignmentService) {
        this.submissions = submissions;
        this.testCases = testCases;
        this.testResults = testResults;
        this.rubricCriteria = rubricCriteria;
        this.criterionScores = criterionScores;
        this.grades = grades;
        this.assignmentService = assignmentService;
    }

    /**
     * A submission, if this user may read it; the caller shapes it for the role.
     *
     * <ul>
     *   <li>A student reads their own (5.5a). Someone else's is refused with a 403, the human's
     *       decision at 5.5a (task 5.6c: "other student gets 403"). Owning it is enough: a student
     *       who has left the course, or whose assignment was unpublished, still sees their own work
     *       (past work stays, 3.2b).
     *   <li>A teacher or admin reads any submission of a course they manage (5.5b: owner or admin).
     *       Another teacher gets the 404 of an id that does not exist, as for every other read and
     *       for the manual score of the same submission (5.4d): the 403 above is for students only.
     * </ul>
     *
     * @throws SubmissionNotFoundException if there is no such submission, or a teacher may not manage it
     * @throws AccessRefusedException if a student asks for another student's
     */
    @Transactional(readOnly = true)
    public SubmissionDetails view(long userId, Role role, long submissionId) {
        Submission submission = submissions.findById(submissionId).orElseThrow(SubmissionNotFoundException::new);
        if (role == Role.STUDENT) {
            if (submission.getStudentId() != userId) {
                throw new AccessRefusedException();
            }
        } else {
            try {
                assignmentService.requireManageable(userId, role, submission.getAssignmentId());
            } catch (AssignmentNotFoundException e) {
                throw new SubmissionNotFoundException();
            }
        }
        return details(submission);
    }

    private SubmissionDetails details(Submission submission) {
        long id = submission.getId();
        long assignmentId = submission.getAssignmentId();
        return new SubmissionDetails(
                submission,
                testCases.findByAssignmentIdOrderByPositionAscIdAsc(assignmentId),
                testResults.findBySubmissionIdOrderByIdAsc(id),
                rubricCriteria.findByAssignmentIdOrderByIdAsc(assignmentId),
                criterionScores.findBySubmissionIdOrderByCriterionIdAsc(id),
                grades.findBySubmissionId(id));
    }
}
