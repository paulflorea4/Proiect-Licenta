package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.AiFeedback;
import java.time.Instant;
import java.util.List;

/**
 * The answer to `GET /submissions/{id}/hints` (7.2b): the hints a submission has received, level 1
 * first, in the shape the token's role picks, as for the submission itself (5.5a/5.5b). A fallback
 * hint is not stored (7.2a), so it is never listed.
 */
public sealed interface HintsResponse permits HintsResponse.StudentHints, HintsResponse.TeacherHints {

    /** The student's own hints: the level (what 7.4b's report takes), the text and when it came. */
    record StudentHints(long submissionId, List<StudentHint> hints) implements HintsResponse {

        public static StudentHints of(long submissionId, List<AiFeedback> stored) {
            return new StudentHints(
                    submissionId, stored.stream().map(StudentHint::of).toList());
        }
    }

    record StudentHint(int level, String content, Instant createdAt) {

        static StudentHint of(AiFeedback hint) {
            return new StudentHint(hint.getHintLevel(), hint.getContent(), hint.getCreatedAt());
        }
    }

    /** What the people who run the course see: each hint with its id and the student's report (7.4b). */
    record TeacherHints(long submissionId, List<TeacherHint> hints) implements HintsResponse {

        public static TeacherHints of(long submissionId, List<AiFeedback> stored) {
            return new TeacherHints(
                    submissionId, stored.stream().map(TeacherHint::of).toList());
        }
    }

    record TeacherHint(
            long id,
            int level,
            String content,
            Instant createdAt,
            boolean reported,
            String reportReason,
            Instant reportedAt) {

        static TeacherHint of(AiFeedback hint) {
            return new TeacherHint(
                    hint.getId(),
                    hint.getHintLevel(),
                    hint.getContent(),
                    hint.getCreatedAt(),
                    hint.isReported(),
                    hint.getReportReason(),
                    hint.getReportedAt());
        }
    }
}
