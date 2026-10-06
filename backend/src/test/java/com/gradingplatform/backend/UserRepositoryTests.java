package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2.3a: the `User` entity maps the `users` table (V1). Startup already fails if the mapping does
 * not match the schema (`ddl-auto: validate`); these tests check the behaviour on top of that.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class UserRepositoryTests {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void roleHasExactlyTheThreeValuesTheMigrationDocuments() {
        assertThat(Arrays.stream(Role.values()).map(Enum::name)).containsExactly("STUDENT", "TEACHER", "ADMIN");
    }

    @Test
    void savedUserGetsAGeneratedIdAndTheDatabaseCreatedAt() {
        Instant before = Instant.now().minusSeconds(60);

        User saved = users.saveAndFlush(new User("ada@example.com", "hash", "Ada Lovelace", Role.STUDENT));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull().isAfter(before);
    }

    @Test
    void everyFieldIsWrittenToItsColumn() {
        User saved = users.saveAndFlush(new User("ada@example.com", "$2a$10$hash", "Ada Lovelace", Role.TEACHER));

        var row = jdbcTemplate.queryForMap(
                "SELECT email, password_hash, full_name, role FROM users WHERE id = ?", saved.getId());
        assertThat(row)
                .containsEntry("email", "ada@example.com")
                .containsEntry("password_hash", "$2a$10$hash")
                .containsEntry("full_name", "Ada Lovelace")
                .containsEntry("role", "TEACHER");
    }

    @Test
    void roleIsStoredAsItsNameNotItsOrdinal() {
        User saved = users.saveAndFlush(new User("root@example.com", "hash", "Root", Role.ADMIN));

        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("ADMIN");
    }

    @Test
    void aRowInsertedWithSqlIsReadBackWithAllFields() {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, ?, ?, ?)",
                "grace@example.com",
                "hash",
                "Grace Hopper",
                "TEACHER");

        User found = users.findByEmail("grace@example.com").orElseThrow();

        assertThat(found.getFullName()).isEqualTo("Grace Hopper");
        assertThat(found.getPasswordHash()).isEqualTo("hash");
        assertThat(found.getRole()).isEqualTo(Role.TEACHER);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByEmailFindsTheUserOrNothing() {
        users.saveAndFlush(new User("ada@example.com", "hash", "Ada", Role.STUDENT));

        assertThat(users.findByEmail("ada@example.com")).isPresent();
        assertThat(users.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void existsByEmailReportsWhetherTheEmailIsTaken() {
        users.saveAndFlush(new User("ada@example.com", "hash", "Ada", Role.STUDENT));

        assertThat(users.existsByEmail("ada@example.com")).isTrue();
        assertThat(users.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void emailLookupIsExactAndCaseSensitiveLikeTheUniqueConstraint() {
        users.saveAndFlush(new User("ada@example.com", "hash", "Ada", Role.STUDENT));

        assertThat(users.findByEmail("ADA@example.com")).isEmpty();
    }

    @Test
    void theDatabaseStillRejectsADuplicateEmail() {
        users.saveAndFlush(new User("ada@example.com", "hash", "Ada", Role.STUDENT));

        assertThatThrownBy(() -> users.saveAndFlush(new User("ada@example.com", "other", "Other", Role.STUDENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
