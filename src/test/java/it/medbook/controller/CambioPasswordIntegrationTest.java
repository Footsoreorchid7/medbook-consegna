package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import it.medbook.config.*;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.MutableClock;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Cambio password HTTP su MySQL isolato: proxy JDBC producono gli esiti del commit; ganci dopo
 * commit controllano la pubblicazione. Attese osservate in performance_schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CambioPasswordIntegrationTest {
    static final String VECCHIA = "Vecchia password U6 di prova", NUOVA = "Nuova password U6 di prova";
    final ObjectMapper mapper = new ObjectMapper();
    final AuthConfig config = new AuthConfig("local-http", "http://localhost:8080");
    final PasswordHasher hasher = new PasswordHasher();
    final List<Integer> users = new ArrayList<>();
    ConnectionFactory factory; String hashVecchia; int sequence = new Random().nextInt(17_000);
    MutableClock clock; SessionStore store; AutenticazioneService service; AutenticazioneController auth;

    @BeforeAll void fixture() { factory = TestDatabaseConnections.isolatedFactory(); hashVecchia = hasher.hash(VECCHIA.toCharArray()); }
    @BeforeEach void wiring() { clock = new MutableClock(); store = new SessionStore(Clock.systemUTC(), 1000); wire(new UtenteRepository(factory, clock)); }
    void wire(UtenteRepository repository) {
        service = new AutenticazioneService(repository, hasher, store);
        auth = new AutenticazioneController(service, store, new LoginThrottle(Clock.systemUTC(), 10_000, 10_000),
                new LoginThrottle(Clock.systemUTC(), 10_000, 10), config, mapper);
    }
    @AfterAll void cleanup() throws Exception { if (factory != null) { for (int id : users) { sql("DELETE FROM utente WHERE id_utente=?", id); } } }

    enum Categoria { PAZIENTE, MEDICO, DESK, AMMINISTRATORE, PAZIENTE_MEDICO }
    record Account(int id, String email) { }
    Account account(Categoria categoria) throws Exception {
        String email = "u6-" + UUID.randomUUID() + "@example.invalid";
        String ruolo = categoria == Categoria.DESK ? "OPERATORE_DESK" : categoria == Categoria.AMMINISTRATORE ? "AMMINISTRATORE" : null;
        int id;
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES('Fixture','U6',?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, email); s.setString(2, hashVecchia); s.setString(3, ruolo); assertEquals(1, s.executeUpdate());
            try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); id = k.getInt(1); users.add(id); }
        }
        if (categoria == Categoria.PAZIENTE || categoria == Categoria.PAZIENTE_MEDICO) {
            int n = sequence++; String sei = "ZZV" + (char) ('A' + n / 676 % 26) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26);
            sql("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,'1990-01-01')", id, RicercaUtentiControllerTest.codiceFiscale(sei));
        }
        if (categoria == Categoria.MEDICO || categoria == Categoria.PAZIENTE_MEDICO) {
            sql("INSERT INTO medico(id_utente,specializzazione,numero_iscrizione_ordine) VALUES(?,'Generale',?)", id, "U6-" + UUID.randomUUID());
        }
        return new Account(id, email);
    }
    void sql(String statement, Object... values) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
        }
    }
    record Riga(String hash, BigInteger generazione, Integer da, LocalDateTime il, String stato) { }
    Riga riga(int id) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT password_hash, generazione_autenticazione, modificato_da, modificato_il, stato_accesso FROM utente WHERE id_utente=?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                return new Riga(r.getString(1), r.getObject(2, BigInteger.class), r.getObject(3, Integer.class), r.getObject(4, LocalDateTime.class), r.getString(5));
            }
        }
    }
    LocalDateTime ora() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }

    TestHttpExchange login(Account a, String password) throws Exception {
        var x = new TestHttpExchange("POST", "/api/auth/login", mapper.writeValueAsString(Map.of("email", a.email(), "password", password)));
        x.requestHeaders.set("Origin", config.origin()); auth.handle(x); return x;
    }
    SessionStore.Session sessione(TestHttpExchange x) {
        assertEquals(200, x.status, x.body());
        String cookie = x.responseHeaders.getFirst("Set-Cookie");
        return store.find(cookie.substring((config.cookieName() + "=").length(), cookie.indexOf(';'))).orElseThrow();
    }
    SessionStore.Session accedi(Account a) throws Exception { return sessione(login(a, VECCHIA)); }
    String corpo(String attuale, String nuova) throws Exception { return mapper.writeValueAsString(Map.of("passwordAttuale", attuale, "nuovaPassword", nuova)); }
    TestHttpExchange put(AutenticazioneController controller, SessionStore.Session s, String body) throws Exception {
        var x = new TestHttpExchange("PUT", "/api/auth/password", body);
        if (s != null) { x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", s.csrfToken()); }
        x.requestHeaders.set("Origin", config.origin()); controller.handle(x); return x;
    }
    TestHttpExchange cambia(SessionStore.Session s, String attuale, String nuova) throws Exception { return put(auth, s, corpo(attuale, nuova)); }
    TestHttpExchange me(SessionStore.Session s) throws Exception {
        var x = new TestHttpExchange("GET", "/api/auth/me", ""); x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); auth.handle(x); return x;
    }
    String errore(TestHttpExchange x) throws Exception { return mapper.readTree(x.body()).get("error").asText(); }
    void cookieScaduto(TestHttpExchange x) {
        String cookie = x.responseHeaders.getFirst("Set-Cookie"); assertNotNull(cookie, "Set-Cookie presente");
        assertTrue(cookie.startsWith(config.cookieName() + "=;") && cookie.contains("Max-Age=0") && cookie.contains("Path=/"), cookie);
        assertNull(x.responseHeaders.getFirst("X-CSRF-Token"));
    }
    void nessunCambioCookie(TestHttpExchange x) { assertNull(x.responseHeaders.getFirst("Set-Cookie")); assertNull(x.responseHeaders.getFirst("X-CSRF-Token")); }
    void nuovoAccessoRichiesto(TestHttpExchange x) throws Exception {
        assertEquals(200, x.status, x.body());
        assertEquals("{\"message\":\"Password aggiornata. Effettuare di nuovo l'accesso.\",\"nuovoAccessoRichiesto\":true}", x.body());
        cookieScaduto(x);
    }

    // A, H, J, L, P
    @Test void a_h_j_l_p_successfulChangePublishesANewSessionAtTheNewGeneration() throws Exception {
        Account a = account(Categoria.PAZIENTE), altro = account(Categoria.PAZIENTE);
        sql("UPDATE utente SET generazione_autenticazione=41 WHERE id_utente=?", a.id());
        var s = accedi(a); var secondoDispositivo = accedi(a); var altrui = accedi(altro);
        clock.advance(Duration.ofMinutes(7));
        var r = cambia(s, VECCHIA, NUOVA);
        assertEquals(200, r.status, r.body());
        assertEquals("{\"message\":\"Password aggiornata.\",\"nuovoAccessoRichiesto\":false}", r.body());
        String cookie = r.responseHeaders.getFirst("Set-Cookie");
        assertFalse(cookie.contains("Max-Age=0")); assertTrue(cookie.contains("HttpOnly"));
        var nuova = store.find(cookie.substring((config.cookieName() + "=").length(), cookie.indexOf(';'))).orElseThrow();
        assertEquals(42, nuova.generazione(), "H: g+1"); assertEquals(nuova.csrfToken(), r.responseHeaders.getFirst("X-CSRF-Token"));
        Riga dopo = riga(a.id());
        assertEquals(BigInteger.valueOf(42), dopo.generazione()); assertEquals(a.id(), dopo.da(), "J: autore = utente stesso"); assertEquals(ora(), dopo.il());
        assertTrue(hasher.verify(NUOVA.toCharArray(), dopo.hash())); assertFalse(r.body().contains(dopo.hash()));
        assertTrue(store.find(s.id()).isEmpty() && store.find(secondoDispositivo.id()).isEmpty(), "vecchie sessioni rimosse da revokeStale");
        assertEquals(401, me(secondoDispositivo).status);
        assertEquals(200, me(nuova).status); assertEquals(200, me(altrui).status, "altri utenti intatti");
        assertEquals(401, login(a, VECCHIA).status); assertEquals(200, login(a, NUOVA).status);
    }

    /* Password corrente errata e politiche distinte per categoria. */
    @Test void b_d_currentPasswordAndCategoryPolicies() throws Exception {
        Account p = account(Categoria.PAZIENTE); var s = accedi(p); Riga prima = riga(p.id());
        var errata = cambia(s, "Password sbagliata", NUOVA);
        assertEquals(403, errata.status); assertEquals("Password attuale non corretta.", errore(errata)); nessunCambioCookie(errata);
        assertEquals(prima, riga(p.id())); assertEquals(200, me(s).status, "la sessione resta valida");
        var uguale = cambia(s, VECCHIA, VECCHIA);
        assertEquals(400, uguale.status); assertEquals("La nuova password deve essere diversa dall'attuale.", errore(uguale));
        /* Password paziente 8–1024 caratteri Java; surrogate isolate rifiutate. */
        for (String nuova : List.of("", "x", "x".repeat(7), "abcde\uD83D\uDE00", "x".repeat(1025))) {
            var r = cambia(s, VECCHIA, nuova); assertEquals(400, r.status, nuova.length() + " char"); assertEquals("Richiesta non valida.", errore(r));
        }
        for (String escape : List.of("abcdefg\\uD800", "\\uDC00abcdefgh")) {
            assertEquals(400, put(auth, s, "{\"passwordAttuale\":\"" + VECCHIA + "\",\"nuovaPassword\":\"" + escape + "\"}").status, "surrogato isolato");
        }
        assertEquals(prima, riga(p.id()));
        assertEquals(200, cambia(s, VECCHIA, "x".repeat(8)).status, "paziente: 8 caratteri ammessi");
        Account p2 = account(Categoria.PAZIENTE); assertEquals(200, cambia(accedi(p2), VECCHIA, "y".repeat(1024)).status);
        Account p3 = account(Categoria.PAZIENTE); assertEquals(200, cambia(accedi(p3), VECCHIA, "abcdef\uD83D\uDE00").status, "coppia surrogata valida");
        for (Categoria rigorosa : List.of(Categoria.MEDICO, Categoria.DESK, Categoria.AMMINISTRATORE, Categoria.PAZIENTE_MEDICO)) {
            Account a = account(rigorosa); var sa = accedi(a); Riga iniziale = riga(a.id());
            for (String nuova : List.of("undici_char", "z".repeat(1025))) {
                assertEquals(400, cambia(sa, VECCHIA, nuova).status, rigorosa + " " + nuova.length());
            }
            /* La surrogate isolata deve arrivare come escape JSON: UTF-8 grezzo la sostituirebbe. */
            assertEquals(400, put(auth, sa, "{\"passwordAttuale\":\"" + VECCHIA + "\",\"nuovaPassword\":\"\\uD800abcdefghijk\"}").status, rigorosa + " surrogato");
            assertEquals(iniziale, riga(a.id()));
            assertEquals(200, cambia(sa, VECCHIA, "dodici_chars").status, rigorosa + ": 12 caratteri ammessi");
        }
    }

    /* Validazione ed envelope HTTP. */
    @Test void c_e_f_g_entryChecksNeverWrite() throws Exception {
        Account a = account(Categoria.DESK); var s = accedi(a); Riga prima = riga(a.id()); String ok = corpo(VECCHIA, NUOVA);
        var x = new TestHttpExchange("PUT", "/api/auth/password", ok);
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", s.csrfToken()); auth.handle(x);
        assertEquals(403, x.status, "Origin assente");
        x = new TestHttpExchange("PUT", "/api/auth/password", ok); x.requestHeaders.set("Origin", "http://invalid.example");
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", s.csrfToken()); auth.handle(x);
        assertEquals(403, x.status, "Origin diverso");
        x = new TestHttpExchange("PUT", "/api/auth/password", ok); x.requestHeaders.set("Origin", config.origin());
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); auth.handle(x); assertEquals(403, x.status, "CSRF assente");
        x = new TestHttpExchange("PUT", "/api/auth/password", ok); x.requestHeaders.set("Origin", config.origin());
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", "x".repeat(43)); auth.handle(x);
        assertEquals(403, x.status, "CSRF errato");
        x = new TestHttpExchange("PUT", "/api/auth/password", ok); x.requestHeaders.set("Origin", config.origin()); x.requestHeaders.set("Content-Type", "text/plain");
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", s.csrfToken()); auth.handle(x);
        assertEquals(415, x.status);
        /* Il limitatore conta prima della lettura del corpo; qui è più ampio per isolare il parsing. */
        auth = new AutenticazioneController(service, store, new LoginThrottle(Clock.systemUTC(), 10_000, 10_000),
                new LoginThrottle(Clock.systemUTC(), 10_000, 100), config, mapper);
        for (String bad : List.of("", "null", "[]", "{}", "{\"passwordAttuale\":\"" + VECCHIA + "\"}", "{\"passwordAttuale\":null,\"nuovaPassword\":\"" + NUOVA + "\"}",
                "{\"passwordAttuale\":1,\"nuovaPassword\":\"" + NUOVA + "\"}", ok.replace("}", ",\"idUtente\":1}"), ok + " {}",
                "{\"passwordAttuale\":\"a\",\"passwordAttuale\":\"b\",\"nuovaPassword\":\"c\"}", ok.replace("}", " ".repeat(8193) + "}"))) {
            var r = put(auth, s, bad); assertEquals(400, r.status, bad.length() > 80 ? "oltre 8192 byte" : bad); assertEquals("Richiesta non valida.", errore(r));
        }
        var query = new TestHttpExchange("PUT", "/api/auth/password?idUtente=1", ok); query.requestHeaders.set("Origin", config.origin());
        query.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); query.requestHeaders.set("X-CSRF-Token", s.csrfToken()); auth.handle(query);
        assertEquals(400, query.status);
        for (String method : List.of("GET", "POST", "DELETE")) {
            var m = new TestHttpExchange(method, "/api/auth/password", ok); m.requestHeaders.set("Origin", config.origin()); auth.handle(m);
            assertEquals(405, m.status, method); assertEquals("PUT", m.responseHeaders.getFirst("Allow"));
        }
        assertEquals(prima, riga(a.id())); assertEquals(200, me(s).status, "nessun controllo d'ingresso invalida la sessione");
        // F: anonymous, stale; G: disabled. 401, expired cookie, token revoked, nothing written.
        var anonimo = put(auth, null, ok); assertEquals(401, anonimo.status); cookieScaduto(anonimo);
        Account b = account(Categoria.AMMINISTRATORE); var vecchia = accedi(b); sql("UPDATE utente SET generazione_autenticazione=1 WHERE id_utente=?", b.id());
        var obsoleta = cambia(vecchia, VECCHIA, NUOVA); assertEquals(401, obsoleta.status); cookieScaduto(obsoleta); assertTrue(store.find(vecchia.id()).isEmpty());
        Account c = account(Categoria.MEDICO); var sc = accedi(c); sql("UPDATE utente SET stato_accesso='DISABILITATO', generazione_autenticazione=1 WHERE id_utente=?", c.id());
        Riga disabilitato = riga(c.id());
        var spento = cambia(sc, VECCHIA, NUOVA); assertEquals(401, spento.status); cookieScaduto(spento);
        assertTrue(store.find(sc.id()).isEmpty()); assertEquals(disabilitato, riga(c.id()));
        /* Anomalia dati all'ingresso: 500 con cookie scaduto e token revocato. */
        Account d = account(Categoria.PAZIENTE); var sd = accedi(d); sql("UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE id_utente=?", d.id());
        var anomalo = cambia(sd, VECCHIA, NUOVA); assertEquals(500, anomalo.status); cookieScaduto(anomalo); assertTrue(store.find(sd.id()).isEmpty());
        assertFalse(anomalo.body().contains("9223372036854775808"));
    }

    @Test void throttleIsPerAccountWithRetryAfter() throws Exception {
        Account a = account(Categoria.PAZIENTE), b = account(Categoria.PAZIENTE); var s = accedi(a); var t = accedi(b);
        for (int i = 0; i < 10; i++) { assertEquals(403, cambia(s, "errata " + i, NUOVA).status, "tentativo " + i); }
        var bloccato = cambia(s, VECCHIA, NUOVA); assertEquals(429, bloccato.status); assertEquals("300", bloccato.responseHeaders.getFirst("Retry-After"));
        assertTrue(hasher.verify(VECCHIA.toCharArray(), riga(a.id()).hash()));
        assertEquals(200, cambia(t, VECCHIA, NUOVA).status, "chiave utente:<id>, gli altri account non sono limitati");
    }

    // I
    @Test void i_generationAtTheMaximumIsAConflictWithoutChanges() throws Exception {
        Account a = account(Categoria.PAZIENTE); sql("UPDATE utente SET generazione_autenticazione=9223372036854775807 WHERE id_utente=?", a.id());
        var s = accedi(a); assertEquals(Long.MAX_VALUE, s.generazione()); Riga prima = riga(a.id());
        var r = cambia(s, VECCHIA, NUOVA);
        assertEquals(409, r.status); assertEquals("Operazione non disponibile per questo account.", errore(r)); nessunCambioCookie(r);
        assertEquals(prima, riga(a.id())); assertEquals(200, me(s).status, "nessuna invalidazione");
    }

    /* Proxy JDBC sulle connessioni reali: intercetta metodi di Connection e PreparedStatement per nome e SQL. */
    interface Gancio { Object call(String metodo, String sql, Callable<Object> reale) throws Exception; }
    ConnectionFactory proxy(Gancio gancio) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = factory.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> {
                    Callable<Object> reale = () -> { try { return m.invoke(c, a); } catch (InvocationTargetException e) { throw (Exception) e.getCause(); } };
                    if (m.getName().equals("prepareStatement")) {
                        String sql = (String) a[0]; PreparedStatement s = (PreparedStatement) reale.call();
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (q, n, b) ->
                                gancio.call(n.getName(), sql, () -> { try { return n.invoke(s, b); } catch (InvocationTargetException e) { throw (Exception) e.getCause(); } }));
                    }
                    return gancio.call(m.getName(), null, reale);
                });
            }
        };
    }
    static boolean aggiornamento(String sql) { return sql != null && sql.startsWith("UPDATE utente SET password_hash"); }

    /*
     * Errore prima del commit: rollback, 500 o 409 secondo il caso, senza cambiare cookie o
     * invalidare altre sessioni.
     */
    @Test void k_n_errorBeforeCommitRollsBackWithoutInvalidations() throws Exception {
        for (String guasto : List.of("eccezione", "zero righe", "due righe")) {
            Account a = account(Categoria.PAZIENTE); var s = accedi(a); Riga prima = riga(a.id());
            wire(new UtenteRepository(proxy((metodo, sql, reale) -> {
                if (metodo.equals("executeUpdate") && aggiornamento(sql)) {
                    if (guasto.equals("eccezione")) { throw new SQLException("guasto iniettato con dettagli riservati"); }
                    return guasto.equals("zero righe") ? 0 : 2;
                }
                return reale.call();
            }), clock));
            var r = cambia(s, VECCHIA, NUOVA);
            assertEquals(500, r.status, guasto); assertEquals("{\"error\":\"Errore interno del server.\"}", r.body()); nessunCambioCookie(r);
            wire(new UtenteRepository(factory, clock));
            assertEquals(prima, riga(a.id()), "nessuna modifica: " + guasto);
            assertTrue(store.find(s.id()).isPresent()); assertEquals(200, me(s).status, "la validità resta decisa da I3");
        }
    }

    /* Commit incerto o pulizia fallita, con transazione realmente confermata oppure no. */
    @Test void l_m_commitOutcomesAreDecidedOnlyByCommit() throws Exception {
        Account a = account(Categoria.PAZIENTE); var s = accedi(a);
        /* Fallisce soltanto close della connessione transazionale, la prima chiusa dopo commit. */
        boolean[] committed = {false}, fallita = {false};
        wire(new UtenteRepository(proxy((metodo, sql, reale) -> {
            if (sql == null && metodo.equals("commit")) { Object r = reale.call(); committed[0] = true; return r; }
            if (sql == null && metodo.equals("close") && committed[0] && !fallita[0]) { reale.call(); fallita[0] = true; throw new SQLException("close fallita"); }
            return reale.call();
        }), clock));
        var confermato = cambia(s, VECCHIA, NUOVA);
        assertEquals(200, confermato.status, "CONFERMATO: l'errore di close non cambia l'esito");
        assertEquals("{\"message\":\"Password aggiornata.\",\"nuovoAccessoRichiesto\":false}", confermato.body());
        assertTrue(fallita[0], "la close fallita è stata davvero iniettata");
        assertTrue(hasher.verify(NUOVA.toCharArray(), riga(a.id()).hash()));

        for (boolean confermatoDalServer : List.of(true, false)) {
            Account b = account(Categoria.PAZIENTE); wire(new UtenteRepository(factory, clock)); var sb = accedi(b); Riga prima = riga(b.id());
            wire(new UtenteRepository(proxy((metodo, sql, reale) -> {
                if (metodo.equals("commit") && sql == null) {
                    if (confermatoDalServer) { reale.call(); } else { throw new SQLException("rete interrotta prima del commit"); }
                    throw new SQLException("risposta al commit persa");
                }
                return reale.call();
            }), clock));
            int prima_ = store.size();
            var incerto = cambia(sb, VECCHIA, NUOVA);
            assertEquals(500, incerto.status, "INCERTO"); assertEquals("{\"error\":\"Errore interno del server.\"}", incerto.body());
            cookieScaduto(incerto); assertEquals(prima_, store.size(), "nessuna nuova sessione");
            wire(new UtenteRepository(factory, clock));
            if (confermatoDalServer) {
                assertEquals(BigInteger.ONE, riga(b.id()).generazione()); assertEquals(401, me(sb).status, "vecchia sessione invalida per I3");
            } else {
                assertEquals(prima, riga(b.id())); assertEquals(200, me(sb).status, "nessuna invalidazione se il commit non è avvenuto");
            }
        }
    }

    /* Gancio dopo commit confermato, prima della pubblicazione della sessione nel controller. */
    static final class DopoCommit extends UtenteRepository {
        volatile Runnable gancio = () -> { }; volatile boolean identitaRotta;
        DopoCommit(ConnectionFactory f, Clock c) { super(f, c); }
        @Override public long aggiornaPassword(int id, long g, boolean r, String h) throws SQLException {
            long nuova = super.aggiornaPassword(id, g, r, h); gancio.run(); return nuova;
        }
        @Override public Optional<StatoSessione> findSessione(Integer id) throws SQLException {
            if (identitaRotta) { throw new SQLException("lettura identità non disponibile"); }
            return super.findSessione(id);
        }
    }

    /* Commit confermato senza sessione pubblicabile. */
    @Test void o_confirmedWithoutPublishableSessionAsksForANewLogin() throws Exception {
        Account a = account(Categoria.PAZIENTE);
        store = new SessionStore(Clock.systemUTC(), 3); var repo = new DopoCommit(factory, clock); wire(repo);
        var s = accedi(a);
        /* Dopo commit lo store si riempie di sessioni più recenti, non rimosse da revokeStale. */
        repo.gancio = () -> { store.revoke(s.id()); while (store.size() < 3) { store.create(a.id(), 1000); } };
        var pieno = cambia(s, VECCHIA, NUOVA); nuovoAccessoRichiesto(pieno);
        assertTrue(hasher.verify(NUOVA.toCharArray(), riga(a.id()).hash()), "password cambiata");
        assertTrue(store.find(s.id()).isEmpty());

        /* Errore d'identità dopo commit: il candidato viene revocato, mai lasciato valido. */
        Account b = account(Categoria.PAZIENTE); store = new SessionStore(Clock.systemUTC(), 100); repo = new DopoCommit(factory, clock); wire(repo);
        var sb = accedi(b); var r = repo;
        repo.gancio = () -> r.identitaRotta = true;
        var errore = cambia(sb, VECCHIA, NUOVA); nuovoAccessoRichiesto(errore);
        assertEquals(0, store.size(), "candidata revocata, vecchia sessione rimossa");
        repo.identitaRotta = false; assertEquals(200, login(b, NUOVA).status);
    }

    /* La scrittura del corpo fallisce dopo gli header; un secondo invio degli header fallisce come nel JDK. */
    static final class ScambioGuasto extends HttpExchange {
        final Headers requestHeaders = new Headers(), responseHeaders = new Headers(); final byte[] body;
        int headerSends, status = -1; boolean closed;
        ScambioGuasto(String body) { this.body = body.getBytes(StandardCharsets.UTF_8); }
        @Override public void sendResponseHeaders(int code, long length) throws IOException { if (++headerSends > 1) { throw new IOException("headers already sent"); } status = code; }
        @Override public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override public void write(int b) { throw new IllegalStateException("guasto in scrittura"); }
                @Override public void write(byte[] b, int off, int len) { throw new IllegalStateException("guasto in scrittura"); }
            };
        }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/api/auth/password"); }
        @Override public String getRequestMethod() { return "PUT"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(body); }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    // Q
    @Test void q_failureAfterHeadersRevokesTheCandidateWithoutASecondResponse() throws Exception {
        Account a = account(Categoria.PAZIENTE); var s = accedi(a);
        var x = new ScambioGuasto(corpo(VECCHIA, NUOVA));
        x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); x.requestHeaders.set("X-CSRF-Token", s.csrfToken());
        x.requestHeaders.set("Origin", config.origin()); x.requestHeaders.set("Content-Type", "application/json");
        var e = assertThrows(IllegalStateException.class, () -> auth.handle(x));
        assertEquals("guasto in scrittura", e.getMessage(), "errore originario non mascherato");
        assertEquals(1, x.headerSends); assertEquals(200, x.status); assertTrue(x.closed);
        assertEquals(0, store.size(), "candidata revocata e vecchia sessione rimossa");
        assertEquals(BigInteger.ONE, riga(a.id()).generazione(), "la password resta cambiata (CONFERMATO)");
    }

    /* Sospende dopo la prima esecuzione di un'istruzione corrispondente. */
    ConnectionFactory pausaDopo(Predicate<String> sql, CountDownLatch arrivato, CountDownLatch via) {
        return proxy((metodo, s, reale) -> {
            Object r = reale.call();
            if (metodo.equals("executeQuery") && s != null && sql.test(s) && arrivato.getCount() > 0) {
                arrivato.countDown(); assertTrue(via.await(30, TimeUnit.SECONDS));
            }
            return r;
        });
    }
    void attendiAttesaUtente(Future<?> inAttesa) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.OBJECT_SCHEMA = DATABASE() AND r.OBJECT_NAME = 'utente'""")) {
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { return; } }
                assertFalse(inAttesa.isDone(), "il secondo cambio non deve concludersi prima del rilascio");
                assertTrue(System.nanoTime() < deadline, "nessuna attesa InnoDB osservata sulla riga utente");
                Thread.sleep(10); /* Attesa tramite polling. */
            }
        }
    }

    /* Due cambi dalla stessa generazione: il secondo attende FOR UPDATE, vede la nuova generazione e ottiene 409. */
    @Test void r_twoConcurrentChangesFromTheSameGeneration() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                Account a = account(Categoria.AMMINISTRATORE); var s1 = accedi(a); var s2 = accedi(a);
                CountDownLatch arrivato = new CountDownLatch(1), via = new CountDownLatch(1);
                var lento = new AutenticazioneController(new AutenticazioneService(new UtenteRepository(
                        pausaDopo(sql -> sql.contains("FOR UPDATE"), arrivato, via), clock), hasher, store), store,
                        new LoginThrottle(Clock.systemUTC(), 100, 100), new LoginThrottle(Clock.systemUTC(), 100, 100), config, mapper);
                Future<TestHttpExchange> primo = pool.submit(() -> put(lento, s1, corpo(VECCHIA, NUOVA + " uno")));
                assertTrue(arrivato.await(30, TimeUnit.SECONDS));
                Future<TestHttpExchange> secondo = pool.submit(() -> cambia(s2, VECCHIA, NUOVA + " due"));
                try { attendiAttesaUtente(secondo); } finally { via.countDown(); }
                assertEquals(200, primo.get(30, TimeUnit.SECONDS).status, "round " + round);
                var r2 = secondo.get(30, TimeUnit.SECONDS);
                assertEquals(409, r2.status, "round " + round); assertEquals("Operazione non disponibile per questo account.", errore(r2)); nessunCambioCookie(r2);
                Riga dopo = riga(a.id());
                assertEquals(BigInteger.ONE, dopo.generazione()); assertTrue(hasher.verify((NUOVA + " uno").toCharArray(), dopo.hash()));
                assertEquals(401, me(s2).status, "la sessione del secondo è superata");
            }
        } finally { pool.shutdownNow(); }
    }

    /*
     * Il primo cambio conferma e attende la pubblicazione; il secondo conferma e pubblica una
     * generazione superiore prima della ripresa del primo.
     */
    @Test void s_t_u_latePublicationNeverRevokesTheNewerSessionNorRevivesAStaleOne() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int round = 0; round < 3; round++) {
                Account a = account(Categoria.PAZIENTE); var s0 = accedi(a);
                CountDownLatch commitP1 = new CountDownLatch(1), riprendi = new CountDownLatch(1);
                var repoP1 = new DopoCommit(factory, clock);
                repoP1.gancio = () -> { commitP1.countDown(); try { assertTrue(riprendi.await(30, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
                var p1 = new AutenticazioneController(new AutenticazioneService(repoP1, hasher, store), store,
                        new LoginThrottle(Clock.systemUTC(), 100, 100), new LoginThrottle(Clock.systemUTC(), 100, 100), config, mapper);
                Future<TestHttpExchange> rispostaP1 = pool.submit(() -> put(p1, s0, corpo(VECCHIA, NUOVA + " P1")));
                assertTrue(commitP1.await(30, TimeUnit.SECONDS));
                assertEquals(BigInteger.ONE, riga(a.id()).generazione(), "P1 confermato a g+1");
                /* Nel frattempo l'utente accede con la password del primo cambio e completa il secondo. */
                var sIntermedia = sessione(login(a, NUOVA + " P1")); assertEquals(1, sIntermedia.generazione());
                var p2 = cambia(sIntermedia, NUOVA + " P1", NUOVA + " P2"); assertEquals(200, p2.status, p2.body());
                String cookie = p2.responseHeaders.getFirst("Set-Cookie");
                var s2 = store.find(cookie.substring((config.cookieName() + "=").length(), cookie.indexOf(';'))).orElseThrow();
                assertEquals(2, s2.generazione());
                riprendi.countDown();
                var r1 = rispostaP1.get(30, TimeUnit.SECONDS);
                nuovoAccessoRichiesto(r1);
                assertTrue(store.find(s2.id()).isPresent(), "T: S2 non revocata dal revokeStale(g+1) di P1");
                assertEquals(200, me(s2).status, "T: S2 ancora valida");
                assertTrue(store.find(s0.id()).isEmpty() && store.find(sIntermedia.id()).isEmpty());
                assertEquals(1, store.size(), "U: S1(g+1) creata da P1 e subito revocata");
                assertTrue(auth_identity_vuota(a.id(), 1), "U: una sessione a g+1 non supera I3");
                store.revoke(s2.id());
            }
        } finally { pool.shutdownNow(); }
    }
    boolean auth_identity_vuota(int id, long generazione) throws Exception { return service.identity(store.create(id, generazione)).isEmpty(); }

    /* Una password storica più corta continua ad autenticare e può essere sostituita. */
    @Test void legacyShortPatientPasswordStillLogsInAndCanBeChangedToACompliantOne() throws Exception {
        Account a = account(Categoria.PAZIENTE);
        sql("UPDATE utente SET password_hash=? WHERE id_utente=?", hasher.hash("abc".toCharArray()), a.id());
        var s = sessione(login(a, "abc"));
        var corta = cambia(s, "abc", "abcdefg"); assertEquals(400, corta.status, "la nuova password deve rispettare 8-1024");
        var r = cambia(s, "abc", "abcdefgh"); assertEquals(200, r.status, r.body());
        assertEquals(401, login(a, "abc").status); assertEquals(200, login(a, "abcdefgh").status);
    }

    /*
     * Password nuova rifiutata per limiti o Unicode: invariati hash, generazione, metadati, stato,
     * sessioni e dimensione dello store.
     */
    @Test void refusedNewPasswordsChangeNeitherTheRowNorTheSessions() throws Exception {
        for (Categoria categoria : List.of(Categoria.PAZIENTE, Categoria.DESK)) {
            Account a = account(categoria); var s = accedi(a); var altroDispositivo = accedi(a);
            sql("UPDATE utente SET modificato_da=NULL, modificato_il='2000-01-01 00:00:00' WHERE id_utente=?", a.id());
            auth = new AutenticazioneController(service, store, new LoginThrottle(Clock.systemUTC(), 10_000, 10_000),
                    new LoginThrottle(Clock.systemUTC(), 10_000, 100), config, mapper);
            Riga prima = riga(a.id()); int sessioni = store.size();
            int minimo = categoria == Categoria.PAZIENTE ? 8 : 12;
            List<String> rifiutate = new ArrayList<>(List.of("\"" + "x".repeat(minimo - 1) + "\"", "\"" + "x".repeat(1025) + "\"",
                    "\"" + "a".repeat(minimo) + "\\uD800\"", "\"\\uDC00" + "a".repeat(minimo) + "\"", "\"" + "a".repeat(minimo) + "\\uDE00\\uD83D\"",
                    "\"" + "a".repeat(minimo) + "\\uD8\"", "\"" + "a".repeat(minimo) + "\\uZZZZ\"", "\"" + "a".repeat(minimo) + "\\u\""));
            for (String nuova : rifiutate) {
                var r = put(auth, s, "{\"passwordAttuale\":\"" + VECCHIA + "\",\"nuovaPassword\":" + nuova + "}");
                String caso = categoria + " " + (nuova.length() > 40 ? "1025 caratteri" : nuova);
                assertEquals(400, r.status, caso); assertEquals("Richiesta non valida.", errore(r)); nessunCambioCookie(r);
                assertEquals(prima, riga(a.id()), "hash, generazione, tracciamento e stato invariati: " + caso);
                assertEquals(sessioni, store.size(), "nessuna sessione creata o rimossa: " + caso);
                assertTrue(store.find(s.id()).isPresent() && store.find(altroDispositivo.id()).isPresent(), caso);
            }
            assertEquals(200, me(s).status); assertEquals(200, me(altroDispositivo).status);
            assertEquals(200, login(a, VECCHIA).status, "la password attuale funziona ancora");
            var limite = cambia(s, VECCHIA, "z".repeat(minimo)); assertEquals(200, limite.status, categoria + ": minimo ammesso");
            var nuova = sessione(limite);
            assertEquals(200, cambia(nuova, "z".repeat(minimo), "w".repeat(1024)).status, categoria + ": 1024 ammessi");
        }
    }
}
