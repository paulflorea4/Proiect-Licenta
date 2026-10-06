package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;

/** Body of a successful signup: who was created. Never contains the password or its hash. */
public record SignupResponse(Long id, String email, String fullName, Role role) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
