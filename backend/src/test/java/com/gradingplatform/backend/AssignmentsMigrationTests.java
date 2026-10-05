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

/** V4 (1.2d): the `assignments` table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AssignmentsMigrationTests {

    private static final String INSERT_ASSIGNMENT = "INSERT INTO assignments"
            + " (course_id, title, description, language, deadline, time_limit_ms, memory_limit_mb)"
            + " VALUES (?, ?, ?, ?, NOW() + INTERVAL '7 days', ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long courseId;

    @BeforeEach
    void insertCourse() {
        Long teacherId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role)"
                        + " VALUES ('teacher@example.com', 'hash', 'Teo Ionescu', 'TEACHER') RETURNING id",
                Long.class);
        courseId = jdbcTemplate.queryForObject(
                "INSERT INTO courses (title, teacher_id, enroll_code) VALUES ('Algorithms', ?, 'ABC234') RETURNING id",
                Long.class,
                teacherId);
    }

    @Test
    void v4WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '4'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void newAssignmentIsUnpublishedWithOptionalFieldsEmptyAndTimestampsSet() {
        jdbcTemplate.update(INSERT_ASSIGNMENT, courseId, "Sum two numbers", "Read two ints", "JAVA", 2000, 256);

        assertThat(jdbcTemplate.queryForObject("SELECT id FROM assignments", Long.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT published FROM assignments", Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT max_attempts FROM assignments", Integer.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT starter_code FROM assignments", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM assignments", OffsetDateTime.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM assignments", OffsetDateTime.class))
                .isNotNull();
    }

    @Test
    void optionalFieldsCanBeSet() {
        jdbcTemplate.update(INSERT_ASSIGNMENT, courseId, "Sum two numbers", "Read two ints", "PYTHON", 2000, 256);
        jdbcTemplate.update("UPDATE assignments SET max_attempts = 3, starter_code = 'print()', published = TRUE");

        assertThat(jdbcTemplate.queryForObject("SELECT max_attempts FROM assignments", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT starter_code FROM assignments", String.class))
                .isEqualTo("print()");
        assertThat(jdbcTemplate.queryForObject("SELECT published FROM assignments", Boolean.class))
                .isTrue();
    }

    @Test
    void languageIsNotRestrictedByTheDatabase() {
        jdbcTemplate.update(INSERT_ASSIGNMENT, courseId, "Title", "Description", "SOME_FUTURE_LANG", 2000, 256);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignments", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void courseMustExist() {
        assertInsertRejected(-1L, "Title", "Description", "JAVA", 2000, 256);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void courseIsRequired() {
        assertInsertRejected(null, "Title", "Description", "JAVA", 2000, 256);
    }

    @Test
    void titleIsRequired() {
        assertInsertRejected(courseId, null, "Description", "JAVA", 2000, 256);
    }

    @Test
    void descriptionIsRequired() {
        assertInsertRejected(courseId, "Title", null, "JAVA", 2000, 256);
    }

    @Test
    void languageIsRequired() {
        assertInsertRejected(courseId, "Title", "Description", null, 2000, 256);
    }

    @Test
    void timeLimitIsRequired() {
        assertInsertRejected(courseId, "Title", "Description", "JAVA", null, 256);
    }

    @Test
    void memoryLimitIsRequired() {
        assertInsertRejected(courseId, "Title", "Description", "JAVA", 2000, null);
    }

    @Test
    void deadlineIsRequired() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO assignments"
                                + " (course_id, title, description, language, deadline, time_limit_ms, memory_limit_mb)"
                                + " VALUES (?, 'Title', 'Description', 'JAVA', NULL, 2000, 256)",
                        courseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInsertRejected(
            Long course, String title, String description, String language, Integer timeLimitMs, Integer memoryMb) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        INSERT_ASSIGNMENT, course, title, description, language, timeLimitMs, memoryMb))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
