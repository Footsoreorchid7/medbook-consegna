package it.medbook.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {
    private static final String VALID_PROPERTIES =
            "db.url=jdbc:mysql://localhost:3306/medbook\ndb.username=medbook_user\n";

    @Test
    void loadsClasspathDefaultsWhenOverridesAreAbsent() {
        String generatedValue = UUID.randomUUID().toString();
        DatabaseConfig config = new DatabaseConfig(DatabaseConfig.class.getClassLoader(), key -> {
            return key.equals("MEDBOOK_DB_PASSWORD") ? generatedValue : null;
        });

        assertEquals("jdbc:mysql://localhost:3306/medbook", config.getUrl());
        assertEquals("medbook_user", config.getUsername());
        assertEquals(generatedValue, config.getPassword());
    }

    @Test
    void rejectsMissingOrBlankEnvironmentVariable() {
        for (String value : new String[]{null, "", "   "}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new DatabaseConfig(resource(VALID_PROPERTIES), key -> key.equals("MEDBOOK_DB_PASSWORD") ? value : null));
            assertTrue(error.getMessage().contains("MEDBOOK_DB_PASSWORD"));
        }
    }

    @Test
    void rejectsMissingResource() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseConfig(resource(null), generatedEnvironment()));
        assertTrue(error.getMessage().contains("application.properties"));
    }

    @Test
    void rejectsMissingOrBlankProperties() {
        for (String key : new String[]{"db.url", "db.username"}) {
            String otherProperty = key.equals("db.url")
                    ? "db.username=medbook_user\n"
                    : "db.url=jdbc:mysql://localhost:3306/medbook\n";
            for (String properties : new String[]{otherProperty, otherProperty + key + "=   \n"}) {
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> new DatabaseConfig(resource(properties), generatedEnvironment()));
                assertTrue(error.getMessage().contains(key));
            }
        }
    }

    @Test
    void reportsResourceReadFailure() {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Lettura non disponibile.");
                    }
                };
            }
        };
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseConfig(loader, generatedEnvironment()));
        assertTrue(error.getMessage().contains("application.properties"));
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void preservesEnvironmentValueWithoutTrimming() {
        String generatedValue = " " + UUID.randomUUID() + " ";
        DatabaseConfig config = new DatabaseConfig(resource(VALID_PROPERTIES), key -> key.equals("MEDBOOK_DB_PASSWORD") ? generatedValue : null);
        assertEquals(generatedValue, config.getPassword());
    }

    @Test
    void explicitOverridesTakePrecedenceAndAreTrimmed() {
        var values = java.util.Map.of("MEDBOOK_DB_URL", " jdbc:mysql://127.0.0.1:33999/medbook ",
                "MEDBOOK_DB_USERNAME", " prova ", "MEDBOOK_DB_PASSWORD", " segreto fittizio ");
        DatabaseConfig config = new DatabaseConfig(resource(VALID_PROPERTIES), values::get);
        assertEquals("jdbc:mysql://127.0.0.1:33999/medbook", config.getUrl());
        assertEquals("prova", config.getUsername());
        assertEquals(" segreto fittizio ", config.getPassword());
    }

    @Test
    void rejectsBlankOverridesInsteadOfFallingBack() {
        for (String variable : new String[]{"MEDBOOK_DB_URL", "MEDBOOK_DB_USERNAME"}) {
            for (String blank : new String[]{"", "   "}) {
                var values = java.util.Map.of(variable, blank, "MEDBOOK_DB_PASSWORD", "segreto fittizio");
                assertTrue(assertThrows(IllegalStateException.class,
                        () -> new DatabaseConfig(resource(VALID_PROPERTIES), values::get))
                        .getMessage().contains(variable));
            }
        }
    }

    @Test
    void overridesCanSupplyPropertiesAbsentFromTheResource() {
        var values = java.util.Map.of("MEDBOOK_DB_URL", "jdbc:mysql://localhost/medbook",
                "MEDBOOK_DB_USERNAME", "prova", "MEDBOOK_DB_PASSWORD", "segreto fittizio");
        assertEquals("prova", new DatabaseConfig(resource(""), values::get).getUsername());
    }

    private static Function<String, String> generatedEnvironment() {
        return key -> key.equals("MEDBOOK_DB_PASSWORD") ? UUID.randomUUID().toString() : null;
    }

    private static ClassLoader resource(String properties) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                assertEquals("application.properties", name);
                return properties == null ? null : new ByteArrayInputStream(
                        properties.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
