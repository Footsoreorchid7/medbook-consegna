package it.medbook.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Nessun fallback verso application.properties o verso la password del database applicativo. */
public final class TestDatabaseConnections {
    private TestDatabaseConnections() { }

    // Configurazione sintetica per factory che sovrascrivono getConnection nei test.
    // URL volutamente non collegabile; nessuna credenziale reale o ambiente necessario.
    public static DatabaseConfig simulatedConfig() {
        byte[] properties = "db.url=jdbc:invalid:unit-test\ndb.username=unit-test\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ClassLoader resources = new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(String name) {
                return "application.properties".equals(name) ? new ByteArrayInputStream(properties) : null;
            }
        };
        return new DatabaseConfig(resources, key -> "MEDBOOK_DB_PASSWORD".equals(key) ? java.util.UUID.randomUUID().toString() : null);
    }

    static boolean validUrl(String url) {
        if (url == null) { return false; }
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/(medbook_test(?:_[A-Za-z0-9_]+)?)").matcher(url);
        if (!match.matches()) { return false; }
        int port = Integer.parseInt(match.group(1));
        return port >= 1024 && port <= 65535 && port != 3306;
    }

    public static ConnectionFactory isolatedFactory() {
        return isolatedFactory("MEDBOOK_TEST_DB_URL");
    }

    /* Catalogo dedicato aggiuntivo sullo stesso server isolato, con le stesse credenziali e UUID. */
    public static ConnectionFactory isolatedFactory(String urlVariable) {
        String url = System.getenv(urlVariable);
        String username = System.getenv("MEDBOOK_TEST_DB_USERNAME");
        String password = System.getenv("MEDBOOK_TEST_DB_PASSWORD");
        assumeTrue(validUrl(url),
                "Richiesto database medbook_test[_suffisso], host locale, porta dedicata diversa da 3306, nessun parametro.");
        assumeTrue(username != null && !username.isBlank() && password != null && !password.isBlank(),
                "UC-20 richiede MEDBOOK_TEST_DB_USERNAME e MEDBOOK_TEST_DB_PASSWORD.");
        Properties properties = new Properties();
        properties.setProperty("db.url", url);
        properties.setProperty("db.username", username);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { properties.store(bytes, null); }
        catch (java.io.IOException e) { throw new IllegalStateException("Configurazione di test non serializzabile.", e); }
        ClassLoader resources = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "application.properties".equals(name) ? new ByteArrayInputStream(bytes.toByteArray()) : null;
            }
        };
        DatabaseConfig config = new DatabaseConfig(resources,
                key -> "MEDBOOK_DB_PASSWORD".equals(key) ? password : null);
        String expectedDatabase = url.substring(url.lastIndexOf('/') + 1);
        String expectedUuid = System.getenv("MEDBOOK_TEST_DB_SERVER_UUID");
        return new ConnectionFactory(config) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = super.getConnection();
                try {
                    if (!expectedDatabase.equals(connection.getCatalog())) {
                        throw new java.sql.SQLException("Destinazione di test incompatibile: catalogo errato.");
                    }
                    try (java.sql.Statement statement = connection.createStatement();
                         java.sql.ResultSet result = statement.executeQuery("SELECT DATABASE(), @@server_uuid")) {
                        if (!result.next() || !expectedDatabase.equals(result.getString(1))
                                || (expectedUuid != null && !expectedUuid.isBlank() && !expectedUuid.equals(result.getString(2)))) {
                            throw new java.sql.SQLException("Identità del server/database di test incompatibile.");
                        }
                    }
                    return connection;
                } catch (java.sql.SQLException | RuntimeException e) {
                    try { connection.close(); } catch (java.sql.SQLException closeError) { e.addSuppressed(closeError); }
                    throw e;
                }
            }
        };
    }
}
