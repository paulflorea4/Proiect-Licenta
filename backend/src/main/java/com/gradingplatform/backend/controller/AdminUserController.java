package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.RoleChangeRequest;
import com.gradingplatform.backend.dto.UserResponse;
import com.gradingplatform.backend.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account administration. Every endpoint here is for admins only. */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserAdminService userAdminService;

    public AdminUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /**
     * The only way a `TEACHER` or `ADMIN` is created in the running system. The change reaches the
     * user's token only at their next signin (the token carries the role, 2.4b).
     */
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeRole(@PathVariable long id, @Valid @RequestBody RoleChangeRequest request) {
        return UserResponse.from(userAdminService.changeRole(id, request.role()));
    }
}
