package com.gradingplatform.backend;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one Postgres every integration test uses (2.7a): a single throwaway container for the whole
 * test run, started the first time a test context needs a database and removed when the JVM ends
 * (Testcontainers' Ryuk). Never the compose database or a CI service container. The image matches
 * the major version of infra/docker-compose.yml.
 *
 * <p>Starting a container costs seconds and Spring builds a new context for each distinct test
 * configuration (a profile, a property, a mock bean, a dirtied context), so one container per
 * context made a run start eight of them. Instead every context gets its <b>own empty database</b>
 * inside the shared container: the isolation is the same as before (a context never sees another's
 * rows or Flyway history, so the dev-seed and out-of-order tests stay independent), only the
 * container is shared. Databases are not dropped; the container takes them all away at the end.
 */
final class SharedPostgres {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16");

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {}

    /** Creates a new empty database in the shared container and returns how to connect to it. */
    static JdbcConnectionDetails newDatabase() {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create a test database in the shared Postgres", e);
        }
        String url = "jdbc:postgresql://" + CONTAINER.getHost() + ":"
                + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return new TestDatabase(url, CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    private record TestDatabase(String url, String username, String password) implements JdbcConnectionDetails {

        @Override
        public String getJdbcUrl() {
            return url;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }
    }
}
