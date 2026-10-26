package it.medbook.config;

import java.net.URI;
import java.util.Objects;

/**
 * Produzione: HTTPS esterno con reverse proxy fidato; cookie sempre Secure.
 * Sviluppo: MEDBOOK_AUTH_MODE=local-http e MEDBOOK_AUTH_ORIGIN=http://localhost:8080
 * (oppure 127.0.0.1). Il listener è locale per default, con override esplicito separato.
 * Nessuna inferenza da Host/X-Forwarded-Proto controllabili dal client.
 */
public final class AuthConfig {
    private final String origin;
    private final boolean localHttp;
    public AuthConfig(String mode, String origin) {
        this.localHttp = "local-http".equals(mode);
        if (!localHttp && !"https".equals(mode)) { throw new IllegalArgumentException("Modalità autenticazione non valida."); }
        URI uri;
        try { uri = URI.create(Objects.requireNonNull(origin)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Origin autenticazione non valida."); }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getPort() < -1 || uri.getPort() > 65535
                || !(localHttp ? "http" : "https").equals(uri.getScheme())
                || (localHttp && !uri.getHost().equals("localhost") && !uri.getHost().equals("127.0.0.1"))) {
            throw new IllegalArgumentException("Origin autenticazione incompatibile con la modalità.");
        }
        this.origin = uri.toASCIIString();
    }
    public static AuthConfig fromEnvironment() {
        String mode = System.getenv("MEDBOOK_AUTH_MODE");
        String origin = System.getenv("MEDBOOK_AUTH_ORIGIN");
        if (mode == null) { mode = "https"; }
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException("MEDBOOK_AUTH_ORIGIN obbligatoria; HTTP richiede modalità local-http esplicita.");
        }
        return new AuthConfig(mode, origin);
    }
    public String origin() { return origin; }
    public boolean localHttp() { return localHttp; }
    public String cookieName() { return localHttp ? "medbook_local_session" : "__Host-medbook_session"; }
    public String cookie(String value, boolean expired) {
        return cookieName() + "=" + value + "; Path=/; HttpOnly; SameSite=Lax"
                + (localHttp ? "" : "; Secure") + (expired ? "; Max-Age=0" : "");
    }
}
