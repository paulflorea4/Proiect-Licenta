package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.User;

/**
 * A row of a course's student list (3.2b): enough for a teacher to tell the students apart.
 * Nothing else of the account (no role, no hash).
 */
public record StudentSummaryResponse(Long id, String email, String fullName) {

    public static StudentSummaryResponse from(User student) {
        return new StudentSummaryResponse(student.getId(), student.getEmail(), student.getFullName());
    }
}
