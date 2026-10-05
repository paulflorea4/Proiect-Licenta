package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V3 (1.2c): the `enrollments` join table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class EnrollmentsMigrationTests {

    private static final String INSERT_ENROLLMENT = "INSERT INTO enrollments (course_id, student_id) VALUES (?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long courseId;
    private Long studentId;

    @BeforeEach
    void insertCourseAndStudent() {
        Long teacherId = insertUser("teacher@example.com", "TEACHER");
        studentId = insertUser("student@example.com", "STUDENT");
        courseId = jdbcTemplate.queryForObject(
                "INSERT INTO courses (title, teacher_id, enroll_code) VALUES ('Algorithms', ?, 'ABC234') RETURNING id",
                Long.class,
                teacherId);
    }

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'hash', 'Some Name', ?)"
                        + " RETURNING id",
                Long.class,
                email,
                role);
    }

    @Test
    void v3WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void insertedEnrollmentGetsEnrolledAt() {
        jdbcTemplate.update(INSERT_ENROLLMENT, courseId, studentId);

        OffsetDateTime enrolledAt = jdbcTemplate.queryForObject(
                "SELECT enrolled_at FROM enrollments WHERE course_id = ? AND student_id = ?",
                OffsetDateTime.class,
                courseId,
                studentId);

        assertThat(enrolledAt).isNotNull();
    }

    @Test
    void studentCanOnlyEnrollInACourseOnce() {
        jdbcTemplate.update(INSERT_ENROLLMENT, courseId, studentId);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ENROLLMENT, courseId, studentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void courseMustExist() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ENROLLMENT, -1L, studentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void studentMustExist() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ENROLLMENT, courseId, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void courseIsRequired() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ENROLLMENT, null, studentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void studentIsRequired() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ENROLLMENT, courseId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
