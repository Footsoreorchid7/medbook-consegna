package it.medbook.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @Test void defaultsRemainLocal() {
        var address = new ServerConfig(key -> null).address();
        assertEquals("127.0.0.1", address.getAddress().getHostAddress());
        assertEquals(8080, address.getPort());
    }
    @Test void explicitInterfaceAndPortAreAccepted() {
        var values = Map.of("MEDBOOK_HTTP_HOST", " 0.0.0.0 ", "MEDBOOK_HTTP_PORT", " 18080 ");
        var address = new ServerConfig(values::get).address();
        assertTrue(address.getAddress().isAnyLocalAddress());
        assertEquals(18080, address.getPort());
    }
    @Test void invalidPortsAreRejected() {
        for (String port : new String[]{"", " ", "0", "65536", "-1", "8.0", "8080x"}) {
            assertThrows(IllegalStateException.class, () -> new ServerConfig(
                    key -> key.equals("MEDBOOK_HTTP_PORT") ? port : null));
        }
    }
    @Test void invalidHostsAreRejected() {
        for (String host : new String[]{"", "  ", "http://localhost", "127.0.0.1:8080"}) {
            assertThrows(IllegalStateException.class, () -> new ServerConfig(
                    key -> key.equals("MEDBOOK_HTTP_HOST") ? host : null));
        }
    }
    @Test void boundaryPortsAreAccepted() {
        for (String port : new String[]{"1", "65535"}) {
            assertEquals(Integer.parseInt(port), new ServerConfig(
                    key -> key.equals("MEDBOOK_HTTP_PORT") ? port : null).address().getPort());
        }
    }
}
