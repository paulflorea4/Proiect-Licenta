package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.TestCaseRequest;
import com.gradingplatform.backend.entity.CriterionType;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.repository.RubricCriterionRepository;
import com.gradingplatform.backend.repository.TestCaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The test cases of an assignment (3.5a). Only the people who manage the assignment's course
 * (owning teacher, admin) can read or change them; anyone else gets the "assignment not found" of
 * an id that does not exist. Students never reach this service: their shape (3.5b) masks hidden
 * tests and is built elsewhere.
 *
 * <p>Every test belongs to a {@code TESTS} criterion of the same assignment. A test can only be
 * added, changed or deleted on a draft ({@link AssignmentPublishedException}) that nobody has
 * submitted to yet ({@link TestsLockedException}: results refer to the tests, so they stay as they
 * were graded). Each change takes the assignment's row lock first, as the rubric does.
 */
@Service
public class TestCaseService {

    private final TestCaseRepository tests;
    private final RubricCriterionRepository criteria;
    private final AssignmentService assignmentService;

    public TestCaseService(
            TestCaseRepository tests, RubricCriterionRepository criteria, AssignmentService assignmentService) {
        this.tests = tests;
        this.criteria = criteria;
        this.assignmentService = assignmentService;
    }

    /**
     * The assignment's tests in run order.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     */
    @Transactional(readOnly = true)
    public List<TestCase> list(long userId, Role role, long assignmentId) {
        assignmentService.requireManageable(userId, role, assignmentId);
        return tests.findByAssignmentIdOrderByPositionAscIdAsc(assignmentId);
    }

    /**
     * Adds a test, after the last one unless the request gives a position.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws TestsLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     * @throws TestCriterionInvalidException if the criterion is not a `TESTS` criterion of this assignment
     */
    @Transactional
    public TestCase create(long userId, Role role, long assignmentId, TestCaseRequest request) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, TestsLockedException::new);
        requireTestsCriterion(assignmentId, request.criterionId());
        int position = request.position() != null ? request.position() : tests.nextPosition(assignmentId);
        return tests.saveAndFlush(new TestCase(
                assignmentId,
                request.criterionId(),
                request.name(),
                request.input(),
                request.expectedOutput(),
                request.visibility(),
                request.weight(),
                position));
    }

    /**
     * Replaces a test's criterion, name, input, expected output, visibility, weight and position
     * (a position left out keeps the current one).
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws TestsLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     * @throws TestCaseNotFoundException if the assignment has no such test
     * @throws TestCriterionInvalidException if the criterion is not a `TESTS` criterion of this assignment
     */
    @Transactional
    public TestCase update(long userId, Role role, long assignmentId, long testId, TestCaseRequest request) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, TestsLockedException::new);
        TestCase test = tests.findByIdAndAssignmentId(testId, assignmentId).orElseThrow(TestCaseNotFoundException::new);
        requireTestsCriterion(assignmentId, request.criterionId());
        test.revise(
                request.criterionId(),
                request.name(),
                request.input(),
                request.expectedOutput(),
                request.visibility(),
                request.weight(),
                request.position() != null ? request.position() : test.getPosition());
        return tests.saveAndFlush(test);
    }

    /**
     * Deletes a test.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not manage it
     * @throws TestsLockedException if the assignment has submissions
     * @throws AssignmentPublishedException if the assignment is published
     * @throws TestCaseNotFoundException if the assignment has no such test
     */
    @Transactional
    public void delete(long userId, Role role, long assignmentId, long testId) {
        assignmentService.lockDraftForGradingChange(userId, role, assignmentId, TestsLockedException::new);
        TestCase test = tests.findByIdAndAssignmentId(testId, assignmentId).orElseThrow(TestCaseNotFoundException::new);
        tests.delete(test);
        tests.flush();
    }

    /** A criterion that does not exist, is another assignment's or is not a `TESTS` one are one and the same error. */
    private void requireTestsCriterion(long assignmentId, long criterionId) {
        criteria.findByIdAndAssignmentId(criterionId, assignmentId)
                .filter(c -> c.getType() == CriterionType.TESTS)
                .orElseThrow(TestCriterionInvalidException::new);
    }
}
