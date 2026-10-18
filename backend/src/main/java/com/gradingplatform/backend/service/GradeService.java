package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.ManualScoreRequest;
import com.gradingplatform.backend.entity.CriterionScore;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.entity.SubmissionStatus;
import com.gradingplatform.backend.repository.CriterionScoreRepository;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.repository.GradeRepository.GradedAttempt;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import com.gradingplatform.backend.repository.SubmissionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grades: reading a student's (5.4c) and a teacher's manual scores (5.4d). Computed scores and the
 * {@code grades} row are written by {@link SubmissionResultWriter} only.
 */
@Service
public class GradeService {

    private final AssignmentService assignmentService;
    private final GradeRepository grades;
    private final SubmissionRepository submissions;
    private final RubricCriterionRepository rubricCriteria;
    private final CriterionScoreRepository criterionScores;

    public GradeService(
            AssignmentService assignmentService,
            GradeRepository grades,
            SubmissionRepository submissions,
            RubricCriterionRepository rubricCriteria,
            CriterionScoreRepository criterionScores) {
        this.assignmentService = assignmentService;
        this.grades = grades;
        this.submissions = submissions;
        this.rubricCriteria = rubricCriteria;
        this.criterionScores = criterionScores;
    }

    /**
     * The student's best graded attempt at the assignment, the one whose score counts: the highest
     * total, and of equal totals the earliest attempt. Empty while no attempt is graded.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional(readOnly = true)
    public Optional<GradedAttempt> bestAttemptOf(long userId, Role role, long assignmentId) {
        assignmentService.getVisibleTo(userId, role, assignmentId);
        return grades.findGradedAttemptsBestFirst(assignmentId, userId, Limit.of(1)).stream()
                .findFirst();
    }

    /**
     * Stores a teacher's score for a {@code MANUAL} criterion of a graded submission (5.4d),
     * replacing an earlier one. Any attempt may be scored, not only the best. Only the people who
     * manage the assignment's course may; anyone else gets the 404 of a submission that does not
     * exist. The grade's total is not changed here.
     *
     * @throws SubmissionNotFoundException if there is no such submission or the user may not manage it
     * @throws CriterionNotFoundException if the submission's assignment has no such criterion
     * @throws CriterionNotManualException if the criterion is not a {@code MANUAL} one
     * @throws SubmissionNotGradedException if the submission is not {@code GRADED}
     * @throws ManualScoreOutOfRangeException if the score is below 0 or above the criterion's weight
     */
    @Transactional
    public CriterionScore enterManualScore(
            long userId, Role role, long submissionId, long criterionId, ManualScoreRequest request) {
        Submission submission = submissions.findById(submissionId).orElseThrow(SubmissionNotFoundException::new);
        try {
            assignmentService.requireManageable(userId, role, submission.getAssignmentId());
        } catch (AssignmentNotFoundException e) {
            throw new SubmissionNotFoundException();
        }
        RubricCriterion criterion = rubricCriteria
                .findByIdAndAssignmentId(criterionId, submission.getAssignmentId())
                .orElseThrow(CriterionNotFoundException::new);
        if (criterion.getType() != CriterionType.MANUAL) {
            throw new CriterionNotManualException();
        }
        if (submission.getStatus() != SubmissionStatus.GRADED) {
            throw new SubmissionNotGradedException();
        }
        BigDecimal maxScore = BigDecimal.valueOf(criterion.getWeight());
        if (request.score().signum() < 0 || request.score().compareTo(maxScore) > 0) {
            throw new ManualScoreOutOfRangeException();
        }
        criterionScores.upsert(submissionId, criterionId, request.score(), maxScore, request.comment());
        return criterionScores
                .findBySubmissionIdAndCriterionId(submissionId, criterionId)
                .orElseThrow();
    }
}
