package it.medbook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.repository.UtenteRepository.FiltriRicerca;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Ricerca HTTP su MySQL isolato: filtri, paginazione per chiave, minimizzazione e anomalie. Un
 * prefisso cognome casuale delimita le fixture pur cercando nell'intera tabella.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RicercaUtentiIntegrationTest {
    static final Set<String> CAMPI = Set.of("idUtente", "nome", "cognome", "codiceFiscale", "email", "ruoloGestionale", "statoAccesso");
    final ObjectMapper mapper = new ObjectMapper();
    final AuthConfig config = new AuthConfig("local-http", "http://localhost:8080");
    final List<Integer> users = new ArrayList<>();
    /* Sei fixture condividono il tag; le altre usano un prefisso diverso, così i conteggi restano esatti. */
    final String tag = "Uq" + randomLetters(8), extra = "Ur" + randomLetters(8);
    int sequence = new Random().nextInt(17_000);
    ConnectionFactory factory; UtenteRepository repository; SessionStore store; AutorizzazioneHandler handler;
    int admin, desk, medico, paziente, pazienteMedico, disabilitato;
    String cfPaziente, cfPazienteMedico, cfDisabilitato;

    static String randomLetters(int n) {
        var r = new Random(); var b = new StringBuilder(); for (int i = 0; i < n; i++) { b.append((char) ('a' + r.nextInt(26))); } return b.toString();
    }
    String cf() {
        int n = sequence++;
        String first = "ZZW" + (char) ('A' + n / 676 % 26) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26) + "90A01A000";
        return RicercaUtentiControllerTest.codiceFiscale(first.substring(0, 6));
    }
    int utente(String cognome, String ruolo) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES('Fixture',?,?,'hash-sintetico-u4',?)",
                Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, cognome); s.setString(2, "u4-" + cognome.substring(0, 10).toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "@example.invalid"); s.setString(3, ruolo);
            assertEquals(1, s.executeUpdate());
            try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); int id = k.getInt(1); users.add(id); return id; }
        }
    }
    void sql(String statement, Object... values) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
        }
    }
    String pazienteDi(int id) throws Exception {
        String cf = cf(); sql("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita,telefono) VALUES(?,?,?,'3330001111')", id, cf, LocalDate.of(1990, 1, 1)); return cf;
    }
    void medicoDi(int id) throws Exception {
        sql("INSERT INTO medico(id_utente,specializzazione,numero_iscrizione_ordine,stato) VALUES(?,'SpecialitaRiservataU4',?,'ATTIVO')", id, "U4-" + UUID.randomUUID());
    }
    String email(int id) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT email FROM utente WHERE id_utente=?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }

    @BeforeAll void fixture() throws Exception {
        factory = TestDatabaseConnections.isolatedFactory();
        repository = new UtenteRepository(factory);
        admin = utente(tag + "Admin", "AMMINISTRATORE");
        desk = utente(tag + "Desk", "OPERATORE_DESK");
        medico = utente(tag + "Medico", null); medicoDi(medico);
        paziente = utente(tag + "Paziente", null); cfPaziente = pazienteDi(paziente);
        pazienteMedico = utente(tag + "PazMed", null); cfPazienteMedico = pazienteDi(pazienteMedico); medicoDi(pazienteMedico);
        disabilitato = utente(tag + "Spento", null); cfDisabilitato = pazienteDi(disabilitato);
        sql("UPDATE utente SET stato_accesso='DISABILITATO', generazione_autenticazione=1 WHERE id_utente=?", disabilitato);
        store = new SessionStore(Clock.systemUTC(), 1000);
        var auth = new AutenticazioneService(repository, new PasswordHasher(), store);
        handler = new AutorizzazioneHandler(new UtenteController(new UtenteService(repository, new PasswordHasher()), mapper), auth, store, config, mapper);
    }
    @AfterAll void cleanup() throws Exception { if (factory != null) { for (int id : users) { sql("DELETE FROM utente WHERE id_utente=?", id); } } }

    FiltriRicerca filtri(Integer id, String email, String cognome, String cf, String ruolo, String stato) {
        return new FiltriRicerca(id, email, cognome, cf, ruolo, stato, 0, 50);
    }
    List<Integer> ids(RicercaUtentiResponse r) { return r.risultati().stream().map(UtenteAmministrativoResponse::idUtente).toList(); }
    /* Visita tutte le pagine verificando gli invarianti del cursore. */
    List<UtenteAmministrativoResponse> tutte(FiltriRicerca f) throws Exception {
        List<UtenteAmministrativoResponse> all = new ArrayList<>(); int dopo = f.dopoId();
        while (true) {
            var page = repository.cerca(admin, new FiltriRicerca(f.idUtente(), f.email(), f.cognome(), f.codiceFiscale(), f.ruolo(), f.statoAccesso(), dopo, f.limite()));
            assertTrue(page.risultati().size() <= f.limite());
            all.addAll(page.risultati());
            if (page.prossimoDopoId() == null) { return all; }
            assertEquals(f.limite(), page.risultati().size(), "solo una pagina piena ha un seguito");
            assertEquals(page.risultati().getLast().idUtente(), page.prossimoDopoId());
            dopo = page.prossimoDopoId();
        }
    }

    // A, I, J
    @Test void a_i_j_eachFilterAndEveryAccountCategory() throws Exception {
        var perCognome = repository.cerca(admin, filtri(null, null, tag, null, null, null));
        assertEquals(List.of(admin, desk, medico, paziente, pazienteMedico, disabilitato), ids(perCognome), "ordinati per id");
        assertNull(perCognome.prossimoDopoId());
        Map<Integer, UtenteAmministrativoResponse> byId = new HashMap<>();
        perCognome.risultati().forEach(r -> byId.put(r.idUtente(), r));
        assertEquals(RuoloGestionale.AMMINISTRATORE, byId.get(admin).ruoloGestionale()); assertNull(byId.get(admin).codiceFiscale());
        assertEquals(RuoloGestionale.OPERATORE_DESK, byId.get(desk).ruoloGestionale());
        assertNull(byId.get(medico).ruoloGestionale()); assertNull(byId.get(medico).codiceFiscale());
        assertEquals(cfPaziente, byId.get(paziente).codiceFiscale()); assertEquals(cfPazienteMedico, byId.get(pazienteMedico).codiceFiscale());
        assertEquals("DISABILITATO", byId.get(disabilitato).statoAccesso()); assertEquals(cfDisabilitato, byId.get(disabilitato).codiceFiscale());
        assertEquals("ATTIVO", byId.get(paziente).statoAccesso());

        assertEquals(List.of(medico), ids(repository.cerca(admin, filtri(medico, null, null, null, null, null))));
        String emailMedico = email(medico);
        assertEquals(List.of(medico), ids(repository.cerca(admin, filtri(null, emailMedico.substring(0, 20), null, null, null, null))));
        assertEquals(List.of(paziente), ids(repository.cerca(admin, filtri(null, null, null, cfPaziente, null, null))));
        assertEquals(List.of(disabilitato), ids(repository.cerca(admin, filtri(null, null, null, cfDisabilitato, null, null))), "J: disabilitato trovato");
        /* La ricerca per prefisso segue la collation ai_ci senza distinzione maiuscole/minuscole. */
        assertEquals(6, repository.cerca(admin, filtri(null, null, tag.toUpperCase(Locale.ROOT), null, null, null)).risultati().size());
        assertEquals(6, repository.cerca(admin, filtri(null, "u4-" + tag.toLowerCase(Locale.ROOT), null, null, null, null)).risultati().size());
        /* I filtri singoli coprono la tabella: ogni pagina resta coerente e contiene le fixture. */
        var amministratori = tutte(filtri(null, null, null, null, "AMMINISTRATORE", null));
        assertTrue(amministratori.stream().allMatch(r -> r.ruoloGestionale() == RuoloGestionale.AMMINISTRATORE));
        assertTrue(amministratori.stream().anyMatch(r -> r.idUtente() == admin));
        var desks = tutte(filtri(null, null, null, null, "OPERATORE_DESK", null));
        assertTrue(desks.stream().allMatch(r -> r.ruoloGestionale() == RuoloGestionale.OPERATORE_DESK) && desks.stream().anyMatch(r -> r.idUtente() == desk));
        var nessuno = tutte(filtri(null, null, null, null, "NESSUNO", null));
        assertTrue(nessuno.stream().allMatch(r -> r.ruoloGestionale() == null) && nessuno.stream().anyMatch(r -> r.idUtente() == paziente));
        var disabilitati = tutte(filtri(null, null, null, null, null, "DISABILITATO"));
        assertTrue(disabilitati.stream().allMatch(r -> r.statoAccesso().equals("DISABILITATO")) && disabilitati.stream().anyMatch(r -> r.idUtente() == disabilitato));
        var attivi = tutte(filtri(null, null, null, null, null, "ATTIVO"));
        assertTrue(attivi.stream().noneMatch(r -> r.idUtente() == disabilitato) && attivi.stream().anyMatch(r -> r.idUtente() == admin));
    }

    // B, H
    @Test void b_h_filtersCombineInAndAndEmptyResults() throws Exception {
        assertEquals(List.of(disabilitato), ids(repository.cerca(admin, filtri(null, null, tag, null, "NESSUNO", "DISABILITATO"))));
        assertEquals(List.of(medico, paziente, pazienteMedico), ids(repository.cerca(admin, filtri(null, null, tag, null, "NESSUNO", "ATTIVO"))));
        assertEquals(List.of(admin), ids(repository.cerca(admin, filtri(admin, null, tag, null, "AMMINISTRATORE", "ATTIVO"))));
        assertEquals(List.of(), ids(repository.cerca(admin, filtri(admin, null, tag, null, "OPERATORE_DESK", null))), "AND, non OR");
        assertEquals(List.of(), ids(repository.cerca(admin, filtri(paziente, null, null, cfPazienteMedico, null, null))));
        var vuoto = repository.cerca(admin, filtri(null, null, tag + "Inesistente", null, null, null));
        assertEquals(List.of(), vuoto.risultati()); assertNull(vuoto.prossimoDopoId());
        assertEquals(List.of(), ids(repository.cerca(admin, filtri(Integer.MAX_VALUE, null, null, null, null, null))));
        /* Un cursore oltre i risultati produce l'ultima pagina vuota. */
        assertEquals(List.of(), ids(repository.cerca(admin, new FiltriRicerca(null, null, tag, null, null, null, disabilitato, 50))));
    }

    @Test void prefixMetacharactersAreLiteral() throws Exception {
        String base = extra + "Lit";
        int letterale = utente(base + "a_b%c!d", null), simile = utente(base + "aXbYc!d", null), altro = utente(base + "a!b", null);
        assertEquals(List.of(letterale), ids(repository.cerca(admin, filtri(null, null, base + "a_b%", null, null, null))));
        assertEquals(List.of(letterale), ids(repository.cerca(admin, filtri(null, null, base + "a_b%c!", null, null, null))));
        assertEquals(List.of(altro), ids(repository.cerca(admin, filtri(null, null, base + "a!", null, null, null))));
        assertEquals(List.of(), ids(repository.cerca(admin, filtri(null, null, base + "%", null, null, null))));
        assertEquals(List.of(letterale, simile, altro), ids(repository.cerca(admin, filtri(null, null, base, null, null, null))));
    }

    // F, G
    @Test void f_g_keysetPaginationOverMoreThanFiftyAccounts() throws Exception {
        String base = extra + "Pag"; List<Integer> attesi = new ArrayList<>();
        for (int i = 0; i < 55; i++) { attesi.add(utente(base + i, null)); }
        var prima = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, 0, 50));
        assertEquals(attesi.subList(0, 50), ids(prima)); assertEquals(attesi.get(49), prima.prossimoDopoId());
        var seconda = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, prima.prossimoDopoId(), 50));
        assertEquals(attesi.subList(50, 55), ids(seconda)); assertNull(seconda.prossimoDopoId());
        /* Dimensione predefinita 20: pagine 20, 20, 15 senza lacune o duplicati. */
        List<Integer> visti = new ArrayList<>(); int dopo = 0; int pagine = 0;
        while (true) {
            var page = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, dopo, 20)); pagine++;
            visti.addAll(ids(page)); if (page.prossimoDopoId() == null) { break; } dopo = page.prossimoDopoId();
        }
        assertEquals(3, pagine); assertEquals(attesi, visti); assertEquals(visti.size(), new HashSet<>(visti).size());
        // Esattamente limite corrispondenze: nessuna pagina successiva inesistente.
        var esatta = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, attesi.get(4), 50));
        assertEquals(50, esatta.risultati().size()); assertNull(esatta.prossimoDopoId());
        /* Eliminare una riga fra pagine non sposta il cursore: paginazione per chiave, non OFFSET. */
        var p1 = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, 0, 10));
        sql("DELETE FROM utente WHERE id_utente=?", attesi.get(0));
        var p2 = repository.cerca(admin, new FiltriRicerca(null, null, base, null, null, null, p1.prossimoDopoId(), 10));
        assertEquals(attesi.subList(10, 20), ids(p2));
    }

    // P
    @Test void p_anomaliesAndActorReverification() throws Exception {
        int anomalo = utente(extra + "Zanomalo", "AMMINISTRATORE");
        try {
            sql("UPDATE utente SET ruolo_gestionale='amministratore' WHERE id_utente=?", anomalo);
            var e = assertThrows(AnomaliaDatiAccountException.class, () -> repository.cerca(admin, filtri(null, null, extra + "Zanomalo", null, null, null)));
            assertEquals("Dati account non coerenti.", e.getMessage());
            /* Il filtro binario del ruolo non coincide con valori non canonici. */
            assertEquals(List.of(), ids(repository.cerca(admin, filtri(null, null, extra + "Zanomalo", null, "AMMINISTRATORE", null))));
        } finally { sql("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", anomalo); }
        /* L'attore è riletto sulla stessa connessione: esattamente AMMINISTRATORE e ATTIVO. */
        for (int attore : List.of(desk, medico, paziente, Integer.MAX_VALUE)) {
            assertThrows(OperazioneNonAutorizzataException.class, () -> repository.cerca(attore, filtri(null, null, tag, null, null, null)));
        }
        int spento = utente(extra + "Zspento", "AMMINISTRATORE"); sql("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?", spento);
        assertThrows(OperazioneNonAutorizzataException.class, () -> repository.cerca(spento, filtri(null, null, tag, null, null, null)));
        int minuscolo = utente(extra + "Zminuscolo", "AMMINISTRATORE");
        try {
            sql("UPDATE utente SET ruolo_gestionale='amministratore' WHERE id_utente=?", minuscolo);
            assertThrows(OperazioneNonAutorizzataException.class, () -> repository.cerca(minuscolo, filtri(admin, null, null, null, null, null)));
        } finally { sql("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", minuscolo); }
    }

    TestHttpExchange get(String path, SessionStore.Session s) throws Exception {
        var x = new TestHttpExchange("GET", path, "");
        if (s != null) { x.requestHeaders.set("Cookie", config.cookieName() + "=" + s.id()); }
        handler.handle(x); return x;
    }
    String q(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    /* Percorso attraverso autorizzazione e controller reali. */
    @Test void k_l_m_n_o_httpBoundaryMinimizationAndRegression() throws Exception {
        var sessione = store.create(admin, 0);
        var ok = get("/api/utenti/ricerca?cognome=" + q(tag) + "&limite=50", sessione);
        assertEquals(200, ok.status, ok.body());
        JsonNode json = mapper.readTree(ok.body());
        assertEquals(6, json.get("risultati").size()); assertTrue(json.get("prossimoDopoId").isNull());
        for (JsonNode item : json.get("risultati")) {
            Set<String> names = new HashSet<>(); item.fieldNames().forEachRemaining(names::add); assertEquals(CAMPI, names, "K: sette campi");
        }
        for (String vietato : List.of("hash-sintetico-u4", "3330001111", "1990-01-01", "SpecialitaRiservataU4", "U4-", "generazione",
                "modificato", "password", "telefono", "dataNascita", "medico", "paziente", "prenotaz", "referto")) {
            assertFalse(ok.body().contains(vietato), "L: " + vietato);
        }
        /* Categorie diverse rifiutate; anonimo con 401. */
        for (int altro : List.of(desk, medico, paziente, pazienteMedico)) {
            var s = store.create(altro, 0);
            assertEquals(403, get("/api/utenti/ricerca?cognome=" + q(tag), s).status, "attore " + altro);
        }
        assertEquals(401, get("/api/utenti/ricerca?cognome=" + q(tag), null).status);
        /* Un ID attore nella query è un parametro sconosciuto, non una prova d'identità. */
        assertEquals(400, get("/api/utenti/ricerca?cognome=" + q(tag) + "&idAttore=" + admin, sessione).status);
        /* Generazione obsoleta o amministratore disabilitato: 401 e revoca token. */
        int secondo = utente(extra + "Zsecondo", "AMMINISTRATORE"); var obsoleta = store.create(secondo, 0);
        sql("UPDATE utente SET generazione_autenticazione=1 WHERE id_utente=?", secondo);
        assertEquals(401, get("/api/utenti/ricerca?cognome=" + q(tag), obsoleta).status); assertTrue(store.find(obsoleta.id()).isEmpty());
        var corrente = store.create(secondo, 1); sql("UPDATE utente SET stato_accesso='DISABILITATO', generazione_autenticazione=2 WHERE id_utente=?", secondo);
        assertEquals(401, get("/api/utenti/ricerca?cognome=" + q(tag), corrente).status); assertTrue(store.find(corrente.id()).isEmpty());
        // O: GET /api/utenti keeps its contract (array of management accounts, query refused), plus statoAccesso (U5).
        var elenco = get("/api/utenti", sessione); assertEquals(200, elenco.status);
        JsonNode lista = mapper.readTree(elenco.body()); assertTrue(lista.isArray());
        boolean trovato = false;
        for (JsonNode item : lista) {
            if (item.get("idUtente").intValue() == admin) { trovato = true; }
            Set<String> names = new HashSet<>(); item.fieldNames().forEachRemaining(names::add);
            /* statoAccesso è l'unico campo aggiunto alla proiezione dell'elenco. */
            assertEquals(Set.of("idUtente", "nome", "cognome", "email", "ruoloGestionale", "paziente", "medico", "statoAccesso"), names);
        }
        assertTrue(trovato);
        var conQuery = get("/api/utenti?cognome=" + q(tag), sessione); assertEquals(400, conQuery.status);
        assertEquals("{\"error\":\"Query string non consentita.\"}", conQuery.body());
    }

    @Test void realHttpServerRoutesTheSearchLikeMain() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/utenti", handler); server.start();
        try {
            var client = HttpClient.newHttpClient(); var s = store.create(admin, 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var ok = client.send(HttpRequest.newBuilder(URI.create(base + "/api/utenti/ricerca?cognome=" + q(tag) + "&ruolo=NESSUNO&statoAccesso=DISABILITATO"))
                    .header("Cookie", config.cookieName() + "=" + s.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(disabilitato, mapper.readTree(ok.body()).get("risultati").get(0).get("idUtente").intValue());
            assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElseThrow());
            var post = client.send(HttpRequest.newBuilder(URI.create(base + "/api/utenti/ricerca?cognome=" + q(tag)))
                    .header("Cookie", config.cookieName() + "=" + s.id()).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode()); assertEquals("GET", post.headers().firstValue("Allow").orElseThrow());
            var vuota = client.send(HttpRequest.newBuilder(URI.create(base + "/api/utenti/ricerca"))
                    .header("Cookie", config.cookieName() + "=" + s.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, vuota.statusCode());
        } finally { server.stop(0); }
    }
}
