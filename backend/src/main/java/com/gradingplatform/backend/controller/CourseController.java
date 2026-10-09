package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.CourseRequest;
import com.gradingplatform.backend.dto.CourseResponse;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
