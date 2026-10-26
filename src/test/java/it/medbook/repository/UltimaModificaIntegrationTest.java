package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.model.RuoloGestionale;
import it.medbook.support.MutableClock;
import org.junit.jupiter.api.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Metadati di ogni scrittura UTENTE su MySQL isolato e orologio deterministico. Le attese si
 * osservano in performance_schema e il tempo avanza soltanto mentre lo scrittore è bloccato.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UltimaModificaIntegrationTest {
    static final LocalDateTime SENTINELLA = LocalDateTime.of(2000, 1, 1, 0, 0);
    final List<Integer> users = new ArrayList<>();
    final List<String> emails = new ArrayList<>();
    ConnectionFactory factory;
    MutableClock clock;
    int sequence = new Random().nextInt(17_000);

    @BeforeAll void fixture() { factory = TestDatabaseConnections.isolatedFactory(); }
    @BeforeEach void orologio() {
        clock = new MutableClock();
        clock.advance(Duration.ofMillis(999)); /* 09:00:00.999 UTC va memorizzato come 09:00:00, senza arrotondamento. */
    }
    @AfterAll void cleanup() throws Exception {
        if (factory == null) { return; }
        for (String email : emails) { sql("DELETE FROM utente WHERE email=?", email); }
        for (int id : users) { sql("DELETE FROM utente WHERE id_utente=?", id); }
    }

    UtenteRepository utenti() { return new UtenteRepository(factory, clock); }
    MedicoRepository medici() { return new MedicoRepository(factory, clock); }
    RegistrazioneRepository registrazioni() { return new RegistrazioneRepository(factory, clock); }
    LocalDateTime ora() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }

    String email() { String e = "u3-" + UUID.randomUUID() + "@example.invalid"; emails.add(e); return e; }
    String numero() { return "U3-" + UUID.randomUUID(); }
    String cf() {
        int n = sequence++;
        String first = "ZZX" + (char) ('A' + n / 676 % 26) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26) + "90A01A000";
        int[] odd = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};
        int sum = 0;
        for (int i = 0; i < 15; i++) { char c = first.charAt(i); int v = Character.isDigit(c) ? c - '0' : c - 'A'; sum += i % 2 == 0 ? odd[v] : v; }
        return first + (char) ('A' + sum % 26);
    }
    int utente(String ruolo) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES('Fixture','U3',?,'hash-sintetico',?)",
                Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, email()); s.setString(2, ruolo); assertEquals(1, s.executeUpdate());
            try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); int id = k.getInt(1); users.add(id); return id; }
        }
    }
    void sql(String statement, Object... values) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
        }
    }
    /* Valori riconoscibili nei metadati dimostrano che un'operazione senza cambiamenti non li riscrive. */
    void sentinella(int id) throws Exception { sql("UPDATE utente SET modificato_da=NULL, modificato_il=? WHERE id_utente=?", SENTINELLA, id); }
    record Traccia(Integer da, LocalDateTime il) { }
    Traccia traccia(int id) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT modificato_da, modificato_il, CAST(modificato_il AS CHAR) AS testo FROM utente WHERE id_utente=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                String testo = r.getString("testo");
                assertTrue(testo == null || testo.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "DATETIME al secondo: " + testo);
                return new Traccia(r.getObject("modificato_da", Integer.class), r.getObject("modificato_il", LocalDateTime.class));
            }
        }
    }
    int idDaEmail(String email) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id_utente FROM utente WHERE email=?")) {
            s.setString(1, email); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : -1; }
        }
    }
    String valore(String colonna, int id) throws Exception {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT " + colonna + " FROM utente WHERE id_utente=?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }

    // A, I, J
    @Test void a_patientRegistrationHasNoAuthorAndClockTimeTruncatedToSeconds() throws Exception {
        String email = email();
        int id = registrazioni().registraPaziente("Fixture", "U3", email, "hash-sintetico", cf(), LocalDate.of(1990, 1, 1), null);
        assertEquals(new Traccia(null, LocalDateTime.of(2026, 10, 10, 9, 0, 0)), traccia(id), "troncato, non arrotondato a :01");
        clock.advance(Duration.ofHours(2).plusMillis(500));
        int altro = registrazioni().registraPaziente("Fixture", "U3", email(), "hash-sintetico", cf(), LocalDate.of(1990, 1, 1), null);
        assertEquals(new Traccia(null, LocalDateTime.of(2026, 10, 10, 11, 0, 1)), traccia(altro), "Clock deterministico");
    }

    // B, H
    @Test void b_managementAccountCreationTracksTheActor() throws Exception {
        int admin = utente("AMMINISTRATORE");
        var creato = utenti().createInterno(admin, "Nuovo", "Desk", email(), "hash-sintetico", RuoloGestionale.OPERATORE_DESK);
        users.add(creato.idUtente());
        assertEquals(new Traccia(admin, ora()), traccia(creato.idUtente()));
        assertNull(traccia(admin).da(), "l'attore non viene modificato");
    }

    // C, H
    @Test void c_effectiveRoleChangeTracksActorAndInstant() throws Exception {
        int admin = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK"); sentinella(desk); sentinella(admin);
        clock.advance(Duration.ofMinutes(3));
        utenti().updateRuolo(admin, desk, RuoloGestionale.AMMINISTRATORE);
        assertEquals(new Traccia(admin, ora()), traccia(desk)); assertEquals("AMMINISTRATORE", valore("ruolo_gestionale", desk));
        assertEquals(new Traccia(null, SENTINELLA), traccia(admin), "solo la riga modificata");
    }

    // D
    @Test void d_idempotentRoleChangeWritesNothing() throws Exception {
        int admin = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK"); sentinella(desk);
        var risposta = utenti().updateRuolo(admin, desk, RuoloGestionale.OPERATORE_DESK);
        assertEquals(RuoloGestionale.OPERATORE_DESK, risposta.ruoloGestionale());
        assertEquals(new Traccia(null, SENTINELLA), traccia(desk));
    }

    /* Le scritture limitate a MEDICO non aggiornano questi metadati. */
    @Test void e_doctorCreationTracksActorWhileMedicoOnlyWritesDoNot() throws Exception {
        int admin = utente("AMMINISTRATORE");
        var medico = medici().crea(admin, "Medico", "U3", email(), "hash-sintetico", "Cardiologia", numero());
        assertEquals(new Traccia(admin, ora()), traccia(medico.idUtente()));
        sentinella(medico.idUtente());
        medici().aggiornaDatiProfessionali(admin, medico.idUtente(), "Neurologia", numero());
        medici().cambiaStatoMedico(admin, medico.idUtente(), "INATTIVO");
        int paziente = registrazioni().registraPaziente("Paz", "U3", email(), "hash-sintetico", cf(), LocalDate.of(1990, 1, 1), null);
        sentinella(paziente);
        medici().collega(admin, paziente, "Pediatria", numero());
        assertEquals(new Traccia(null, SENTINELLA), traccia(medico.idUtente()), "dati professionali e stato: solo medico");
        assertEquals(new Traccia(null, SENTINELLA), traccia(paziente), "collegamento: solo medico");
    }

    // F, G
    @Test void f_g_personalDataTrackedOnlyWhenSomethingChangesBinary() throws Exception {
        int admin = utente("AMMINISTRATORE"), altroAdmin = utente("AMMINISTRATORE");
        String email = email();
        var medico = medici().crea(admin, "Luca", "Bianchi", email, "hash-sintetico", "Cardiologia", numero()); int id = medico.idUtente();
        sentinella(id);
        medici().aggiornaDatiPersonali(altroAdmin, id, "Luca", "Bianchi", email);
        assertEquals(new Traccia(null, SENTINELLA), traccia(id), "G: nessuna variazione, nessun tracciamento");
        for (String[] dati : List.of(new String[]{"luca", "Bianchi", email}, new String[]{"luca", "Bianchi ", email},
                new String[]{"luca", "Bianchi ", email.toUpperCase(Locale.ROOT)})) {
            sentinella(id); clock.advance(Duration.ofSeconds(7));
            medici().aggiornaDatiPersonali(altroAdmin, id, dati[0], dati[1], dati[2]);
            assertEquals(new Traccia(altroAdmin, ora()), traccia(id), "F: variazione binaria " + Arrays.toString(dati));
        }
        assertEquals("Bianchi ", valore("cognome", id));
    }

    /* Eliminare l'autore imposta NULL conservando l'istante. */
    @Test void h_authorForeignKeyIsValidAndSetNullOnDelete() throws Exception {
        int admin = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK");
        utenti().updateRuolo(admin, desk, RuoloGestionale.AMMINISTRATORE);
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT a.ruolo_gestionale FROM utente u JOIN utente a ON a.id_utente=u.modificato_da WHERE u.id_utente=?")) {
            s.setInt(1, desk); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); assertEquals("AMMINISTRATORE", r.getString(1)); }
        }
        sql("DELETE FROM utente WHERE id_utente=?", admin);
        assertEquals(new Traccia(null, ora()), traccia(desk));
    }

    /* Fa fallire la prima SELECT preparata dopo la scrittura del prefisso indicato, nella stessa transazione. */
    ConnectionFactory guastoDopo(String prefissoScrittura) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = factory.getConnection(); boolean[] scritto = {false};
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("prepareStatement")) {
                        String sql = ((String) a[0]).strip();
                        if (sql.startsWith(prefissoScrittura)) { scritto[0] = true; }
                        else if (scritto[0] && sql.startsWith("SELECT") && !sql.contains("_LOCK(")) { throw new SQLException("guasto iniettato"); }
                    }
                    try { return m.invoke(c, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }

    // K
    @Test void k_rollbackPersistsNoMetadata() throws Exception {
        int admin = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK"); sentinella(desk);
        assertThrows(SQLException.class, () -> new UtenteRepository(guastoDopo("UPDATE utente"), clock).updateRuolo(admin, desk, RuoloGestionale.AMMINISTRATORE));
        assertEquals(new Traccia(null, SENTINELLA), traccia(desk)); assertEquals("OPERATORE_DESK", valore("ruolo_gestionale", desk));

        String creataEmail = email();
        assertThrows(SQLException.class, () -> new UtenteRepository(guastoDopo("INSERT INTO utente"), clock)
                .createInterno(admin, "N", "C", creataEmail, "hash-sintetico", RuoloGestionale.OPERATORE_DESK));
        assertEquals(-1, idDaEmail(creataEmail));

        var medico = medici().crea(admin, "Luca", "Bianchi", email(), "hash-sintetico", "Cardiologia", numero()); sentinella(medico.idUtente());
        assertThrows(SQLException.class, () -> new MedicoRepository(guastoDopo("UPDATE utente"), clock).aggiornaDatiPersonali(admin, medico.idUtente(), "Altro", "Nome", email()));
        assertEquals(new Traccia(null, SENTINELLA), traccia(medico.idUtente())); assertEquals("Luca", valore("nome", medico.idUtente()));

        String numero = numero(); medici().crea(admin, "Primo", "U3", email(), "hash-sintetico", "Cardiologia", numero);
        String secondaEmail = email();
        assertThrows(ConflittoMedicoException.class, () -> medici().crea(admin, "Secondo", "U3", secondaEmail, "hash-sintetico", "Cardiologia", numero));
        assertEquals(-1, idDaEmail(secondaEmail), "INSERT utente annullata dal numero duplicato");

        String cf = cf(); registrazioni().registraPaziente("Paz", "U3", email(), "hash-sintetico", cf, LocalDate.of(1990, 1, 1), null);
        String pazienteEmail = email();
        assertThrows(RegistrazioneDuplicataException.class, () -> registrazioni().registraPaziente("Paz", "U3", pazienteEmail, "hash-sintetico", cf, LocalDate.of(1990, 1, 1), null));
        assertEquals(-1, idDaEmail(pazienteEmail), "INSERT utente annullata dal codice fiscale duplicato");
    }

    /*
     * Una transazione mantiene FOR UPDATE; si osserva lo scrittore in attesa, si avanza l'orologio
     * e si conferma il blocco iniziale. Il timestamp deve essere letto dopo l'attesa.
     */
    void istanteDopoAttesa(int bloccata, Integer autore, Callable<Integer> scrittura) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = factory.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement s = holder.prepareStatement("SELECT id_utente FROM utente WHERE id_utente=? FOR UPDATE")) {
                s.setInt(1, bloccata); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); }
            }
            long thread;
            try (Statement s = holder.createStatement(); ResultSet r = s.executeQuery("SELECT PS_THREAD_ID(CONNECTION_ID())")) { r.next(); thread = r.getLong(1); }
            LocalDateTime prima = ora();
            Future<Integer> writer = executor.submit(scrittura);
            try {
                attendiAttesa(thread, writer);
                clock.advance(Duration.ofHours(1));
            } finally { holder.commit(); }
            int tracciata = writer.get(20, TimeUnit.SECONDS);
            assertEquals(new Traccia(autore, prima.plusHours(1)), traccia(tracciata));
        } finally { executor.shutdownNow(); }
    }
    void attendiAttesa(long holderThread, Future<?> writer) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.OBJECT_SCHEMA = DATABASE() AND r.OBJECT_NAME = 'utente'
                  AND w.BLOCKING_ENGINE_TRANSACTION_ID IN
                      (SELECT h.ENGINE_TRANSACTION_ID FROM performance_schema.data_locks h WHERE h.THREAD_ID = ?)""";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, holderThread);
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { return; } }
                assertFalse(writer.isDone(), "la scrittura non deve completarsi prima del rilascio del lock");
                assertTrue(System.nanoTime() < deadline, "nessuna attesa InnoDB osservata sulla riga utente");
                Thread.sleep(10); /* Il polling osserva l'attesa effettiva. */
            }
        }
    }

    @Test void l_instantIsReadAfterTheDecisiveRowLocks() throws Exception {
        int admin = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK");
        /* Cambio ruolo: si blocca prima l'attore con ID minore, poi il destinatario. */
        istanteDopoAttesa(desk, admin, () -> utenti().updateRuolo(admin, desk, RuoloGestionale.AMMINISTRATORE).idUtente());
        /* La creazione attende la riga dell'attore prima di INSERT. */
        istanteDopoAttesa(admin, admin, () -> {
            int id = utenti().createInterno(admin, "N", "C", email(), "hash-sintetico", RuoloGestionale.OPERATORE_DESK).idUtente();
            users.add(id); return id;
        });
        istanteDopoAttesa(admin, admin, () -> medici().crea(admin, "Medico", "U3", email(), "hash-sintetico", "Cardiologia", numero()).idUtente());
        /* I dati personali attendono la riga UTENTE del medico. */
        int medico = medici().crea(admin, "Luca", "Bianchi", email(), "hash-sintetico", "Cardiologia", numero()).idUtente();
        istanteDopoAttesa(medico, admin, () -> medici().aggiornaDatiPersonali(admin, medico, "Marco", "Bianchi", email()).idUtente());
    }

    @Test void l_lastCommittedChangeWinsWithoutHistory() throws Exception {
        int primo = utente("AMMINISTRATORE"), secondo = utente("AMMINISTRATORE"), desk = utente("OPERATORE_DESK");
        utenti().updateRuolo(primo, desk, RuoloGestionale.AMMINISTRATORE);
        clock.advance(Duration.ofSeconds(1));
        utenti().updateRuolo(secondo, desk, RuoloGestionale.OPERATORE_DESK);
        assertEquals(new Traccia(secondo, ora()), traccia(desk));
    }
}
