package it.medbook.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.config.AuthConfig;
import it.medbook.dto.CambioPasswordRequest;
import it.medbook.dto.LoginRequest;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.security.LoginThrottle;
import it.medbook.security.SessionStore;
import it.medbook.security.SessionSecurity;
import it.medbook.service.AutenticazioneService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Meccanismo condiviso con AutorizzazioneHandler per le API cliniche.
 * CSRF synchronizer token: login/me restituiscono X-CSRF-Token, mai il cookie nel JSON.
 * Logout (e login con sessione già valida) richiede lo stesso header e l'Origin configurata.
 * Login anonimo richiede Origin esatta e application/json: nessun CORS viene abilitato.
 * HTTPS è terminato da un reverse proxy fidato davanti al listener loopback di Main.
 * Non fidarsi di X-Forwarded-Proto/For; il throttling usa l'indirizzo del peer TCP.
 * Tutto lo stato della richiesta è locale: nessun principal condiviso nel controller.
 */
public final class AutenticazioneController implements HttpHandler {
    private static final int MAX_BODY = 8192;
    private final AutenticazioneService service;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final LoginThrottle passwordThrottle;
    private final AuthConfig config;
    private final ObjectMapper mapper;
    private final SessionSecurity security;

    public AutenticazioneController(AutenticazioneService service, SessionStore sessions,
                                    LoginThrottle throttle, AuthConfig config, ObjectMapper mapper) {
        this(service, sessions, throttle, new LoginThrottle(java.time.Clock.systemUTC(), 10_000, 10), config, mapper);
    }
    /* Limitatore password per chiave utente: dieci tentativi ogni cinque minuti. */
    public AutenticazioneController(AutenticazioneService service, SessionStore sessions, LoginThrottle throttle,
                                    LoginThrottle passwordThrottle, AuthConfig config, ObjectMapper mapper) {
        this.service = Objects.requireNonNull(service);
        this.sessions = Objects.requireNonNull(sessions);
        this.throttle = Objects.requireNonNull(throttle);
        this.passwordThrottle = Objects.requireNonNull(passwordThrottle);
        this.config = Objects.requireNonNull(config);
        this.mapper = Objects.requireNonNull(mapper);
        this.security = new SessionSecurity(sessions, config);
    }

