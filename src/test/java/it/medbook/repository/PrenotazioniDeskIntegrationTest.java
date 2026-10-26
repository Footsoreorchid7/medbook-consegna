package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.PrenotazioneDeskResponse;
import it.medbook.model.*;
import it.medbook.service.PrenotazioneService;
import org.junit.jupiter.api.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** lettura desk su MySQL Docker isolato; confronta solo le righe create dal test. */
class PrenotazioniDeskIntegrationTest {
    static final LocalDate DAY = LocalDate.of(2096, 3, 10);
    static final String TAG = UUID.randomUUID().toString().substring(0, 8);
    ConnectionFactory factory;
    int desk, patient, titolare, sostituto, prestazione, stanza;
    final List<Integer> users = new ArrayList<>(), slots = new ArrayList<>(), bookings = new ArrayList<>();
    // Prenotazioni della fixture, per nome.
    int confermataInizio, effettuataInizio, nonErogataUltimo, annullataDaRichiesta, assente, annullataConfermata,
            richiesta, rifiutata, primaDelPeriodo, dopoIlPeriodo;
    int fallito, pagato, pagatoBis, contanti, rimborsoAlto, rimborsoBasso;

    @BeforeEach void setup() throws Exception {
        assumeTrue(System.getenv("MEDBOOK_TEST_DB_SERVER_UUID") != null && !System.getenv("MEDBOOK_TEST_DB_SERVER_UUID").isBlank(),
                "UUID Docker isolato obbligatorio.");
        factory = TestDatabaseConnections.isolatedFactory();
        desk = user("Desk", "OPERATORE_DESK");
        patient = user("Anna", null);
        execute("INSERT INTO paziente (id_utente,codice_fiscale,data_nascita,telefono) VALUES (?,?,?,?)",
                patient, ("CF" + TAG + "XXXXXXXX").substring(0, 16).toUpperCase(), LocalDate.of(1990, 1, 1), "+39-B8A-" + TAG);
        titolare = doctor("Mario"); sostituto = doctor("Luca");
        prestazione = insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES (?,60,80)", "Visita B8A " + TAG);
        execute("INSERT INTO medico_prestazione VALUES (?,?)", titolare, prestazione);
        stanza = insert("INSERT INTO stanza (nome) VALUES (?)", "Stanza B8A " + TAG);

        int inizio = slot(DAY.atStartOfDay(), true);                               // limite inferiore incluso
        int ultimo = slot(DAY.plusDays(30).atTime(23, 0), false);                    // ultimo giorno incluso
        int centrale = slot(DAY.plusDays(10).atTime(9, 0), false);
        int prima = slot(DAY.minusDays(1).atTime(23, 0), false);                     // escluso
        int dopo = slot(DAY.plusDays(31).atStartOfDay(), false);                      // escluso
        LocalDateTime gestione = DAY.minusDays(5).atTime(10, 0, 1);

        confermataInizio = booking(inizio, "CONFERMATA", sostituto, desk, gestione);
        effettuataInizio = booking(inizio, "EFFETTUATA", titolare, desk, gestione);
        nonErogataUltimo = booking(ultimo, "NON_EROGATA", titolare, desk, gestione);
        annullataDaRichiesta = booking(ultimo, "ANNULLATA", null, null, null);
        assente = booking(centrale, "PAZIENTE_ASSENTE", titolare, desk, gestione);
        annullataConfermata = booking(centrale, "ANNULLATA", sostituto, desk, gestione);
        richiesta = booking(centrale, "RICHIESTA", null, null, null);
        rifiutata = booking(centrale, "RIFIUTATA", null, desk, gestione);
        primaDelPeriodo = booking(prima, "CONFERMATA", titolare, desk, gestione);
        dopoIlPeriodo = booking(dopo, "CONFERMATA", titolare, desk, gestione);

        // Tre tentativi e due rimborsi sulla stessa prenotazione; ID dei rimborsi in ordine inverso ai pagamenti.
        fallito = pay(nonErogataUltimo, "ONLINE", "FALLITO", null);
        pagato = pay(nonErogataUltimo, "ONLINE", "PAGATO", null);
        pagatoBis = pay(nonErogataUltimo, "ONLINE", "PAGATO", null);
        rimborsoAlto = insert("INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione,verificato_da,verificato_il) "
                + "VALUES (?,'RESPINTO',80,?,?,?,?)", pagatoBis, DAY.minusDays(1).atStartOfDay(), "Motivazione riservata " + TAG,
                desk, DAY.minusDays(1).atTime(12, 0));
        rimborsoBasso = insert("INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione) VALUES (?,'RICHIESTO',80,?,?)",
                pagato, DAY.minusDays(1).atStartOfDay(), "Motivazione riservata " + TAG);
        contanti = pay(confermataInizio, "DESK_CONTANTI", "PAGATO", desk);
    }

