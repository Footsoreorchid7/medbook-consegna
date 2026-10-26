package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.config.*;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Login, generazione, me, logout e cookie su MySQL isolato. Disabilitazioni e cambi password sono
 * simulati nelle fixture SQL aggiornando stato e generazione; non vengono eseguiti tramite i
 * rispettivi endpoint.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessioneGenerazioneIntegrationTest {
    static final String PASSWORD = "password di prova U2";
    final ObjectMapper mapper = new ObjectMapper();
    final AuthConfig config = new AuthConfig("local-http", "http://localhost:8080");
    final List<Integer> users = new ArrayList<>();
    ConnectionFactory factory; String hash;
    SessionStore store; AutenticazioneService service; AutenticazioneController auth; AutorizzazioneHandler protetto;

    @BeforeAll void fixture() {
        factory = TestDatabaseConnections.isolatedFactory();
        hash = new PasswordHasher().hash(PASSWORD.toCharArray());
    }
    @BeforeEach void wiring() { wire(new UtenteRepository(factory)); }
    void wire(UtenteRepository repository) {
        store = new SessionStore(Clock.systemUTC(), 1000);
        service = new AutenticazioneService(repository, new PasswordHasher(), store);
        auth = new AutenticazioneController(service, store, new LoginThrottle(Clock.systemUTC(), 10_000, 10_000), config, mapper);
        HttpHandler ok = exchange -> { byte[] b = "{}".getBytes(); exchange.sendResponseHeaders(200, b.length); exchange.getResponseBody().write(b); };
        protetto = new AutorizzazioneHandler(ok, service, store, config, mapper);
    }
    @AfterAll void cleanup() throws Exception { if (factory != null) { for (int id : users) { sql("DELETE FROM utente WHERE id_utente=?", id); } } }

    String user(String ruolo) throws Exception {
        String email = "u2-" + UUID.randomUUID() + "@example.invalid";
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES('Fixture','U2',?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, email); s.setString(2, hash); s.setString(3, ruolo); assertEquals(1, s.executeUpdate());
            try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); users.add(k.getInt(1)); }
        }
        return email;
    }
    int id(String email) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id_utente FROM utente WHERE email=?")) {
            s.setString(1, email); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
    void sql(String statement, Object... values) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
        }
    }
    /* Effetti di disabilitazione e password simulati nei dati confermati. */
    void disabilita(String email) throws Exception { sql("UPDATE utente SET stato_accesso='DISABILITATO', generazione_autenticazione=generazione_autenticazione+1 WHERE email=?", email); }
    void riabilita(String email) throws Exception { sql("UPDATE utente SET stato_accesso='ATTIVO', generazione_autenticazione=generazione_autenticazione+1 WHERE email=?", email); }
    void nuovaGenerazione(String email) throws Exception { sql("UPDATE utente SET generazione_autenticazione=generazione_autenticazione+1 WHERE email=?", email); }

    TestHttpExchange login(String email, String password, SessionStore.Session previous, boolean csrf) throws Exception {
        var x = new TestHttpExchange("POST", "/api/auth/login", mapper.writeValueAsString(Map.of("email", email, "password", password)));
        x.requestHeaders.set("Origin", config.origin()); x.requestHeaders.set("Content-Type", "application/json");
        if (previous != null) { x.requestHeaders.set("Cookie", config.cookieName() + "=" + previous.id()); if (csrf) { x.requestHeaders.set("X-CSRF-Token", previous.csrfToken()); } }
        auth.handle(x); return x;
    }
    SessionStore.Session session(TestHttpExchange x) {
        assertEquals(200, x.status, x.body());
        String cookie = x.responseHeaders.getFirst("Set-Cookie"); assertNotNull(cookie);
        String value = cookie.substring((config.cookieName() + "=").length(), cookie.indexOf(';'));
        return store.find(value).orElseThrow();
    }
    SessionStore.Session accedi(String email) throws Exception { return session(login(email, PASSWORD, null, false)); }
    TestHttpExchange me(SessionStore.Session s) throws Exception {
        var x = new TestHttpExchange("GET", "/api/auth/me", ""); x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); auth.handle(x); return x;
    }
    TestHttpExchange api(SessionStore.Session s) throws Exception {
        var x = new TestHttpExchange("GET", "/api/utenti", ""); x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); protetto.handle(x); return x;
    }
    TestHttpExchange logout(SessionStore.Session s, String csrf, String body) throws Exception {
        var x = new TestHttpExchange("POST", "/api/auth/logout", body);
        x.requestHeaders.set("Origin", config.origin()); x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id());
        if (csrf != null) { x.requestHeaders.set("X-CSRF-Token", csrf); }
        auth.handle(x); return x;
    }
    void cookieScaduto(TestHttpExchange x) {
        String cookie = x.responseHeaders.getFirst("Set-Cookie");
        assertNotNull(cookie, "Set-Cookie presente"); assertTrue(cookie.startsWith(config.cookieName() + "=;"), cookie);
        assertTrue(cookie.contains("Max-Age=0"), cookie); assertTrue(cookie.contains("Path=/"), cookie);
    }
    String errore(TestHttpExchange x) throws Exception { return mapper.readTree(x.body()).get("error").asText(); }

    @Test void a_i_p_validLoginGivesSessionAtCurrentGenerationUsableEverywhere() throws Exception {
        String email = user("AMMINISTRATORE"); sql("UPDATE utente SET generazione_autenticazione=41 WHERE email=?", email);
        var s = accedi(email);
        assertEquals(41, s.generazione()); assertEquals(200, me(s).status); assertEquals(200, api(s).status);
    }
    @Test void b_c_d_wrongPasswordUnknownAndDisabledAreIdentical401() throws Exception {
        String email = user(null); int before = store.size();
        var errata = login(email, "errata", null, false); var ignoto = login("u2-ignoto-" + UUID.randomUUID() + "@example.invalid", PASSWORD, null, false);
        disabilita(email); var disabilitato = login(email, PASSWORD, null, false);
        for (var x : List.of(errata, ignoto, disabilitato)) {
            assertEquals(401, x.status); assertEquals("Credenziali non valide.", errore(x)); assertNull(x.responseHeaders.getFirst("X-CSRF-Token"));
        }
        assertEquals(errata.body(), disabilitato.body()); assertEquals(before, store.size());
    }
    @Test void e_f_h_anomalousRoleOrGenerationBeyondLongMax() throws Exception {
        for (String anomalia : List.of("UPDATE utente SET ruolo_gestionale='amministratore' WHERE email=?",
                "UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE email=?",
                "UPDATE utente SET generazione_autenticazione=18446744073709551615 WHERE email=?")) {
            String email = user(null); sql(anomalia, email); int before = store.size();
            var errata = login(email, "errata", null, false);
            assertEquals(401, errata.status, anomalia); assertEquals("Credenziali non valide.", errore(errata));
            var corretta = login(email, PASSWORD, null, false);
            assertEquals(500, corretta.status, anomalia); assertEquals("Errore interno del server.", errore(corretta));
            assertFalse(corretta.body().contains("9223372036854775808") || corretta.body().contains("amministratore"));
            assertNull(corretta.responseHeaders.getFirst("Set-Cookie")); assertEquals(before, store.size(), "nessuna sessione");
        }
    }
    @Test void h_generationExactlyLongMaxIsValid() throws Exception {
        String email = user(null); sql("UPDATE utente SET generazione_autenticazione=9223372036854775807 WHERE email=?", email);
        var s = accedi(email); assertEquals(Long.MAX_VALUE, s.generazione()); assertEquals(200, me(s).status);
    }
    @Test void j_staleGenerationIsRevokedOnEveryAuthenticatedPath() throws Exception {
        String email = user("AMMINISTRATORE");
        var s1 = accedi(email); nuovaGenerazione(email);
        var r = api(s1); assertEquals(401, r.status); cookieScaduto(r); assertTrue(store.find(s1.id()).isEmpty());
        var s2 = accedi(email); nuovaGenerazione(email);
        r = me(s2); assertEquals(401, r.status); cookieScaduto(r); assertTrue(store.find(s2.id()).isEmpty());
        var s3 = accedi(email);
        assertEquals(200, api(s3).status, "la nuova sessione nasce alla generazione corrente");
    }
    @Test void k_disabledAccountRevokesSessionAndA1ReEnableDoesNotRevive() throws Exception {
        String email = user("AMMINISTRATORE");
        var s = accedi(email); disabilita(email);
        var r = api(s); assertEquals(401, r.status); assertTrue(store.find(s.id()).isEmpty());
        /* Una sessione non usata durante la disabilitazione resta invalida dopo riabilitazione. */
        riabilita(email); var vecchia = accedi(email); disabilita(email); riabilita(email);
        assertEquals(401, me(vecchia).status); assertTrue(store.find(vecchia.id()).isEmpty());
        assertEquals(200, me(accedi(email)).status, "nuovo login dopo la riabilitazione");
    }
    @Test void k_disabledWithValidRoleIs401ButWithAnomalousRoleIs500AndBothRevoke() throws Exception {
        String valido = user("AMMINISTRATORE"); var a = accedi(valido); var b = accedi(valido); var c = accedi(valido); disabilita(valido);
        var r = api(a); assertEquals(401, r.status); cookieScaduto(r); assertTrue(store.find(a.id()).isEmpty());
        r = me(b); assertEquals(401, r.status); cookieScaduto(r); assertTrue(store.find(b.id()).isEmpty());
        r = logout(c, c.csrfToken(), ""); assertEquals(401, r.status); assertTrue(store.find(c.id()).isEmpty());
        String anomalo = user("AMMINISTRATORE"); a = accedi(anomalo); b = accedi(anomalo); c = accedi(anomalo); disabilita(anomalo);
        sql("UPDATE utente SET ruolo_gestionale='amministratore' WHERE email=?", anomalo);
        r = api(a); assertEquals(500, r.status); assertTrue(store.find(a.id()).isEmpty());
        r = me(b); assertEquals(500, r.status); assertEquals("Errore interno del server.", errore(r)); assertTrue(store.find(b.id()).isEmpty());
        r = logout(c, c.csrfToken(), ""); assertEquals(500, r.status); cookieScaduto(r); assertTrue(store.find(c.id()).isEmpty());
        assertFalse(r.body().contains("amministratore"));
    }
    @Test void anomalyOnProtectedPathIs500AndRevokes() throws Exception {
        String email = user("AMMINISTRATORE"); var s = accedi(email);
        sql("UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE email=?", email);
        var r = api(s); assertEquals(500, r.status); assertTrue(store.find(s.id()).isEmpty());
    }
    @Test void l_logoutWithValidSessionRevokesOnlyThatToken() throws Exception {
        String email = user(null); var s = accedi(email); var altra = accedi(email);
        var r = logout(s, s.csrfToken(), ""); assertEquals(200, r.status); cookieScaduto(r);
        assertTrue(store.find(s.id()).isEmpty()); assertTrue(store.find(altra.id()).isPresent(), "le altre sessioni restano");
    }
    @Test void m_logoutWithStaleDisabledOrDeletedSessionIsIdentical401AndRevokes() throws Exception {
        List<TestHttpExchange> risposte = new ArrayList<>();
        String e1 = user(null); var s1 = accedi(e1); nuovaGenerazione(e1); risposte.add(logout(s1, s1.csrfToken(), "")); assertTrue(store.find(s1.id()).isEmpty());
        String e2 = user(null); var s2 = accedi(e2); disabilita(e2); risposte.add(logout(s2, s2.csrfToken(), "")); assertTrue(store.find(s2.id()).isEmpty());
        String e3 = user(null); var s3 = accedi(e3); sql("DELETE FROM utente WHERE email=?", e3); risposte.add(logout(s3, s3.csrfToken(), "")); assertTrue(store.find(s3.id()).isEmpty());
        for (var r : risposte) { assertEquals(401, r.status); cookieScaduto(r); assertEquals("Autenticazione richiesta.", errore(r)); }
        assertEquals(risposte.get(0).body(), risposte.get(1).body()); assertEquals(risposte.get(1).body(), risposte.get(2).body());
    }
    @Test void m_logoutWithoutCsrfOrWithBodyDoesNotRevokeAndAnomalyIs500() throws Exception {
        String email = user(null); var s = accedi(email);
        assertEquals(403, logout(s, null, "").status); assertTrue(store.find(s.id()).isPresent());
        assertEquals(400, logout(s, s.csrfToken(), "{}").status); assertTrue(store.find(s.id()).isPresent());
        sql("UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE email=?", email);
        var r = logout(s, s.csrfToken(), ""); assertEquals(500, r.status); cookieScaduto(r); assertTrue(store.find(s.id()).isEmpty());
    }
    @Test void n_validPreviousCookieRequiresCsrf() throws Exception {
        String email = user(null); var previous = accedi(email);
        assertEquals(403, login(email, PASSWORD, previous, false).status); assertTrue(store.find(previous.id()).isPresent());
        assertEquals(401, login(email, "errata", previous, true).status);
        assertTrue(store.find(previous.id()).isPresent(), "login fallito: token precedente non revocato");
        var nuova = session(login(email, PASSWORD, previous, true));
        assertTrue(store.find(previous.id()).isEmpty()); assertNotEquals(previous.id(), nuova.id());
    }
    @Test void o_stalePreviousCookieIsRevokedAndLoginProceedsAsAnonymous() throws Exception {
        String email = user(null), altro = user(null);
        var stale = accedi(email); nuovaGenerazione(email);
        var nuova = session(login(email, PASSWORD, stale, false)); assertTrue(store.find(stale.id()).isEmpty()); assertNotNull(nuova);
        var disabilitata = accedi(altro); disabilita(altro);
        session(login(email, PASSWORD, disabilitata, false)); assertTrue(store.find(disabilitata.id()).isEmpty());
        String anomalo = user(null); var anomala = accedi(anomalo);
        sql("UPDATE utente SET ruolo_gestionale='operatore_desk' WHERE email=?", anomalo);
        session(login(email, PASSWORD, anomala, false)); assertTrue(store.find(anomala.id()).isEmpty());
    }

    /* Sospende il login dopo la lettura delle credenziali e prima della creazione della sessione. */
    static final class PausaLettura extends UtenteRepository {
        final CountDownLatch letto = new CountDownLatch(1), rilascio = new CountDownLatch(1);
        PausaLettura(ConnectionFactory f) { super(f); }
        @Override public Optional<CredenzialiLogin> leggiCredenzialiLogin(String email) throws SQLException {
            var result = super.leggiCredenzialiLogin(email); letto.countDown();
            try { if (!rilascio.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("timeout"); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return result;
        }
    }
    @Test void r_loginConcurrentWithDisableNeverPublishesUsableSession() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (boolean disableDuranteLogin : List.of(true, false)) {
                String email = user(null); var pausa = new PausaLettura(factory); wire(pausa); int before = store.size();
                try (var pool = Executors.newSingleThreadExecutor()) {
                    Future<TestHttpExchange> f;
                    try {
                        f = pool.submit(() -> login(email, PASSWORD, null, false));
                        assertTrue(pausa.letto.await(10, TimeUnit.SECONDS));
                        if (disableDuranteLogin) { disabilita(email); }
                    } finally { pausa.rilascio.countDown(); }
                    var r = f.get(30, TimeUnit.SECONDS);
                    if (disableDuranteLogin) {
                        /*
                         * La lettura vede ATTIVO alla generazione iniziale; una disabilitazione
                         * confermata rende obsoleta la nuova sessione.
                         */
                        assertEquals(401, r.status); assertEquals(before, store.size(), "nessuna sessione residua");
                    } else {
                        var s = session(r); disabilita(email);
                        assertEquals(401, me(s).status); assertTrue(store.find(s.id()).isEmpty());
                    }
                }
            }
        }
    }
}
