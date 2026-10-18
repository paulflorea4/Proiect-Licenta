package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.GradeRepository;
import com.gradingplatform.backend.repository.GradeRepository.GradedAttempt;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading grades (5.4c). They are written by {@link SubmissionResultWriter} only. */
@Service
public class GradeService {

    private final AssignmentService assignmentService;
    private final GradeRepository grades;

    public GradeService(AssignmentService assignmentService, GradeRepository grades) {
        this.assignmentService = assignmentService;
        this.grades = grades;
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
}
