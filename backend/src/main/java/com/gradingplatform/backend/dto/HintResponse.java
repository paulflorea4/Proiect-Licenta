package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.AiFeedback;
import java.time.Instant;

/**
 * A hint for a submission (7.2a).
 *
 * @param submissionId the submission it is for
 * @param level 1 nudge, 2 pointer, 3 partial explanation
 * @param content the hint's text
 * @param fallback true when the AI service could only give its generic safe hint (6.4b): it is shown
 *     but not stored, so the level is still unused and can be asked for again (the human's decision)
 * @param createdAt when it was stored; null for a fallback, which is not
 */
public record HintResponse(long submissionId, int level, String content, boolean fallback, Instant createdAt) {

    public static HintResponse of(AiFeedback hint) {
        return new HintResponse(
                hint.getSubmissionId(), hint.getHintLevel(), hint.getContent(), false, hint.getCreatedAt());
    }

    public static HintResponse fallback(long submissionId, int level, String content) {
        return new HintResponse(submissionId, level, content, true, null);
    }
}
