package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.Course;
import java.time.Instant;

/**
 * A course as its owner sees it, enrollment code included. The code lets anyone join, so 3.1b and
 * 3.1c decide who else gets this shape; do not return it from a student-facing endpoint unchanged.
 */
public record CourseResponse(
        Long id, String title, String description, Long teacherId, String enrollCode, Instant createdAt) {

    public static CourseResponse from(Course course) {
        return new CourseResponse(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getTeacherId(),
                course.getEnrollCode(),
                course.getCreatedAt());
    }
}
