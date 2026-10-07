package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Body of `PATCH /admin/users/{id}/role`: the role to give the user, spelled exactly as the enum
 * (`STUDENT`, `TEACHER`, `ADMIN`). A missing, null or unknown value is a 400.
 */
public record RoleChangeRequest(@NotNull Role role) {}
