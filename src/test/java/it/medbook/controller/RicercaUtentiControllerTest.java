package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* Decodifica query, filtri, errori e autorizzazione HTTP senza database. */
class RicercaUtentiControllerTest {
    static final String CF = codiceFiscale("ZZWAAA");

    /* Registra i filtri validati senza JDBC. */
    static final class Repo extends UtenteRepository {
        int calls; Integer attore; FiltriRicerca filtri; Exception failure;
        RicercaUtentiResponse risposta = new RicercaUtentiResponse(List.of(new UtenteAmministrativoResponse(
                9, "Nome", "Cognome", null, "e@example.invalid", null, "ATTIVO")), null);
        Repo() { super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())); }
        @Override public RicercaUtentiResponse cerca(int attore, FiltriRicerca filtri) throws SQLException {
            calls++; this.attore = attore; this.filtri = filtri;
            if (failure instanceof SQLException e) { throw e; }
            if (failure instanceof RuntimeException e) { throw e; }
            return risposta;
        }
    }
    final Repo repo = new Repo();
    final ObjectMapper mapper = new ObjectMapper();
    final UtenteController controller = new UtenteController(new UtenteService(repo, new PasswordHasher()), mapper);

    TestHttpExchange get(String query) throws Exception { return call("GET", query); }
    TestHttpExchange call(String method, String query) throws Exception {
        var e = new AuthenticatedTestExchange(method, "/api/utenti/ricerca" + (query == null ? "" : "?" + query), "", 7);
        controller.handle(e); assertTrue(e.exchange.closed); return e.exchange;
    }
    static String codiceFiscale(String sei) {
        String first = sei + "90A01A000";
        int[] odd = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23}; int sum = 0;
        for (int i = 0; i < 15; i++) { char c = first.charAt(i); int v = Character.isDigit(c) ? c - '0' : c - 'A'; sum += i % 2 == 0 ? odd[v] : v; }
        return first + (char) ('A' + sum % 26);
    }

    @Test void eachFilterIsValidatedAndNormalizedWithDefaults() throws Exception {
        assertEquals(200, get("idUtente=2147483647").status);
        assertEquals(new UtenteRepository.FiltriRicerca(Integer.MAX_VALUE, null, null, null, null, null, 0, 20), repo.filtri);
        assertEquals(7, repo.attore, "attore dal Principal, mai dalla query");
        get("email=%20Mario.Rossi%40Example%20"); assertEquals("mario.rossi@example", repo.filtri.email());
        get("cognome=+De+Luca+"); assertEquals("De Luca", repo.filtri.cognome());
        get("codiceFiscale=" + CF.toLowerCase(Locale.ROOT)); assertEquals(CF, repo.filtri.codiceFiscale());
        for (String r : List.of("AMMINISTRATORE", "OPERATORE_DESK", "NESSUNO")) { get("ruolo=" + r); assertEquals(r, repo.filtri.ruolo()); }
        for (String s : List.of("ATTIVO", "DISABILITATO")) { get("statoAccesso=" + s); assertEquals(s, repo.filtri.statoAccesso()); }
        get("cognome=Ro&ruolo=NESSUNO&statoAccesso=DISABILITATO&dopoId=0&limite=50");
        assertEquals(new UtenteRepository.FiltriRicerca(null, null, "Ro", null, "NESSUNO", "DISABILITATO", 0, 50), repo.filtri);
        get("email=abc&dopoId=2147483647&limite=1");
        assertEquals(Integer.MAX_VALUE, repo.filtri.dopoId()); assertEquals(1, repo.filtri.limite());
        /* I caratteri speciali arrivano invariati al repository; l'escape per LIKE avviene lì. */
        get("cognome=%25_%21"); assertEquals("%_!", repo.filtri.cognome());
        get("email=%F0%9F%98%80ab"); assertEquals("😀ab", repo.filtri.email(), "3 code point");
    }

    @ParameterizedTest @ValueSource(strings = {
            // Nessun filtro effettivo.
            "", "dopoId=0", "limite=10", "dopoId=5&limite=5",
            // D: empty, malformed, out of range
            "email=", "=x", "email", "email=ab", "email=%20%20ab%20", "cognome=a", "cognome=a%09b", "email=ab%0Ac",
            "idUtente=0", "idUtente=-1", "idUtente=01", "idUtente=%2B1", "idUtente=1.0", "idUtente=2147483648", "idUtente=abc",
            "dopoId=-1&email=abc", "dopoId=01&email=abc", "dopoId=2147483648&email=abc",
            "limite=0&email=abc", "limite=51&email=abc", "limite=050&email=abc", "limite=100&email=abc",
            "ruolo=amministratore", "ruolo=MEDICO", "ruolo=PAZIENTE", "ruolo=NESSUNO%20", "ruolo=",
            "statoAccesso=attivo", "statoAccesso=SOSPESO", "statoAccesso=ATTIVO%20",
            "codiceFiscale=ABC", "codiceFiscale=ZZWAAA90A01A000A",
            "email=%C3%28ab", "email=%ED%A0%80ab",
            /* Parametri sconosciuti e duplicati. */
            "email=abc&email=abd", "email=abc&%65mail=abd", "nome=Mario", "email=abc&extra=1", "Email=abc", "email=abc&",
            "&email=abc", "email=abc&&cognome=Ro", "telefono=123", "idPaziente=1"})
    void invalidQueriesAre400WithoutRepositoryAccess(String query) throws Exception {
        var e = get(query);
        assertEquals(400, e.status, query); assertEquals("{\"error\":\"Richiesta non valida.\"}", e.body());
        assertEquals(0, repo.calls, query);
    }

    /* Gli escape malformati sono rifiutati sia dal parsing URI sia dal decoder difensivo. */
    @Test void decoderRejectsMalformedEscapesAndKeepsOrder() {
        for (String raw : List.of("email=%ZZabc", "email=abc%", "email=abc%4", "email=%G1", "email=%C3%28")) {
            assertThrows(IllegalArgumentException.class, () -> UtenteController.parametri(raw), raw);
        }
        assertEquals(List.of("cognome", "email"), List.copyOf(UtenteController.parametri("cognome=Ro&email=a%2Bb+c").keySet()));
        assertEquals("a+b c", UtenteController.parametri("email=a%2Bb+c").get("email"));
    }

    @Test void noQueryStringAtAllIs400() throws Exception {
        assertEquals(400, get(null).status); assertEquals(0, repo.calls);
    }

    @Test void onlyGetIsAllowedAndBaseListStillRefusesQueries() throws Exception {
        for (String method : List.of("POST", "PUT", "DELETE", "HEAD")) {
            var e = call(method, "email=abc"); assertEquals(405, e.status, method); assertEquals("GET", e.responseHeaders.getFirst("Allow"));
        }
        var base = new AuthenticatedTestExchange("GET", "/api/utenti?email=abc", "", 7); controller.handle(base);
        assertEquals(400, base.exchange.status); assertEquals("{\"error\":\"Query string non consentita.\"}", base.exchange.body());
        for (String path : List.of("/api/utenti/ricerca/", "/api/utenti/ricerca/x", "/api/utenti/Ricerca")) {
            var e = new AuthenticatedTestExchange("GET", path + "?email=abc", "", 7); controller.handle(e);
            assertEquals(404, e.exchange.status, path);
        }
        assertEquals(0, repo.calls);
    }

    @Test void responseHasExactlyTheContractFields() throws Exception {
        repo.risposta = new RicercaUtentiResponse(List.of(new UtenteAmministrativoResponse(9, "Nome", "Cognome", CF,
                "e@example.invalid", RuoloGestionale.OPERATORE_DESK, "DISABILITATO")), 9);
        var json = mapper.readTree(get("email=abc").body());
        assertEquals(Set.of("risultati", "prossimoDopoId"), names(json)); assertEquals(9, json.get("prossimoDopoId").intValue());
        var item = json.get("risultati").get(0);
        assertEquals(Set.of("idUtente", "nome", "cognome", "codiceFiscale", "email", "ruoloGestionale", "statoAccesso"), names(item));
        assertEquals("OPERATORE_DESK", item.get("ruoloGestionale").asText()); assertEquals("DISABILITATO", item.get("statoAccesso").asText());
        repo.risposta = new RicercaUtentiResponse(List.of(), null);
        assertEquals("{\"risultati\":[],\"prossimoDopoId\":null}", get("email=abc").body());
    }
    static Set<String> names(com.fasterxml.jackson.databind.JsonNode node) { Set<String> s = new HashSet<>(); node.fieldNames().forEachRemaining(s::add); return s; }

    @Test void errorsAreGenericAndNeverPartial() throws Exception {
        repo.failure = new it.medbook.exception.AnomaliaDatiAccountException();
        var e = get("email=abc"); assertEquals(500, e.status); assertEquals("{\"error\":\"Errore interno del server.\"}", e.body());
        repo.failure = new SQLException("secret driver detail");
        e = get("email=abc"); assertEquals(500, e.status); assertFalse(e.body().contains("secret"));
        repo.failure = new it.medbook.exception.OperazioneNonAutorizzataException("dettaglio");
        e = get("email=abc"); assertEquals(403, e.status); assertEquals("{\"error\":\"Operazione non autorizzata.\"}", e.body());
        repo.failure = new IllegalStateException("secret");
        e = get("email=abc"); assertEquals(500, e.status); assertFalse(e.body().contains("secret"));
    }

    /* Autorizzazione reale con identità simulata. */
    @Test void onlyAnActiveAdministratorWithCurrentSessionReachesTheSearch() throws Exception {
        var users = new FakeUtenteRepository(); var clock = new MutableClock(); var sessions = new SessionStore(clock, 10);
        var auth = new AutenticazioneService(users, new PasswordHasher(), sessions); var config = new AuthConfig("local-http", "http://localhost:8080");
        var handler = new AutorizzazioneHandler(controller, auth, sessions, config, mapper);
        var anonimo = new TestHttpExchange("GET", "/api/utenti/ricerca?email=abc", ""); handler.handle(anonimo);
        assertEquals(401, anonimo.status);
        record Caso(Boolean paziente, Boolean medico, RuoloGestionale ruolo, int atteso) { }
        for (var caso : List.of(new Caso(false, false, RuoloGestionale.AMMINISTRATORE, 200), new Caso(false, false, RuoloGestionale.OPERATORE_DESK, 403),
                new Caso(false, true, null, 403), new Caso(true, false, null, 403), new Caso(true, true, null, 403))) {
            users.identity = new UtenteAutenticatoResponse(7, "N", "C", caso.paziente(), caso.medico(), caso.ruolo());
            var s = sessions.create(7, 0L);
            var e = new TestHttpExchange("GET", "/api/utenti/ricerca?email=abc&idUtente=7", "");
            e.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); handler.handle(e);
            assertEquals(caso.atteso(), e.status, caso.toString());
        }
        assertEquals(1, repo.calls, "solo l'amministratore raggiunge il repository");
        users.identity = new UtenteAutenticatoResponse(7, "N", "C", false, false, RuoloGestionale.AMMINISTRATORE);
        // Origin errata su GET viene rifiutata se presente.
        var s = sessions.create(7, 0L); var origin = new TestHttpExchange("GET", "/api/utenti/ricerca?email=abc", "");
        origin.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); origin.requestHeaders.set("Origin", "http://invalid.example");
        handler.handle(origin); assertEquals(403, origin.status);
        /* Generazione obsoleta e account disabilitato: 401 e revoca del token. */
        var stale = sessions.create(7, 0L); users.generazione = OptionalLong.of(1);
        var e = new TestHttpExchange("GET", "/api/utenti/ricerca?email=abc", ""); e.requestHeaders.set("Cookie", config.cookieName() + "=" + stale.id());
        handler.handle(e); assertEquals(401, e.status); assertTrue(sessions.find(stale.id()).isEmpty());
        var disabled = sessions.create(7, 1L); users.statoAccesso = UtenteRepository.DISABILITATO;
        e = new TestHttpExchange("GET", "/api/utenti/ricerca?email=abc", ""); e.requestHeaders.set("Cookie", config.cookieName() + "=" + disabled.id());
        handler.handle(e); assertEquals(401, e.status); assertTrue(sessions.find(disabled.id()).isEmpty());
        /* Metodo non previsto: 405 prima dell'autenticazione. */
        var post = new TestHttpExchange("POST", "/api/utenti/ricerca", ""); handler.handle(post);
        assertEquals(405, post.status); assertEquals("GET", post.responseHeaders.getFirst("Allow"));
        assertEquals(1, repo.calls);
    }
}
