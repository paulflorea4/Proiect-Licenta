package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * 5.1e: the shared test Postgres runs with the raised connection limit and keeps Testcontainers'
 * {@code fsync=off}. No Spring context, so this test adds no connection pool of its own.
 */
class SharedPostgresTests {

    @Test
    void theServerAllowsTheConfiguredConnectionsAndKeepsFsyncOff() throws Exception {
        JdbcConnectionDetails database = SharedPostgres.newDatabase();

        try (Connection connection = DriverManager.getConnection(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(setting(statement, "max_connections")).isEqualTo(String.valueOf(SharedPostgres.MAX_CONNECTIONS));
            assertThat(setting(statement, "fsync")).isEqualTo("off");
        }
    }

    @Test
    void moreConnectionsThanTheOldDefaultCanBeOpenAtOnce() throws Exception {
        // 120 is over Postgres's default of 100 on its own, and leaves the pools other contexts hold room.
        JdbcConnectionDetails database = SharedPostgres.newDatabase();
        List<Connection> open = new ArrayList<>();
        try {
            for (int i = 0; i < 120; i++) {
                open.add(DriverManager.getConnection(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            }
            assertThat(open)
                    .hasSize(120)
                    .allSatisfy(connection -> assertThat(connection.isValid(2)).isTrue());
        } finally {
            for (Connection connection : open) {
                connection.close();
            }
        }
    }

    private static String setting(Statement statement, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("show " + name)) {
            result.next();
            return result.getString(1);
        }
    }
}
