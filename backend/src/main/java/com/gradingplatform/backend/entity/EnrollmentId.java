package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** The composite primary key of `enrollments` (V3): a student joins a course at most once. */
@Embeddable
public record EnrollmentId(
        @Column(name = "course_id", nullable = false) Long courseId,
        @Column(name = "student_id", nullable = false) Long studentId) {}
