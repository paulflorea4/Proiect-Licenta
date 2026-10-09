package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.dto.CourseResponse;
import com.gradingplatform.backend.dto.PageResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.CourseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * The courses the caller can see, as {@link PageResponse} in id order: a teacher's own, a
     * student's enrolled courses, all of them for an admin. Any signed-in user may call it; what
     * they get depends only on who they are. Students do not receive the enrollment code.
     */
    @GetMapping
    public PageResponse<CourseResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {
        var pageable = PageResponse.pageable(page, size, Sort.by("id"));
        return PageResponse.of(
                courseService.listVisibleTo(principal.id(), principal.role(), pageable),
                course -> CourseResponse.forRole(course, principal.role()));
    }

    /**
     * Creates a course owned by the caller and generates its enrollment code. Teachers only: an
     * admin does not create courses (the row says teacher, and there is no role hierarchy, 2.5a).
     */
    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CourseRequest request) {
        return CourseResponse.from(courseService.create(principal.id(), request));
    }
}
