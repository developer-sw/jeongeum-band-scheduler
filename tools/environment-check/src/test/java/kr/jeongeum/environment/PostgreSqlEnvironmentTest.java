package kr.jeongeum.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the local Java/Docker/JDBC environment; contains no application policies. */
@Testcontainers
class PostgreSqlEnvironmentTest {
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("environment_check")
                    .withUsername("environment_check")
                    .withPassword(UUID.randomUUID().toString())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    @Timeout(60)
    void connectsToPostgreSqlAndCommitsAndRollsBackTransactions() throws Exception {
        assertTrue(POSTGRES.isRunning());

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result =
                    statement.executeQuery("SELECT current_setting('server_version_num')::integer")) {
                assertTrue(result.next());
                assertEquals(170011, result.getInt(1));
            }

            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE environment_probe (id integer PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO environment_probe (id) VALUES (1)");
            connection.commit();

            statement.executeUpdate("INSERT INTO environment_probe (id) VALUES (2)");
            connection.rollback();
        }

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT count(*), sum(id) FROM environment_probe")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertEquals(1, result.getInt(2));
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