    @Override public void handle(HttpExchange original) throws IOException {
        ScambioTracciato exchange = new ScambioTracciato(original);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        try {
            String path = exchange.getRequestURI().getRawPath();
            String expectedMethod = switch (path) {
                case "/api/auth/login", "/api/auth/logout" -> "POST";
                case "/api/auth/me" -> "GET";
                case "/api/auth/password" -> "PUT";
                default -> null;
            };
            if (expectedMethod == null) { error(exchange, 404, "Risorsa non trovata."); return; }
            if (!expectedMethod.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", expectedMethod);
                error(exchange, 405, "Metodo non consentito."); return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                error(exchange, 400, "Richiesta non valida."); return;
            }
            boolean originPresent = exchange.getRequestHeaders().containsKey("Origin");
            if ((expectedMethod.equals("POST") || originPresent) && !validOrigin(exchange)) {
                error(exchange, 403, "Richiesta non consentita."); return;
            }
            switch (path) {
                case "/api/auth/login" -> login(exchange);
                case "/api/auth/me" -> me(exchange);
                case "/api/auth/logout" -> logout(exchange);
                case "/api/auth/password" -> password(exchange);
                default -> throw new IllegalStateException("Rotta non valida.");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            senzaSecondaRisposta(exchange, e);
            error(exchange, 400, "Richiesta non valida.");
        } catch (SessionStore.CapacityException e) {
            senzaSecondaRisposta(exchange, e);
            exchange.getResponseHeaders().set("Retry-After", "60");
            error(exchange, 503, "Servizio temporaneamente non disponibile.");
        } catch (SQLException | IllegalStateException e) {
            senzaSecondaRisposta(exchange, e);
            exchange.getResponseHeaders().remove("Set-Cookie");
            exchange.getResponseHeaders().remove("X-CSRF-Token");
            error(exchange, 500, "Errore interno del server.");
        } finally {
            try { exchange.getRequestBody().close(); }
            finally { exchange.close(); }
        }
    }

    private void login(HttpExchange exchange) throws IOException, SQLException {
        String peer = exchange.getRemoteAddress() == null ? "unknown"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!throttle.allow(peer)) {
            exchange.getResponseHeaders().set("Retry-After", "300");
            error(exchange, 429, "Troppi tentativi. Riprova più tardi."); return;
        }
        SessionStore.Session previous = session(exchange).orElse(null);
        /* Il cookie presentato viene riverificato: sessione obsoleta, disabilitata, assente o anomala revocata */
        /* e ignorata senza rivelare il motivo; il login procede anonimo. Una sessione valida conserva CSRF. */
        if (previous != null) {
            boolean valida;
            try { valida = service.identity(previous).isPresent(); }
            catch (AnomaliaDatiAccountException e) { valida = false; }
            if (!valida) { previous = null; }
        }
        if (previous != null && !validCsrf(exchange, previous)) {
            error(exchange, 403, "Richiesta non consentita."); return;
        }
        try (LoginRequest request = readLogin(exchange)) {
            Optional<SessionStore.Session> authenticated = service.login(request.getEmail(), request.getPassword());
            if (authenticated.isEmpty()) { error(exchange, 401, "Credenziali non valide."); return; }
            SessionStore.Session created = authenticated.get();
            boolean completed = false;
            try {
                Optional<UtenteAutenticatoResponse> identity = service.identity(created);
                if (identity.isEmpty() || !sessions.touch(created)) {
                    error(exchange, 401, "Credenziali non valide."); return;
                }
                if (previous != null) { sessions.revoke(previous.id()); }
                exchange.getResponseHeaders().set("Set-Cookie", config.cookie(created.id(), false));
                exchange.getResponseHeaders().set("X-CSRF-Token", created.csrfToken());
                sendJson(exchange, 200, identity.get());
                completed = true;
            } finally {
                if (!completed) { sessions.revoke(created.id()); }
            }
        }
    }

    private void me(HttpExchange exchange) throws IOException, SQLException {
        Optional<SessionStore.Session> authenticated = session(exchange);
        if (authenticated.isEmpty()) { unauthenticated(exchange); return; }
        SessionStore.Session current = authenticated.get();
        Optional<UtenteAutenticatoResponse> identity = service.identity(current);
        if (identity.isEmpty() || !sessions.touch(current)) { unauthenticated(exchange); return; }
        exchange.getResponseHeaders().set("X-CSRF-Token", current.csrfToken());
        sendJson(exchange, 200, identity.get());
    }

    private void logout(HttpExchange exchange) throws IOException {
        Optional<SessionStore.Session> authenticated = session(exchange);
        if (authenticated.isEmpty()) { unauthenticated(exchange); return; }
        SessionStore.Session current = authenticated.get();
        if (!validCsrf(exchange, current)) { error(exchange, 403, "Richiesta non consentita."); return; }
        try (InputStream input = exchange.getRequestBody()) {
            if (input.read() != -1) { error(exchange, 400, "Richiesta non valida."); return; }
        }
        /* In ogni esito il token presentato viene revocato e viene inviato un cookie realmente scaduto. */
        int status; Map<String, String> body;
        try {
            boolean valida = service.identity(current).isPresent();
            status = valida ? 200 : 401;
            body = valida ? Map.of("message", "Logout effettuato.") : Map.of("error", "Autenticazione richiesta.");
        } catch (SQLException e) {
            /* Anomalia account o errore SQL: 500 generico senza dettagli della causa. */
            status = 500; body = Map.of("error", "Errore interno del server.");
        } finally {
            sessions.revoke(current.id());
        }
        exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
        sendJson(exchange, status, body);
    }

