package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.RubricCriterionRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.RubricCriterion;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rubric of an assignment (3.4a): its weighted criteria. Only the people who manage the
 * assignment's course (owning teacher, admin) can read or change them; anyone else gets the
 * "assignment not found" of an id that does not exist.
 *
 * <p>Once the assignment has a submission the criteria and their weights are locked, because
 * existing grades would silently stop matching them, and while it is published (3.5a: the rubric
 * is finished before publication, so it is edited on a draft). Every change takes the assignment's
 * row lock first ({@link AssignmentService#lockDraftForGradingChange}), so the check for
 * submissions cannot race with one being made. That the weights add up to 100 is not enforced
 * here, only when publishing (3.4b).
 */
@Service
public class RubricService {

    /** The foreign key `test_cases.criterion_id` -> `rubric_criteria.id` (Postgres default name for V6's inline `REFERENCES`). */
    static final String TEST_CASE_FOREIGN_KEY = "test_cases_criterion_id_fkey";

    private final RubricCriterionRepository criteria;
    private final AssignmentService assignmentService;

    public RubricService(RubricCriterionRepository criteria, AssignmentService assignmentService) {
        this.criteria = criteria;
        this.assignmentService = assignmentService;
    }

    /**
     * The assignment's criteria in creation order.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     */
    @Transactional(readOnly = true)
    public List<RubricCriterion> list(long userId, Role role, long assignmentId) {
        assignmentService.requireManageable(userId, role, assignmentId);
        return criteria.findByAssignmentIdOrderByIdAsc(assignmentId);
    }

    /**
     * Adds a criterion.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws CriterionTypeNotAvailableException if no phase has enabled the type yet
     * @throws RubricLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     */
    @Transactional
    public RubricCriterion create(long userId, Role role, long assignmentId, RubricCriterionRequest request) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, RubricLockedException::new);
        requireAvailable(request);
        return criteria.saveAndFlush(
                new RubricCriterion(assignmentId, request.name(), request.type(), request.weight()));
    }

    /**
     * Replaces a criterion's name, type and weight.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws CriterionNotFoundException if the assignment has no such criterion
     * @throws CriterionTypeNotAvailableException if no phase has enabled the type yet
     * @throws RubricLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     */
    @Transactional
    public RubricCriterion update(
            long userId, Role role, long assignmentId, long criterionId, RubricCriterionRequest request) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, RubricLockedException::new);
        requireAvailable(request);
        RubricCriterion criterion = criteria.findByIdAndAssignmentId(criterionId, assignmentId)
                .orElseThrow(CriterionNotFoundException::new);
        criterion.revise(request.name(), request.type(), request.weight());
        return criteria.saveAndFlush(criterion);
    }

    /**
     * Deletes a criterion. Test cases that still belong to it are never deleted along with it: the
     * foreign key refuses, and the refusal is the 409.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws CriterionNotFoundException if the assignment has no such criterion
     * @throws RubricLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     * @throws CriterionHasTestsException if test cases belong to the criterion
     */
    @Transactional
    public void delete(long userId, Role role, long assignmentId, long criterionId) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, RubricLockedException::new);
        RubricCriterion criterion = criteria.findByIdAndAssignmentId(criterionId, assignmentId)
                .orElseThrow(CriterionNotFoundException::new);
        try {
            criteria.delete(criterion);
            criteria.flush();
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, TEST_CASE_FOREIGN_KEY)) {
                throw new CriterionHasTestsException();
            }
            throw e;
        }
    }

    private static void requireAvailable(RubricCriterionRequest request) {
        if (!request.type().isAvailable()) {
            throw new CriterionTypeNotAvailableException();
        }
    }
}
