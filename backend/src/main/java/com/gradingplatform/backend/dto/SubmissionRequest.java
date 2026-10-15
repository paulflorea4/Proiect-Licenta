package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of `POST /assignments/{id}/submissions` with the code as text (5.1a). The code is stored exactly
 * as sent; the language is the assignment's, so the body has none (anything else sent is ignored).
 * Size, deadline and attempt limits are checked by the service (5.1c).
 */
public record SubmissionRequest(@NotBlank String sourceCode) {

    /** Student code stays out of any log line that prints the request. */
    @Override
    public String toString() {
        return "SubmissionRequest[sourceCode=<" + (sourceCode == null ? "null" : sourceCode.length() + " chars") + ">]";
    }
}