    /*
     * Cambio password: sessione corrente, Origin, CSRF e un Content-Type JSON. Errore prima del
     * commit conserva cookie; commit incerto produce 500 con cookie scaduto e nessun token;
     * conferma pubblica una nuova sessione o richiede nuovo accesso.
     */
    private void password(HttpExchange exchange) throws IOException, SQLException {
        Optional<SessionStore.Session> authenticated = session(exchange);
        if (authenticated.isEmpty()) { unauthenticated(exchange); return; }
        SessionStore.Session current = authenticated.get();
        try {
            if (service.identity(current).isEmpty()) { unauthenticated(exchange); return; }
        } catch (AnomaliaDatiAccountException e) { erroreConCookieScaduto(exchange); return; }
        if (!exchange.getRequestHeaders().containsKey("Origin") || !validOrigin(exchange) || !validCsrf(exchange, current)) {
            error(exchange, 403, "Richiesta non consentita."); return;
        }
        List<String> types = exchange.getRequestHeaders().get("Content-Type");
        if (types == null || types.size() != 1 || !types.getFirst().matches(
                "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\"))?\\s*")) {
            error(exchange, 415, "Content-Type non supportato."); return;
        }
        if (!passwordThrottle.allow("utente:" + current.idUtente())) {
            exchange.getResponseHeaders().set("Retry-After", "300");
            error(exchange, 429, "Troppi tentativi. Riprova più tardi."); return;
        }
        long nuovaGenerazione;
        try (CambioPasswordRequest request = readPassword(exchange)) {
            nuovaGenerazione = service.cambiaPassword(current, request.getPasswordAttuale(), request.getNuovaPassword());
        } catch (AutenticazioneService.SessioneNonValidaException e) {
            if (e.anomalia()) { erroreConCookieScaduto(exchange); } else { unauthenticated(exchange); }
            return;
        } catch (AutenticazioneService.PasswordAttualeErrataException e) {
            error(exchange, 403, e.getMessage()); return;
        } catch (AutenticazioneService.NuovaPasswordUgualeException e) {
            error(exchange, 400, e.getMessage()); return;
        } catch (it.medbook.repository.UtenteRepository.ConflittoAccountException | it.medbook.exception.GenerazioneEsauritaException e) {
            error(exchange, 409, "Operazione non disponibile per questo account."); return;
        } catch (it.medbook.exception.EsitoCommitIncertoException e) {
            erroreConCookieScaduto(exchange); return;
        }
        pubblica(exchange, current.idUtente(), nuovaGenerazione);
    }