    int user(String nome, String ruolo) throws SQLException {
        int id = insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES (?,?,?,'invalid-fixture',?)",
                nome, "B8A", "b8a-" + UUID.randomUUID() + "@example.invalid", ruolo);
        users.add(id); return id;
    }
    int doctor(String nome) throws SQLException {
        int id = user(nome, null);
        execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)", id, "Fixture", UUID.randomUUID().toString());
        return id;
    }
    int slot(LocalDateTime start, boolean configured) throws SQLException {
        int id = insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine,id_prestazione,id_stanza) VALUES (?,?,?,?,?)",
                titolare, start, start.plusHours(1), configured ? prestazione : null, configured ? stanza : null);
        slots.add(id); return id;
    }
    int booking(int slot, String state, Integer effective, Integer handler, LocalDateTime handled) throws SQLException {
        int id = insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,"
                + "data_ora_gestione,prezzo) VALUES (?,?,?,?,?,?,?,80)", patient, slot, handler, effective, state, DAY.minusDays(20).atStartOfDay(), handled);
        bookings.add(id); return id;
    }
    int pay(int booking, String mode, String state, Integer registrant) throws SQLException {
        LocalDateTime at = DAY.minusDays(2).atTime(8, 0);
        return insert("INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,provider_payment_id,creato_il,pagato_il) "
                + "VALUES (?,?,?,80,?,?,?,?)", booking, registrant, mode, state, mode.equals("ONLINE") ? "SIM-B8A-" + TAG : null,
                at, state.equals("PAGATO") ? at : null);
    }
    int insert(String sql, Object... args) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(s, args); assertEquals(1, s.executeUpdate());
            try (ResultSet r = s.getGeneratedKeys()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
    void execute(String sql, Object... args) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { bind(s, args); s.executeUpdate(); }
    }
    static void bind(PreparedStatement s, Object[] args) throws SQLException { for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]); }
    List<List<Object>> rows(String sql) throws SQLException {
        try (Connection c = factory.getConnection()) {
            List<Object> ids = new ArrayList<>(bookings); String in = String.join(",", Collections.nCopies(ids.size(), "?"));
            try (PreparedStatement q = c.prepareStatement(sql.replace("(:ids)", "(" + in + ")"))) {
                bind(q, ids.toArray());
                try (ResultSet r = q.executeQuery()) {
                    List<List<Object>> out = new ArrayList<>();
                    while (r.next()) { List<Object> row = new ArrayList<>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getObject(i)); out.add(row); }
                    return out;
                }
            }
        }
    }
    List<List<Object>> state() throws SQLException {
        List<List<Object>> all = new ArrayList<>();
        all.addAll(rows("SELECT * FROM prenotazione WHERE id_prenotazione IN (:ids) ORDER BY 1"));
        all.addAll(rows("SELECT * FROM pagamento WHERE id_prenotazione IN (:ids) ORDER BY 1"));
        all.addAll(rows("SELECT r.* FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione IN (:ids) ORDER BY 1"));
        return all;
    }

    @AfterEach void cleanup() throws Exception {
        if (factory == null) return;
        for (int b : bookings) {
            execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?", b);
            execute("DELETE FROM pagamento WHERE id_prenotazione=?", b);
            execute("DELETE FROM prenotazione WHERE id_prenotazione=?", b);
        }
        for (int s : slots) execute("DELETE FROM disponibilita WHERE id_disponibilita=?", s);
        if (prestazione > 0) { execute("DELETE FROM medico_prestazione WHERE id_prestazione=?", prestazione); execute("DELETE FROM prestazione WHERE id_prestazione=?", prestazione); }
        if (stanza > 0) execute("DELETE FROM stanza WHERE id_stanza=?", stanza);
        for (int u : users) execute("DELETE FROM utente WHERE id_utente=?", u);
    }

    PrenotazioneRepository repo() { return new PrenotazioneRepository(factory); }
    List<PrenotazioneDeskResponse> own(List<PrenotazioneDeskResponse> list) { return list.stream().filter(r -> bookings.contains(r.getIdPrenotazione())).toList(); }
    static List<Integer> ids(List<PrenotazioneDeskResponse> list) { return list.stream().map(PrenotazioneDeskResponse::getIdPrenotazione).toList(); }
    PrenotazioneDeskResponse find(List<PrenotazioneDeskResponse> list, int id) { return list.stream().filter(r -> r.getIdPrenotazione() == id).findFirst().orElseThrow(); }

    @Test void inclusiveWindowStatesAndStableOrdering() throws Exception {
        var before = state();
        var list = own(repo().findPrenotazioniDesk(null, DAY, DAY.plusDays(30)));
        // Stessa ora di inizio: ordine per ID. RICHIESTA, RIFIUTATA e i limiti esterni sono esclusi.
        assertEquals(List.of(confermataInizio, effettuataInizio, assente, annullataConfermata, nonErogataUltimo, annullataDaRichiesta), ids(list));
        assertEquals(Set.of(StatoPrenotazione.CONFERMATA, StatoPrenotazione.ANNULLATA, StatoPrenotazione.EFFETTUATA,
                StatoPrenotazione.PAZIENTE_ASSENTE, StatoPrenotazione.NON_EROGATA), new HashSet<>(list.stream().map(PrenotazioneDeskResponse::getStato).toList()));
        assertEquals(before, state(), "la lettura non modifica righe");
        assertEquals(List.of(primaDelPeriodo), ids(own(repo().findPrenotazioniDesk(null, DAY.minusDays(1), DAY.minusDays(1)))));
        assertEquals(List.of(dopoIlPeriodo), ids(own(repo().findPrenotazioniDesk(null, DAY.plusDays(31), DAY.plusDays(31)))));
        assertEquals(List.of(nonErogataUltimo, annullataDaRichiesta), ids(own(repo().findPrenotazioniDesk(null, DAY.plusDays(30), DAY.plusDays(30)))));
    }

    @Test void serviceAppliesInclusive31DayLimitOnRealData() throws Exception {
        var service = new PrenotazioneService(repo());
        assertEquals(6, own(service.getPrenotazioniDesk(null, null, DAY, DAY.plusDays(30))).size());
        assertThrows(IllegalArgumentException.class, () -> service.getPrenotazioniDesk(null, null, DAY, DAY.plusDays(31)));
    }

    @Test void medicoFilterUsesEffectiveDoctorOnly() throws Exception {
        assertEquals(List.of(confermataInizio, annullataConfermata), ids(own(repo().findPrenotazioniDesk(sostituto, DAY, DAY.plusDays(30)))));
        // Il titolare dello slot non basta: la confermata sostituita e l'annullata senza effettivo restano fuori.
        assertEquals(List.of(effettuataInizio, assente, nonErogataUltimo), ids(own(repo().findPrenotazioniDesk(titolare, DAY, DAY.plusDays(30)))));
        assertTrue(own(repo().findPrenotazioniDesk(desk, DAY, DAY.plusDays(30))).isEmpty());
        assertTrue(repo().findPrenotazioniDesk(Integer.MAX_VALUE, DAY, DAY.plusDays(30)).isEmpty());
    }

    @Test void nestedPaymentsAndRefundsWithoutDuplicates() throws Exception {
        var list = own(repo().findPrenotazioniDesk(null, DAY, DAY.plusDays(30)));
        assertEquals(6, list.size(), "nessuna prenotazione ripetuta per i pagamenti");
        var row = find(list, nonErogataUltimo);
        assertEquals(List.of(fallito, pagato, pagatoBis), row.getPagamenti().stream().map(PrenotazioneDeskResponse.Pagamento::idPagamento).toList());
        assertEquals(List.of(rimborsoAlto, rimborsoBasso), row.getRimborsi().stream().map(PrenotazioneDeskResponse.Rimborso::idRimborso).toList());
        assertTrue(rimborsoAlto < rimborsoBasso);
        var alto = row.getRimborsi().get(0);
        assertEquals(pagatoBis, alto.idPagamento()); assertEquals(StatoRimborso.RESPINTO, alto.stato()); assertEquals(desk, alto.verificatoDa());
        assertEquals(DAY.minusDays(1).atTime(12, 0), alto.verificatoIl()); assertEquals(0, new BigDecimal("80").compareTo(alto.importo()));
        var basso = row.getRimborsi().get(1);
        assertEquals(pagato, basso.idPagamento()); assertEquals(StatoRimborso.RICHIESTO, basso.stato());
        assertNull(basso.verificatoDa()); assertNull(basso.verificatoIl());
        var tentativo = row.getPagamenti().get(0);
        assertEquals(StatoPagamento.FALLITO, tentativo.stato()); assertNull(tentativo.pagatoIl()); assertNull(tentativo.registratoDa());
        var incasso = find(list, confermataInizio).getPagamenti();
        assertEquals(1, incasso.size()); assertEquals(contanti, incasso.getFirst().idPagamento());
        assertEquals(ModalitaPagamento.DESK_CONTANTI, incasso.getFirst().modalita()); assertEquals(desk, incasso.getFirst().registratoDa());
        assertNotNull(incasso.getFirst().pagatoIl());
        assertTrue(find(list, confermataInizio).getRimborsi().isEmpty());
        assertTrue(find(list, effettuataInizio).getPagamenti().isEmpty());
    }

    @Test void projectionNullsAndExcludedSensitiveData() throws Exception {
        var list = own(repo().findPrenotazioniDesk(null, DAY, DAY.plusDays(30)));
        var configured = find(list, confermataInizio);
        assertEquals(prestazione, configured.getIdPrestazione()); assertEquals("Visita B8A " + TAG, configured.getNomePrestazione());
        assertEquals(stanza, configured.getIdStanza()); assertEquals(titolare, configured.getIdMedicoTitolare());
        assertEquals("Mario", configured.getNomeMedicoTitolare()); assertEquals(sostituto, configured.getIdMedicoEffettivo());
        assertEquals("Luca", configured.getNomeMedicoEffettivo()); assertEquals(patient, configured.getIdPaziente());
        assertEquals("Anna", configured.getNomePaziente()); assertEquals(desk, configured.getGestitaDa());
        assertEquals(DAY.minusDays(5).atTime(10, 0, 1), configured.getDataOraGestione());
        assertEquals(DAY.atStartOfDay(), configured.getDataOraInizio()); assertEquals(DAY.atTime(1, 0), configured.getDataOraFine());
        assertEquals(0, new BigDecimal("80").compareTo(configured.getPrezzo())); assertFalse(configured.isIndisponibilitaSegnalata());
        var unconfigured = find(list, assente);
        assertNull(unconfigured.getIdPrestazione()); assertNull(unconfigured.getNomePrestazione());
        assertNull(unconfigured.getIdStanza()); assertNull(unconfigured.getNomeStanza());
        var fromRequest = find(list, annullataDaRichiesta);
        assertNull(fromRequest.getIdMedicoEffettivo()); assertNull(fromRequest.getNomeMedicoEffettivo());
        assertNull(fromRequest.getGestitaDa()); assertNull(fromRequest.getDataOraGestione());
        assertTrue(fromRequest.getPagamenti().isEmpty()); assertTrue(fromRequest.getRimborsi().isEmpty());
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(list);
        for (String secret : List.of("@example.invalid", "+39-B8A-" + TAG, "SIM-B8A-" + TAG, "Motivazione riservata", "invalid-fixture",
                ("CF" + TAG).toUpperCase(), "email", "telefono", "codiceFiscale", "provider", "motivazione", "password")) {
            assertFalse(json.contains(secret), secret);
        }
    }

    @Test void incoherentConfirmedDerivedRowsFailInsteadOfBeingHidden() throws Exception {
        execute("UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?", confermataInizio);
        assertThrows(SQLException.class, () -> repo().findPrenotazioniDesk(null, DAY, DAY));
        execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?", sostituto, confermataInizio);
        execute("UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?", effettuataInizio);
        assertThrows(SQLException.class, () -> repo().findPrenotazioniDesk(null, DAY, DAY));
        // Un'incoerenza fuori dal periodo non riguarda la lettura richiesta.
        assertEquals(List.of(nonErogataUltimo, annullataDaRichiesta), ids(own(repo().findPrenotazioniDesk(null, DAY.plusDays(30), DAY.plusDays(30)))));
    }

    /** Una sola istruzione, senza FOR UPDATE/FOR SHARE: snapshot unico e nessuna attesa sui lock di scrittura. */
    @Test void singleNonLockingStatementReadsCommittedSnapshot() throws Exception {
        AtomicInteger statements = new AtomicInteger();
        List<String> sql = new CopyOnWriteArrayList<>();
        ConnectionFactory observed = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection real = factory.getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {Connection.class}, (p, m, a) -> {
                    if (m.getName().startsWith("prepare") || m.getName().equals("createStatement")) {
                        statements.incrementAndGet(); if (a != null && a.length > 0 && a[0] instanceof String s) sql.add(s);
                    }
                    try { return m.invoke(real, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection writer = factory.getConnection()) {
            writer.setAutoCommit(false);
            // Scrittore concorrente non ancora confermato: lock esclusivi su prenotazione, pagamento e rimborso.
            try (PreparedStatement s = writer.prepareStatement("UPDATE prenotazione SET stato='ANNULLATA' WHERE id_prenotazione=?")) { s.setInt(1, confermataInizio); assertEquals(1, s.executeUpdate()); }
            try (PreparedStatement s = writer.prepareStatement("UPDATE pagamento SET stato='FALLITO',pagato_il=NULL WHERE id_pagamento=?")) { s.setInt(1, contanti); assertEquals(1, s.executeUpdate()); }
            try (PreparedStatement s = writer.prepareStatement("UPDATE rimborso SET stato='RESPINTO',verificato_da=?,verificato_il=? WHERE id_rimborso=?")) {
                s.setInt(1, desk); s.setObject(2, DAY.atStartOfDay()); s.setInt(3, rimborsoBasso); assertEquals(1, s.executeUpdate());
            }
            Future<List<PrenotazioneDeskResponse>> read = pool.submit(() -> new PrenotazioneRepository(observed).findPrenotazioniDesk(null, DAY, DAY.plusDays(30)));
            var list = own(read.get(10, TimeUnit.SECONDS));
            assertEquals(StatoPrenotazione.CONFERMATA, find(list, confermataInizio).getStato());
            assertEquals(StatoPagamento.PAGATO, find(list, confermataInizio).getPagamenti().getFirst().stato());
            assertEquals(StatoRimborso.RICHIESTO, find(list, nonErogataUltimo).getRimborsi().get(1).stato());
            writer.rollback();
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, statements.get());
        assertFalse(sql.getFirst().toUpperCase().contains("FOR UPDATE")); assertFalse(sql.getFirst().toUpperCase().contains("FOR SHARE"));
    }
}
