package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V1 (1.2a): the `users` table exists with the agreed constraints and defaults. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class UsersMigrationTests {

    private static final String INSERT_USER =
            "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v1WasAppliedSuccessfully() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void insertedUserGetsGeneratedIdAndCreatedAt() {
        jdbcTemplate.update(INSERT_USER, "ana@example.com", "hash", "Ana Pop", "STUDENT");

        Long id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'ana@example.com'", Long.class);
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM users WHERE email = 'ana@example.com'", OffsetDateTime.class);

        assertThat(id).isNotNull();
        assertThat(createdAt).isNotNull();
    }

    @Test
    void emailMustBeUnique() {
        jdbcTemplate.update(INSERT_USER, "dup@example.com", "hash", "First", "STUDENT");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_USER, "dup@example.com", "hash2", "Second", "TEACHER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One test per column: Postgres aborts the surrounding transaction after the first error.
    @Test
    void emailIsRequired() {
        assertInsertRejected(null, "hash", "Name", "STUDENT");
    }

    @Test
    void passwordHashIsRequired() {
        assertInsertRejected("a@example.com", null, "Name", "STUDENT");
    }

    @Test
    void fullNameIsRequired() {
        assertInsertRejected("b@example.com", "hash", null, "STUDENT");
    }

    @Test
    void roleIsRequired() {
        assertInsertRejected("c@example.com", "hash", "Name", null);
    }

    private void assertInsertRejected(String email, String passwordHash, String fullName, String role) {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_USER, email, passwordHash, fullName, role))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