    /* Solo dopo commit confermato: nessun 5xx; il candidato viene revocato in ogni altra uscita. */
    private void pubblica(HttpExchange exchange, int idUtente, long nuovaGenerazione) throws IOException {
        sessions.revokeStale(idUtente, nuovaGenerazione); /* Non rimuove sessioni di generazione uguale o successiva. */
        SessionStore.Session created = null;
        boolean completed = false;
        try {
            try { created = sessions.create(idUtente, nuovaGenerazione); }
            catch (SessionStore.CapacityException e) { nuovoAccessoRichiesto(exchange); return; }
            Optional<UtenteAutenticatoResponse> identity;
            try { identity = service.identity(created); }
            catch (SQLException e) { identity = Optional.empty(); }
            if (identity.isEmpty() || !sessions.touch(created)) {
                sessions.revoke(created.id()); nuovoAccessoRichiesto(exchange); return;
            }
            exchange.getResponseHeaders().set("Set-Cookie", config.cookie(created.id(), false));
            exchange.getResponseHeaders().set("X-CSRF-Token", created.csrfToken());
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("message", "Password aggiornata."); body.put("nuovoAccessoRichiesto", false);
            sendJson(exchange, 200, body);
            completed = true;
        } finally {
            if (!completed && created != null) { sessions.revoke(created.id()); }
        }
    }
    private void nuovoAccessoRichiesto(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
        exchange.getResponseHeaders().remove("X-CSRF-Token");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "Password aggiornata. Effettuare di nuovo l'accesso."); body.put("nuovoAccessoRichiesto", true);
        sendJson(exchange, 200, body);
    }
    private void erroreConCookieScaduto(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
        exchange.getResponseHeaders().remove("X-CSRF-Token");
        error(exchange, 500, "Errore interno del server.");
    }
    private CambioPasswordRequest readPassword(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) { body = input.readNBytes(MAX_BODY + 1); }
        if (body.length > MAX_BODY) { throw new IllegalArgumentException("Richiesta non valida."); }
        JsonNode json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        if (json == null || !json.isObject() || json.size() != 2 || !json.has("passwordAttuale") || !json.has("nuovaPassword")
                || !json.get("passwordAttuale").isTextual() || !json.get("nuovaPassword").isTextual()) {
            throw new IllegalArgumentException("Richiesta non valida.");
        }
        return new CambioPasswordRequest(json.get("passwordAttuale").textValue().toCharArray(),
                json.get("nuovaPassword").textValue().toCharArray());
    }

    private LoginRequest readLogin(HttpExchange exchange) throws IOException {
        String type = single(exchange.getRequestHeaders(), "Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new IllegalArgumentException("Richiesta non valida.");
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) { body = input.readNBytes(MAX_BODY + 1); }
        if (body.length > MAX_BODY) { throw new IllegalArgumentException("Richiesta non valida."); }
        JsonNode json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        if (json == null || !json.isObject() || json.size() != 2 || !json.has("email")
                || !json.has("password") || !json.get("email").isTextual() || !json.get("password").isTextual()) {
            throw new IllegalArgumentException("Richiesta non valida.");
        }
        return new LoginRequest(json.get("email").textValue(), json.get("password").textValue().toCharArray());
    }

    private Optional<SessionStore.Session> session(HttpExchange exchange) {
        return security.find(exchange);
    }
    private boolean validOrigin(HttpExchange exchange) {
        return security.validOrigin(exchange);
    }
    private boolean validCsrf(HttpExchange exchange, SessionStore.Session session) {
        return security.validCsrf(exchange, session);
    }
    private String single(Headers headers, String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }
    private void unauthenticated(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
        error(exchange, 401, "Autenticazione richiesta.");
    }
    private void error(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }
    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            // Un errore di output è interno, mai un JSON di richiesta malformato.
            if (status < 400) { throw new IllegalStateException("Serializzazione risposta non disponibile.", e); }
            status = 500;
            body = "{\"error\":\"Errore interno del server.\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) { if (!head) { output.write(body); } }
    }

    /*
     * Dopo sendResponseHeaders non si tenta una seconda risposta. Errore originale propagato,
     * exchange comunque chiuso e candidato login già revocato nel finally.
     */
    private static void senzaSecondaRisposta(ScambioTracciato exchange, Exception e) throws IOException {
        if (!exchange.rispostaAvviata) { return; }
        if (e instanceof RuntimeException runtime) { throw runtime; }
        if (e instanceof IOException io) { throw io; }
        throw new IOException("Errore dopo l'invio della risposta.", e);
    }

    /*
     * Wrapper locale alla richiesta: registra l'inizio dell'invio header, non solo quelli
     * preparati. Anche un invio fallito conta come iniziato, sul server reale e nei doppi.
     */
    private static final class ScambioTracciato extends HttpExchange {
        private final HttpExchange original;
        private boolean rispostaAvviata;
        ScambioTracciato(HttpExchange original) { this.original = Objects.requireNonNull(original); }
        @Override public void sendResponseHeaders(int code, long length) throws IOException {
            rispostaAvviata = true;
            original.sendResponseHeaders(code, length);
        }
        @Override public Headers getRequestHeaders() { return original.getRequestHeaders(); }
        @Override public Headers getResponseHeaders() { return original.getResponseHeaders(); }
        @Override public java.net.URI getRequestURI() { return original.getRequestURI(); }
        @Override public String getRequestMethod() { return original.getRequestMethod(); }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return original.getHttpContext(); }
        @Override public void close() { original.close(); }
        @Override public InputStream getRequestBody() { return original.getRequestBody(); }
        @Override public OutputStream getResponseBody() { return original.getResponseBody(); }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return original.getRemoteAddress(); }
        @Override public int getResponseCode() { return original.getResponseCode(); }
        @Override public java.net.InetSocketAddress getLocalAddress() { return original.getLocalAddress(); }
        @Override public String getProtocol() { return original.getProtocol(); }
        @Override public Object getAttribute(String name) { return original.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { original.setAttribute(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { original.setStreams(input, output); }
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return original.getPrincipal(); }
    }
}
