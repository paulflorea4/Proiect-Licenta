package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 1.4a: with the dev seed location on the Flyway path (what the `dev` profile will do, 2.2a), the
 * four dev users exist and their documented passwords match the stored BCrypt hashes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev-seed")
class DevSeedUsersTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    UserRepository users;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void theUserRepositoryReadsTheSeededRolesBack() {
        assertThat(users.findByEmail("admin@dev.example.com").orElseThrow().getRole())
                .isEqualTo(Role.ADMIN);
        assertThat(users.findByEmail("teacher@dev.example.com").orElseThrow().getRole())
                .isEqualTo(Role.TEACHER);
        assertThat(users.findByEmail("student1@dev.example.com").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    @Test
    void seedMigrationWasAppliedAfterTheSchemaMigrations() {
        Boolean applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1000'", Boolean.class);

        assertThat(applied).isTrue();
    }

    @Test
    void oneAdminOneTeacherAndTwoStudentsExist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT email, role FROM users ORDER BY email");

        assertThat(rows)
                .extracting(row -> row.get("email") + " " + row.get("role"))
                .containsExactly(
                        "admin@dev.example.com ADMIN",
                        "student1@dev.example.com STUDENT",
                        "student2@dev.example.com STUDENT",
                        "teacher@dev.example.com TEACHER");
    }

    @Test
    void documentedPasswordsMatchTheStoredHashes() {
        assertPasswordMatches("admin@dev.example.com", "Admin-dev-1");
        assertPasswordMatches("teacher@dev.example.com", "Teacher-dev-1");
        assertPasswordMatches("student1@dev.example.com", "Student-dev-1");
        assertPasswordMatches("student2@dev.example.com", "Student-dev-2");
    }

    @Test
    void aWrongPasswordDoesNotMatch() {
        String hash = hashOf("admin@dev.example.com");

        assertThat(encoder.matches("Teacher-dev-1", hash)).isFalse();
    }

    private void assertPasswordMatches(String email, String password) {
        assertThat(encoder.matches(password, hashOf(email))).as(email).isTrue();
    }

    private String hashOf(String email) {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
    }
}
