package com.gradingplatform.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** A row of `assignments` (V4). New assignments are unpublished. */
@Entity
@Table(name = "assignments")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    /** Upper-case identifier from the languages list (`JAVA`, `PYTHON`). */
    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    /** Null = unlimited attempts. */
    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs;

    @Column(name = "memory_limit_mb", nullable = false)
    private int memoryLimitMb;

    /** Null = no starter code. */
    @Column(name = "starter_code")
    private String starterCode;

    @Column(name = "published", nullable = false)
    private boolean published;

    /** Set by the database default (`NOW()`); read back after the insert, never written by us. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Set by the database default on insert; 3.3c makes the application maintain it on updates. */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Assignment() {}

    /** A new, unpublished assignment. */
    public Assignment(
            Long courseId,
            String title,
            String description,
            String language,
            Instant deadline,
            Integer maxAttempts,
            int timeLimitMs,
            int memoryLimitMb,
            String starterCode) {
        this.courseId = courseId;
        this.title = title;
        this.description = description;
        this.language = language;
        this.deadline = deadline;
        this.maxAttempts = maxAttempts;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.starterCode = starterCode;
        this.published = false;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLanguage() {
        return language;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public String getStarterCode() {
        return starterCode;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
