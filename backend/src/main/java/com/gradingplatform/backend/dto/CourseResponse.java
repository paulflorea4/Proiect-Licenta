package com.gradingplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gradingplatform.backend.entity.Course;
import com.gradingplatform.backend.entity.Role;
import java.time.Instant;

/**
 * A course as the API shows it. The enrollment code lets anyone who has it join, so only the
 * people who run the course get it: build this with {@link #forRole}, which leaves the code out for
 * a student. For a student the {@code enrollCode} key is absent from the JSON, not null.
 */
public record CourseResponse(
        Long id,
        String title,
        String description,
        Long teacherId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String enrollCode,
        Instant createdAt) {

    /** With the enrollment code: for the owning teacher and for an admin. */
    public static CourseResponse from(Course course) {
        return build(course, course.getEnrollCode());
    }

    /** For whoever is looking: a student never receives the enrollment code. */
    public static CourseResponse forRole(Course course, Role viewer) {
        return viewer == Role.STUDENT ? build(course, null) : from(course);
    }

    private static CourseResponse build(Course course, String enrollCode) {
        return new CourseResponse(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getTeacherId(),
                enrollCode,
                course.getCreatedAt());
    }
}
