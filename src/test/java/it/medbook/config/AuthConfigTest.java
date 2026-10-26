package it.medbook.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {
    @Test void productionCookieIsHostOnlySecureHttpOnlyAndLax() {
        var config = new AuthConfig("https","https://medbook.example.invalid");
        String cookie = config.cookie("opaque",false);
        assertEquals("__Host-medbook_session",config.cookieName());
        assertTrue(cookie.contains("; Secure")); assertTrue(cookie.contains("; HttpOnly"));
        assertTrue(cookie.contains("; SameSite=Lax")); assertTrue(cookie.contains("; Path=/"));
        assertFalse(cookie.contains("Domain=")); assertFalse(config.localHttp());
    }
    @Test void localHttpMustBeExplicitAndUsesDifferentCookieName() {
        var config = new AuthConfig("local-http","http://localhost:8080");
        assertTrue(config.localHttp()); assertEquals("medbook_local_session",config.cookieName());
        assertFalse(config.cookie("opaque",false).contains("; Secure"));
    }
    @Test void localModeRejectsNonLoopbackOrigins() {
        for(String origin : new String[]{"http://medbook.example.invalid", "http://0.0.0.0:8080", "https://localhost"}) {
            assertThrows(IllegalArgumentException.class, () -> new AuthConfig("local-http",origin));
        }
    }
    @Test void secureModeRejectsHttpAndImplicitLocalFallback() {
        assertThrows(IllegalArgumentException.class, () -> new AuthConfig("https","http://localhost:8080"));
        assertThrows(IllegalArgumentException.class, () -> new AuthConfig("production","https://example.invalid"));
        assertThrows(IllegalArgumentException.class, () -> new AuthConfig(null,"http://localhost"));
    }
    @Test void malformedOriginsAreRejected() {
        for(String origin : new String[]{"https://example.invalid/path", "https://user@example.invalid", "https://example.invalid?x=1",
                "https://example.invalid#x", "https://example.invalid:99999", "not-an-origin", "https://example.invalid/"}) {
            assertThrows(IllegalArgumentException.class, () -> new AuthConfig("https",origin));
        }
        assertThrows(IllegalArgumentException.class, () -> new AuthConfig("https",null));
    }
    @Test void logoutExpiresCookieWithSameScopeAndSecurityAttributes() {
        String expired = new AuthConfig("https","https://example.invalid").cookie("",true);
        assertTrue(expired.startsWith("__Host-medbook_session=;"));
        assertTrue(expired.contains("Max-Age=0")); assertTrue(expired.contains("Secure"));
        assertTrue(expired.contains("HttpOnly")); assertTrue(expired.contains("Path=/"));
    }
}
