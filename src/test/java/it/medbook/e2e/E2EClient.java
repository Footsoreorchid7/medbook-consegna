package it.medbook.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/*
 * Un client HTTP per attore senza stato condiviso. Identità solo da login reale, mai Principal
 * costruito o store manipolato. Ogni risposta aggiorna cookie e CSRF; cookie scaduto li pulisce.
 * Snapshot credenziali riproduce sessioni vecchie per verificarne revoca. Header extra
 * sostituiscono per nome senza distinzione maiuscole; valore vuoto rimuove. Gli header riservati
 * richiedono socket grezzo, non HttpClient.
 */
public final class E2EClient implements AutoCloseable {
    /* Status, header e corpo grezzo, vuoto per HEAD o risposte senza corpo. */
    public record Risposta(int status, HttpHeaders headers, byte[] body) {
        public Risposta { body = body == null ? new byte[0] : body; }
        public String testo() { return new String(body, StandardCharsets.UTF_8); }
        public Optional<String> header(String name) { return headers.firstValue(name); }
        public List<String> headers(String name) { return headers.allValues(name); }
        public JsonNode json() throws IOException { return MAPPER.readTree(body); }
    }

    /* Snapshot delle credenziali di sessione restituite dal server a un attore. */
    public record Credenziali(String cookie, String csrf) {
        @Override public String toString() { return "Credenziali[***]"; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final E2EServer server;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private String cookie;
    private String csrf;

    public E2EClient(E2EServer server) { this.server = Objects.requireNonNull(server); }

    /* Client separato con credenziali storiche da login reale per verificare revoche. */
    public static E2EClient conCredenziali(E2EServer server, Credenziali credenziali) {
        E2EClient c = new E2EClient(server);
        c.cookie = credenziali.cookie(); c.csrf = credenziali.csrf();
        return c;
    }

    /* Rilascia HttpClient, connessioni e thread selettore dell'attore. */
    @Override public void close() { http.close(); }

    public Optional<Credenziali> credenziali() {
        return cookie == null ? Optional.empty() : Optional.of(new Credenziali(cookie, csrf));
    }

    public Risposta login(String email, String password) throws IOException, InterruptedException {
        Risposta r = postJson("/api/auth/login", Map.of("email", email, "password", password));
        if (r.status() == 200 && (cookie == null || csrf == null)) { throw new IllegalStateException("Login senza credenziali."); }
        return r;
    }

    public Risposta get(String path) throws IOException, InterruptedException { return invia("GET", path, null, Map.of()); }

    public Risposta postJson(String path, Object body) throws IOException, InterruptedException {
        return invia("POST", path, body instanceof String s ? s : MAPPER.writeValueAsString(body), Map.of());
    }

    public Risposta putJson(String path, Object body) throws IOException, InterruptedException {
        return invia("PUT", path, body instanceof String s ? s : MAPPER.writeValueAsString(body), Map.of());
    }

    public Risposta invia(String method, String path, String body, Map<String, String> extra)
            throws IOException, InterruptedException {
        return inviaBytes(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                body == null ? null : "application/json", extra);
    }

    public Risposta inviaBytes(String method, String path, byte[] body, String contentType, Map<String, String> extra)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.base() + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        intestazioni(server.authConfig().origin(), contentType, cookie == null ? null : server.authConfig().cookieName() + "=" + cookie,
                csrf, extra).forEach(b::header);
        HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        Risposta r = new Risposta(response.statusCode(), response.headers(), response.body());
        aggiorna(r);
        return r;
    }

    /* Fusione degli header senza distinzione maiuscole; valori extra vuoti rimuovono il predefinito. */
    static Map<String, String> intestazioni(String origin, String contentType, String cookieHeader, String csrfToken,
                                            Map<String, String> extra) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.put("Origin", origin);
        if (contentType != null) { h.put("Content-Type", contentType); }
        if (cookieHeader != null) { h.put("Cookie", cookieHeader); }
        if (csrfToken != null) { h.put("X-CSRF-Token", csrfToken); }
        extra.forEach((k, v) -> { h.remove(k); if (v != null && !v.isEmpty()) { h.put(k, v); } });
        return h;
    }

    private void aggiorna(Risposta r) {
        String prefix = server.authConfig().cookieName() + "=";
        for (String c : r.headers("Set-Cookie")) {
            if (!c.startsWith(prefix)) { continue; }
            if (c.contains("Max-Age=0")) { cookie = null; csrf = null; }
            else { cookie = c.substring(prefix.length(), c.indexOf(';') < 0 ? c.length() : c.indexOf(';')); }
        }
        r.header("X-CSRF-Token").ifPresent(t -> { if (cookie != null) { csrf = t; } });
    }
}
