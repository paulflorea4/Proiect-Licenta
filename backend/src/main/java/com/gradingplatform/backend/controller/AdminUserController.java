package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.PageResponse;
import com.gradingplatform.backend.dto.RoleChangeRequest;
import com.gradingplatform.backend.dto.UserResponse;
import com.gradingplatform.backend.dto.UserSummaryResponse;
import com.gradingplatform.backend.service.UserAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * All users, oldest account first (by id, which never changes, so pages do not overlap), as
     * {@link PageResponse}. Id, email and role only: enough to find whom to promote.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserSummaryResponse> list(
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var pageable = PageResponse.pageable(page, size, Sort.by("id"));
        return PageResponse.of(userAdminService.listUsers(pageable), UserSummaryResponse::from);
    }
}
