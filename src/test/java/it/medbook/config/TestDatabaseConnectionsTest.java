package it.medbook.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseConnectionsTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://127.0.0.1:13306/medbook_test_uc20", "jdbc:mysql://localhost:13307/medbook_test"})
    void acceptsOnlyExplicitLocalTestDestinations(String url) {
        assertTrue(TestDatabaseConnections.validUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "jdbc:mysql://localhost:3306/medbook", "jdbc:mysql://localhost:3306/medbook_test",
            "jdbc:mysql://localhost/medbook_test", "jdbc:mysql://remote:13306/medbook_test",
            "jdbc:mysql://127.0.0.1:13306/medbook", "jdbc:mysql://127.0.0.1:13306/medbook_test?allowMultiQueries=true",
            "jdbc:mysql://127.0.0.1:13306/medbook_test/medbook", "jdbc:mysql://localhost:65536/medbook_test",
            "jdbc:mysql://localhost:0/medbook_test", "jdbc:mysql://localhost:13306/%6dedbook_test",
            "jdbc:mysql://localhost:13306/medbook_test#medbook", "jdbc:mysql://localhost:13306/medbook_test_"})
    void refusesAmbiguousOrApplicationDestinations(String url) {
        assertFalse(TestDatabaseConnections.validUrl(url));
        assertFalse(TestDatabaseConnections.validUrl(null));
    }
}
