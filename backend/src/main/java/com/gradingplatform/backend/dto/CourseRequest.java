package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of `POST /courses`. There is deliberately no `teacherId` and no `enrollCode`: the owner is
 * the caller and the code is generated, so a value sent for either is ignored. The title limit
 * matches its VARCHAR(255) column; the description (a TEXT column) gets a cap of its own so a
 * request cannot store an arbitrarily large blob.
 */
public record CourseRequest(
        @NotBlank @Size(max = CourseRequest.TITLE_MAX) String title,
        @Size(max = CourseRequest.DESCRIPTION_MAX) String description) {

    public static final int TITLE_MAX = 255;

    /** A placeholder cap pending the human's call (flagged in the PR). */
    public static final int DESCRIPTION_MAX = 5000;

    /**
     * Surrounding whitespace on the title is dropped before validation, and a blank description is
     * the same as none, so the stored values are never padded or empty strings.
     */
    public CourseRequest {
        if (title != null) {
            title = title.trim();
        }
        if (description != null && description.isBlank()) {
            description = null;
        }
    }
}
