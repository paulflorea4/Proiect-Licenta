package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;

/** A user as the API shows them (signup, signin; later /auth/me). Never contains the password or its hash. */
public record UserResponse(Long id, String email, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
