package com.gradingplatform.backend.entity;

/**
 * A user's role. Stored as its name in `users.role` (a plain VARCHAR, validated here rather than by
 * a database CHECK). Signup always creates a {@link #STUDENT}; only an admin promotes to
 * {@link #TEACHER}.
 */
public enum Role {
    STUDENT,
    TEACHER,
    ADMIN
}
