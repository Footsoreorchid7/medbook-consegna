package it.medbook.config;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionIntegrationTest {
    @Test
    void connectsToMySqlAndExecutesSelectOne() throws SQLException {

        ConnectionFactory factory = it.medbook.config.TestDatabaseConnections.isolatedFactory();

        try (Connection connection = factory.getConnection()) {
            assertNotNull(connection);
            assertFalse(connection.isClosed());

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }
}
