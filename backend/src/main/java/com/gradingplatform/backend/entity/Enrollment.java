package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** A row of `enrollments` (V3): this student is in this course. */
@Entity
@Table(name = "enrollments")
public class Enrollment {

    @EmbeddedId
    private EnrollmentId id;

    /** Set by the database default (`NOW()`); read back after the insert, never written by us. */
    @Generated(event = EventType.INSERT)
    @Column(name = "enrolled_at", insertable = false, updatable = false)
    private Instant enrolledAt;

    /** For JPA only. */
    protected Enrollment() {}

    public Enrollment(Long courseId, Long studentId) {
        this.id = new EnrollmentId(courseId, studentId);
    }

    public EnrollmentId getId() {
        return id;
    }

    public Long getCourseId() {
        return id.courseId();
    }

    public Long getStudentId() {
        return id.studentId();
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }
}
