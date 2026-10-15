package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SubmissionRequest;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.Submission;
import com.gradingplatform.backend.repository.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Students' submissions: accepting them (5.1a). Grading them is the worker's (5.2). */
@Service
public class SubmissionService {

    private final AssignmentService assignmentService;
    private final SubmissionRepository submissions;

    public SubmissionService(AssignmentService assignmentService, SubmissionRepository submissions) {
        this.assignmentService = assignmentService;
        this.submissions = submissions;
    }

    /**
     * Stores the code as the student's next attempt, {@code QUEUED}, and returns at once: nothing here
     * waits for grading. The assignment must be one the student may see (published, in a course they
     * are enrolled in), else it is the 404 of an id that does not exist. The language is the
     * assignment's, read under {@link AssignmentService#lockVisibleForSubmission}, so a teacher's
     * change of language either happens before (and is the one stored) or waits and is then refused.
     *
     * @throws AssignmentNotFoundException if there is no such assignment or the user may not see it
     */
    @Transactional
    public Submission submit(long userId, Role role, long assignmentId, SubmissionRequest request) {
        Assignment assignment = assignmentService.lockVisibleForSubmission(userId, role, assignmentId);
        int attemptNo = submissions.nextAttemptNo(assignmentId, userId);
        return submissions.saveAndFlush(
                new Submission(assignmentId, userId, assignment.getLanguage(), request.sourceCode(), attemptNo));
    }
}
