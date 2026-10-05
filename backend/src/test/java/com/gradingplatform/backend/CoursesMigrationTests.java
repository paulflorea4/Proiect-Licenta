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

/** V2 (1.2b): the `courses` table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class CoursesMigrationTests {

    private static final String INSERT_COURSE =
            "INSERT INTO courses (title, description, teacher_id, enroll_code) VALUES (?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long teacherId;

    @BeforeEach
    void insertTeacher() {
        teacherId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role)"
                        + " VALUES ('teacher@example.com', 'hash', 'Teo Ionescu', 'TEACHER') RETURNING id",
                Long.class);
    }

    @Test
    void v2WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void insertedCourseGetsGeneratedIdAndCreatedAt() {
        jdbcTemplate.update(INSERT_COURSE, "Algorithms", "Intro course", teacherId, "ABC234");

        Long id = jdbcTemplate.queryForObject("SELECT id FROM courses WHERE enroll_code = 'ABC234'", Long.class);
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM courses WHERE enroll_code = 'ABC234'", OffsetDateTime.class);

        assertThat(id).isNotNull();
        assertThat(createdAt).isNotNull();
    }

    @Test
    void descriptionIsOptional() {
        jdbcTemplate.update(INSERT_COURSE, "Algorithms", null, teacherId, "ABC234");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM courses", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void enrollCodeMustBeUnique() {
        jdbcTemplate.update(INSERT_COURSE, "First", null, teacherId, "ABC234");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_COURSE, "Second", null, teacherId, "ABC234"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teacherMustExist() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_COURSE, "Orphan", null, -1L, "ABC234"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void titleIsRequired() {
        assertInsertRejected(null, teacherId, "ABC234");
    }

    @Test
    void teacherIsRequired() {
        assertInsertRejected("Algorithms", null, "ABC234");
    }

    @Test
    void enrollCodeIsRequired() {
        assertInsertRejected("Algorithms", teacherId, null);
    }

    private void assertInsertRejected(String title, Long teacher, String enrollCode) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_COURSE, title, null, teacher, enrollCode))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
