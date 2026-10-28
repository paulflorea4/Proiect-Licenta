package com.gradingplatform.backend.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body of `POST /submissions/{id}/hints/{level}/report` (7.4b): why the student thinks the
 * hint is wrong or unhelpful. Stored as sent for the teacher and the thesis; never sent to the AI
 * service. A blank reason is the same as none, so a stored reason is never an empty string. No NUL
 * character ({@link NoNul}).
 */
public record HintReportRequest(
        @Size(max = HintReportRequest.REASON_MAX) @NoNul String reason) {

    /** A placeholder cap pending the human's call (flagged in the PR), as for a manual score's comment. */
    public static final int REASON_MAX = 2000;

    public HintReportRequest {
        if (reason != null && reason.isBlank()) {
            reason = null;
        }
    }

    /** The reason is the student's own text: kept out of logs. */
    @Override
    public String toString() {
        return "HintReportRequest[reason=" + (reason == null ? "none" : reason.length() + " chars") + "]";
    }
}
