package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V12 (9.0d): the enrollments that users promoted before the migration still had are removed;
 * students keep theirs, and nothing else is touched. The migration ran at startup on an empty
 * database, so this runs its statement again on data inserted here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class EnrollmentsOfNonStudentsMigrationTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'hash', 'Some Name', ?)"
                        + " RETURNING id",
                Long.class,
                email,
                role);
    }

    private static String v12() throws IOException {
        return new ClassPathResource("db/migration/V12__drop_enrollments_of_non_students.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void v12WasAppliedSuccessfully() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '12'", Boolean.class))
                .isTrue();
    }

    @Test
    void onlyTheStudentsEnrollmentsAreKept() throws IOException {
        Long owner = insertUser("owner@example.com", "TEACHER");
        Long student = insertUser("student@example.com", "STUDENT");
        Long promotedTeacher = insertUser("teacher@example.com", "TEACHER");
        Long promotedAdmin = insertUser("admin@example.com", "ADMIN");
        Long courseId = jdbcTemplate.queryForObject(
                "INSERT INTO courses (title, teacher_id, enroll_code) VALUES ('Algorithms', ?, 'MIGR2345') RETURNING id",
                Long.class,
                owner);
        for (Long user : List.of(student, promotedTeacher, promotedAdmin)) {
            jdbcTemplate.update("INSERT INTO enrollments (course_id, student_id) VALUES (?, ?)", courseId, user);
        }

        jdbcTemplate.execute(v12());

        assertThat(jdbcTemplate.queryForList(
                        "SELECT student_id FROM enrollments WHERE course_id = ?", Long.class, courseId))
                .containsExactly(student);
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, promotedTeacher))
                .isEqualTo("TEACHER");
    }
}
