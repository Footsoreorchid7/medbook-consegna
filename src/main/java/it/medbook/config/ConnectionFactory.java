package it.medbook.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public class ConnectionFactory {
    private final DatabaseConfig databaseConfig;

    public ConnectionFactory() {
        this(new DatabaseConfig());
    }

    public ConnectionFactory(DatabaseConfig databaseConfig) {
        this.databaseConfig = Objects.requireNonNull(databaseConfig, "DatabaseConfig obbligatoria.");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(databaseConfig.getUrl(),
                databaseConfig.getUsername(), databaseConfig.getPassword());
    }
}
