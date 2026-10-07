package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;

/** A row of the admin user list: enough to find a user and change their role. Nothing else. */
public record UserSummaryResponse(Long id, String email, Role role) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
