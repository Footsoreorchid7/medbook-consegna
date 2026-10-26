package it.medbook.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.Function;

public class DatabaseConfig {
    private final String url;
    private final String username;
    private final String password;

    public DatabaseConfig() {
        this(DatabaseConfig.class.getClassLoader(), System::getenv);
    }

    // Consente ai test nello stesso package di simulare risorse e ambiente.
    DatabaseConfig(ClassLoader classLoader, Function<String, String> environment) {
        Properties properties = new Properties();
        try (InputStream input = classLoader.getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new IllegalStateException("application.properties non trovato nel classpath.");
            }
            properties.load(input);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Impossibile caricare application.properties.", e);
        }

        url = configured(properties, environment, "MEDBOOK_DB_URL", "db.url");
        username = configured(properties, environment, "MEDBOOK_DB_USERNAME", "db.username");
        password = environment.apply("MEDBOOK_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "La variabile d'ambiente MEDBOOK_DB_PASSWORD è mancante o vuota.");
        }
    }

    // Un override esplicito vuoto è un errore, non un invito a usare la destinazione predefinita.
    private static String configured(Properties properties, Function<String, String> environment,
                                     String variable, String property) {
        String value = environment.apply(variable);
        if (value == null) { return requiredProperty(properties, property); }
        if (value.isBlank()) { throw new IllegalStateException("Configurazione vuota: " + variable + "."); }
        return value.trim();
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configurazione mancante o vuota: " + key + ".");
        }
        return value.trim();
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
