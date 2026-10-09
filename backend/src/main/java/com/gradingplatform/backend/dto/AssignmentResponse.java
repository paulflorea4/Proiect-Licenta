package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Assignment;
import java.time.Instant;

/**
 * An assignment as its teacher sees it. Student-facing shapes (3.3b) must be built separately: an
 * unpublished assignment is not visible to students at all.
 */
public record AssignmentResponse(
        Long id,
        Long courseId,
        String title,
        String description,
        String language,
        Instant deadline,
        Integer maxAttempts,
        int timeLimitMs,
        int memoryLimitMb,
        String starterCode,
        boolean published,
        Instant createdAt,
        Instant updatedAt) {

    public static AssignmentResponse from(Assignment a) {
        return new AssignmentResponse(
                a.getId(),
                a.getCourseId(),
                a.getTitle(),
                a.getDescription(),
                a.getLanguage(),
                a.getDeadline(),
                a.getMaxAttempts(),
                a.getTimeLimitMs(),
                a.getMemoryLimitMb(),
                a.getStarterCode(),
                a.isPublished(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
