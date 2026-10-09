package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of `POST /courses/{id}/assignments`. There is no `published`, `courseId` or id: the course
 * is the path's, and a new assignment is always unpublished, so a value sent for any of them is
 * ignored. Whether the language is supported and whether the deadline is still ahead are checked by
 * the service (they depend on configuration and on the clock).
 *
 * <p>The title and language limits match their columns. Every other maximum is a server-side
 * placeholder pending the human's call (flagged in the PR): the description and starter code are
 * TEXT columns and get caps so a request cannot store an arbitrary blob, and the time and memory
 * limits cannot be set above what the sandbox should ever be asked to give. Time and memory
 * limits are required, as the spec lists them as the teacher's choice and the columns are
 * NOT NULL, so there is no default that could quietly become a loosened limit.
 *
 * @param maxAttempts null = unlimited attempts
 * @param timeLimitMs wall-clock limit of one run, in milliseconds
 * @param memoryLimitMb memory limit of one run, in megabytes
 * @param starterCode null = no starter code
 */
public record AssignmentRequest(
        @NotBlank @Size(max = AssignmentRequest.TITLE_MAX) String title,

        @NotBlank @Size(max = AssignmentRequest.DESCRIPTION_MAX)
        String description,

        @NotBlank @Size(max = AssignmentRequest.LANGUAGE_MAX)
        String language,

        @NotNull Instant deadline,
        @Min(1) @Max(AssignmentRequest.MAX_ATTEMPTS_MAX) Integer maxAttempts,

        @NotNull @Min(1) @Max(AssignmentRequest.TIME_LIMIT_MAX_MS)
        Integer timeLimitMs,

        @NotNull @Min(1) @Max(AssignmentRequest.MEMORY_LIMIT_MAX_MB)
        Integer memoryLimitMb,

        @Size(max = AssignmentRequest.STARTER_CODE_MAX) String starterCode) {

    public static final int TITLE_MAX = 255;
    public static final int LANGUAGE_MAX = 20;

    // Placeholders pending the human's decision.
    public static final int DESCRIPTION_MAX = 20_000;
    public static final int STARTER_CODE_MAX = 50_000;
    public static final int MAX_ATTEMPTS_MAX = 100;
    public static final int TIME_LIMIT_MAX_MS = 10_000;
    public static final int MEMORY_LIMIT_MAX_MB = 1024;

    /** Surrounding whitespace on the title is dropped, and blank starter code is the same as none. */
    public AssignmentRequest {
        if (title != null) {
            title = title.trim();
        }
        if (starterCode != null && starterCode.isBlank()) {
            starterCode = null;
        }
    }
}
