package com.gradingplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gradingplatform.backend.entity.Assignment;
import com.gradingplatform.backend.entity.Role;
import java.time.Instant;

/**
 * An assignment as the API shows it. Build it with {@link #forRole} (or {@link #from} for the
 * people who run the course). A student never sees an unpublished assignment, so the draft state
 * and the bookkeeping timestamps are for the teacher's view only: for a student the {@code
 * published}, {@code createdAt} and {@code updatedAt} keys are absent from the JSON, not null. Later
 * phases add the rubric and tests to this shape and must mask them for students here.
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean published,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant updatedAt) {

    /** The full view: for the owning teacher and for an admin. */
    public static AssignmentResponse from(Assignment a) {
        return build(a, a.isPublished(), a.getCreatedAt(), a.getUpdatedAt());
    }

    /** For whoever is looking: a student gets no draft state and no timestamps. */
    public static AssignmentResponse forRole(Assignment a, Role viewer) {
        return viewer == Role.STUDENT ? build(a, null, null, null) : from(a);
    }

    private static AssignmentResponse build(Assignment a, Boolean published, Instant createdAt, Instant updatedAt) {
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
                published,
                createdAt,
                updatedAt);
    }
}
