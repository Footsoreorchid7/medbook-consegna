package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.*;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.RuoloGestionale;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import it.medbook.service.AutenticazioneService;
import it.medbook.service.MedicoService;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/* Creazione, collegamento e modifiche personali/professionali su MySQL isolato. */
class MedicoRepositoryIntegrationTest {
    static final String PASSWORD = " password medico ";
    static final String HASH_FITTIZIO = "synthetic-test-hash";
    final List<Integer> users = new ArrayList<>();
    final PasswordHasher hasher = new PasswordHasher();
    ConnectionFactory factory; MedicoRepository repo; MedicoService service; int admin;

    @BeforeEach void setup() throws Exception {
        factory = TestDatabaseConnections.isolatedFactory(); repo = new MedicoRepository(factory); service = new MedicoService(repo, hasher);
        admin = user("AMMINISTRATORE", false, false);
    }
    @AfterEach void cleanup() throws Exception { if (factory != null) { for (Integer id : users) { execute("DELETE FROM utente WHERE id_utente=?", id); } } }

    static String email() { return "f5-" + UUID.randomUUID() + "@example.invalid"; }
    static String numero() { return "F5-" + UUID.randomUUID(); }
    int user(String ruolo, boolean medico, boolean paziente) throws SQLException {
        int id;
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('F5','Fixture',?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, email()); s.setString(2, HASH_FITTIZIO); s.setString(3, ruolo); s.executeUpdate();
            try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); id = k.getInt(1); }
        }
        users.add(id);
        if (medico) { execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)", id, "Generale", numero()); }
        if (paziente) { execute("INSERT INTO paziente (id_utente,codice_fiscale,data_nascita) VALUES (?,?,'1990-01-01')", id,
                UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT)); }
        return id;
    }
    void execute(String sql, Object... values) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
        }
    }
    List<Object> riga(String table, int id) throws SQLException {
        return MedicoInattivoFlussiIntegrationTest.riga(factory, table, "id_utente", id);
    }
    int conta(String sql, Object value) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, value); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
    String valore(String sql, int id) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
    AutenticazioneService auth() { return new AutenticazioneService(new UtenteRepository(factory), hasher, new SessionStore(Clock.systemUTC(), 10)); }
    MedicoResponse nuovo(String email, String numero) throws Exception {
        var r = service.crea(admin, new NuovoMedicoRequest(" Anna ", " Rossi ", email, PASSWORD, " Cardiologia ", numero)); users.add(r.idUtente()); return r;
    }
    static <T extends Throwable> T rifiuto(Class<T> type, String message, MedicoInattivoFlussiIntegrationTest.Executable action) {
        T e = assertThrows(type, action::run); assertEquals(message, e.getMessage()); return e;
    }

    @Test void createsActiveDoctorWithNewAccountThatCanLogIn() throws Exception {
        String email = email(); var r = nuovo(" " + email.toUpperCase(Locale.ROOT) + " ", " ORD-" + UUID.randomUUID() + " ");
        assertEquals(email, r.email()); assertEquals("Anna", r.nome()); assertEquals("ATTIVO", r.stato());
        assertNull(r.ruoloGestionale()); assertFalse(r.paziente());
        assertNull(valore("SELECT ruolo_gestionale FROM utente WHERE id_utente=?", r.idUtente()));
        assertEquals("ATTIVO", valore("SELECT stato FROM medico WHERE id_utente=?", r.idUtente()));
        var auth = auth(); var session = auth.login(email, PASSWORD.toCharArray()).orElseThrow();
        var identity = auth.identity(session).orElseThrow(); assertTrue(identity.medico()); assertFalse(identity.paziente());
        assertTrue(auth.login(email, PASSWORD.strip().toCharArray()).isEmpty(), "password non trimmata");
        assertFalse(valore("SELECT password_hash FROM utente WHERE id_utente=?", r.idUtente()).contains(PASSWORD.strip()));
    }

    @Test void linksPlainUserAndPatientKeepingThePatientProfile() throws Exception {
        int semplice = user(null, false, false), paziente = user(null, false, true); var pazienteRow = riga("paziente", paziente);
        assertEquals("ATTIVO", service.collega(admin, semplice, new CollegamentoMedicoRequest("Neurologia", numero())).stato());
        var r = service.collega(admin, paziente, new CollegamentoMedicoRequest("Pediatria", numero()));
        assertTrue(r.paziente()); assertEquals(pazienteRow, riga("paziente", paziente));
        var identity = new UtenteRepository(factory).findById(paziente).orElseThrow(); assertTrue(identity.medico()); assertTrue(identity.paziente());
        assertTrue(new UtenteRepository(factory).findById(semplice).orElseThrow().medico());
    }

    @Test void refusesManagementUsersAndExistingProfiles() throws Exception {
        for (String ruolo : List.of("AMMINISTRATORE", "OPERATORE_DESK", "amministratore")) {
            int u = user(null, false, false); execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?", ruolo, u);
            rifiuto(ConflittoMedicoException.class, MedicoRepository.RUOLO_GESTIONALE, () -> service.collega(admin, u, new CollegamentoMedicoRequest("S", numero())));
            assertEquals(0, conta("SELECT COUNT(*) FROM medico WHERE id_utente=?", u), ruolo);
        }
        int medico = user(null, true, false); var before = riga("medico", medico);
        rifiuto(ConflittoMedicoException.class, MedicoRepository.PROFILO_PRESENTE, () -> service.collega(admin, medico, new CollegamentoMedicoRequest("S", numero())));
        assertEquals(before, riga("medico", medico));
        int desk = user("OPERATORE_DESK", false, false);
        assertThrows(OperazioneNonAutorizzataException.class, () -> service.collega(desk, user(null, false, false), new CollegamentoMedicoRequest("S", numero())));
    }

    @Test void duplicateEmailAndNumberAre409UnderAiCiAndRollBackTheAccount() throws Exception {
        String esistente = email(); int altro = user(null, false, false); execute("UPDATE utente SET email=? WHERE id_utente=?", esistente, altro);
        String numeroEsistente = "Ord-Àbc-" + UUID.randomUUID(); var medico = nuovo(email(), numeroEsistente);
        int utenti = conta("SELECT COUNT(*) FROM utente WHERE id_utente>?", 0);
        rifiuto(ConflittoMedicoException.class, MedicoRepository.EMAIL_DUPLICATA, () -> nuovo(esistente.toUpperCase(Locale.ROOT), numero()));
        /* Numero equivalente per maiuscole e accenti: rollback anche della nuova riga UTENTE inserita prima. */
        String nuovaEmail = email();
        rifiuto(ConflittoMedicoException.class, MedicoRepository.NUMERO_DUPLICATO, () -> nuovo(nuovaEmail, numeroEsistente.toLowerCase(Locale.ROOT).replace('à', 'a')));
        assertEquals(0, conta("SELECT COUNT(*) FROM utente WHERE email=?", nuovaEmail), "utente non orfano");
        assertEquals(utenti, conta("SELECT COUNT(*) FROM utente WHERE id_utente>?", 0));
        int u = user(null, false, false);
        rifiuto(ConflittoMedicoException.class, MedicoRepository.NUMERO_DUPLICATO, () -> service.collega(admin, u, new CollegamentoMedicoRequest("S", numeroEsistente.toUpperCase(Locale.ROOT))));
        int secondo = user(null, true, false); var before = riga("medico", secondo);
        rifiuto(ConflittoMedicoException.class, MedicoRepository.NUMERO_DUPLICATO,
                () -> service.aggiornaDatiProfessionali(admin, secondo, new DatiProfessionaliMedicoRequest("S", numeroEsistente.toLowerCase(Locale.ROOT))));
        assertEquals(before, riga("medico", secondo));
        rifiuto(ConflittoMedicoException.class, MedicoRepository.EMAIL_DUPLICATA,
                () -> service.aggiornaDatiPersonali(admin, medico.idUtente(), new DatiPersonaliMedicoRequest("A", "R", esistente)));
        /* Cambiare il casing del proprio numero non costituisce duplicato di sé. */
        assertEquals(numeroEsistente.toUpperCase(Locale.ROOT), service.aggiornaDatiProfessionali(admin, medico.idUtente(),
                new DatiProfessionaliMedicoRequest("S", numeroEsistente.toUpperCase(Locale.ROOT))).numeroIscrizioneOrdine());
    }

    @Test void duplicatesWithItalianServerMessagesAndOtherErrorsStaySqlErrors() throws Exception {
        String esistente = email(); int altro = user(null, false, false); execute("UPDATE utente SET email=? WHERE id_utente=?", esistente, altro);
        String numeroEsistente = nuovo(email(), numero()).numeroIscrizioneOrdine();
        ConnectionFactory italiano = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) { @Override public Connection getConnection() throws SQLException {
            Connection c = factory.getConnection(); try (Statement s = c.createStatement()) { s.execute("SET SESSION lc_messages='it_IT'"); } return c; } };
        try (Connection c = italiano.getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO utente (nome,cognome,email,password_hash) VALUES ('X','Y',?,'h')")) {
            s.setString(1, esistente); var raw = assertThrows(SQLException.class, s::executeUpdate);
            assertEquals(1062, raw.getErrorCode()); assertFalse(raw.getMessage().contains("Duplicate entry"), raw.getMessage());
        }
        var it = new MedicoRepository(italiano);
        rifiuto(ConflittoMedicoException.class, MedicoRepository.EMAIL_DUPLICATA, () -> it.crea(admin, "N", "C", esistente, HASH_FITTIZIO, "S", numero()));
        String libera = email();
        rifiuto(ConflittoMedicoException.class, MedicoRepository.NUMERO_DUPLICATO, () -> it.crea(admin, "N", "C", libera, HASH_FITTIZIO, "S", numeroEsistente));
        /* Errore sul secondo INSERT per lunghezza in modalità SQL rigorosa: SQLException e rollback completo. */
        var other = assertThrows(SQLException.class, () -> it.crea(admin, "N", "C", libera, HASH_FITTIZIO, "S", "n".repeat(51)));
        assertNotEquals(1062, other.getErrorCode());
        assertEquals(0, conta("SELECT COUNT(*) FROM utente WHERE email=?", libera), "rollback dell'utente dopo l'errore sulla seconda INSERT");
    }

    @Test void professionalUpdateForActiveAndInactiveDoctorsAndAdministratorDoctors() throws Exception {
        int m = user(null, true, false);
        var r = service.aggiornaDatiProfessionali(admin, m, new DatiProfessionaliMedicoRequest(" Oncologia ", " ORD-NEW-" + m + " "));
        assertEquals("Oncologia", r.specializzazione()); assertEquals("ORD-NEW-" + m, r.numeroIscrizioneOrdine()); assertEquals("ATTIVO", r.stato());
        execute("UPDATE medico SET stato='INATTIVO' WHERE id_utente=?", m);
        assertEquals("INATTIVO", service.aggiornaDatiProfessionali(admin, m, new DatiProfessionaliMedicoRequest("Ematologia", numero())).stato());
        // Amministratore-medico preesistente: dati professionali ammessi anche su sé stesso, personali vietati.
        int am = user("AMMINISTRATORE", true, false); var personali = riga("utente", am);
        assertEquals(RuoloGestionale.AMMINISTRATORE, service.aggiornaDatiProfessionali(admin, am, new DatiProfessionaliMedicoRequest("Chirurgia", numero())).ruoloGestionale());
        assertEquals("Radiologia", service.aggiornaDatiProfessionali(am, am, new DatiProfessionaliMedicoRequest("Radiologia", numero())).specializzazione());
        rifiuto(ConflittoMedicoException.class, MedicoRepository.DATI_PERSONALI_GESTIONALE,
                () -> service.aggiornaDatiPersonali(admin, am, new DatiPersonaliMedicoRequest("X", "Y", email())));
        rifiuto(ConflittoMedicoException.class, MedicoRepository.DATI_PERSONALI_GESTIONALE,
                () -> service.aggiornaDatiPersonali(am, am, new DatiPersonaliMedicoRequest("X", "Y", email())));
        assertEquals(personali, riga("utente", am));
    }

    @Test void personalUpdateChangesLoginEmailAndKeepsExistingSessions() throws Exception {
        String vecchia = email(); var m = nuovo(vecchia, numero()); var auth = auth();
        var session = auth.login(vecchia, PASSWORD.toCharArray()).orElseThrow();
        String nuova = email();
        var r = service.aggiornaDatiPersonali(admin, m.idUtente(), new DatiPersonaliMedicoRequest(" Luca ", " Bianchi ", " " + nuova.toUpperCase(Locale.ROOT) + " "));
        assertEquals(List.of("Luca", "Bianchi", nuova), List.of(r.nome(), r.cognome(), r.email()));
        assertTrue(auth.identity(session).orElseThrow().medico(), "sessione esistente valida");
        assertTrue(auth.login(nuova, PASSWORD.toCharArray()).isPresent()); assertTrue(auth.login(vecchia, PASSWORD.toCharArray()).isEmpty());
        /* Il medico-paziente senza ruolo gestionale può cambiare dati personali senza alterare il profilo paziente. */
        int pm = user(null, true, true); var paziente = riga("paziente", pm);
        assertEquals("Maria", service.aggiornaDatiPersonali(admin, pm, new DatiPersonaliMedicoRequest("Maria", "Verdi", email())).nome());
        assertEquals(paziente, riga("paziente", pm));
    }

    @Test void anomalousStateIs409WithoutChangesOrNormalization() throws Exception {
        int m = user(null, true, false);
        for (String anomalo : List.of("attivo", "Attivo", "inattivo")) {
            execute("UPDATE medico SET stato=? WHERE id_utente=?", anomalo, m); var medico = riga("medico", m); var utente = riga("utente", m);
            rifiuto(ConflittoMedicoException.class, MedicoRepository.STATO_NON_VALIDO, () -> service.aggiornaDatiProfessionali(admin, m, new DatiProfessionaliMedicoRequest("S", numero())));
            rifiuto(ConflittoMedicoException.class, MedicoRepository.STATO_NON_VALIDO, () -> service.aggiornaDatiPersonali(admin, m, new DatiPersonaliMedicoRequest("N", "C", email())));
            assertEquals(medico, riga("medico", m)); assertEquals(utente, riga("utente", m));
            assertEquals(HexFormat.of().withUpperCase().formatHex(anomalo.getBytes()), valore("SELECT HEX(stato) FROM medico WHERE id_utente=?", m));
        }
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiProfessionali(admin, m, new DatiProfessionaliMedicoRequest(" ", "O")));
    }

    @Test void actorDemotedWhileWaitingGetsSame403ForExistingAndMissingTarget() throws Exception {
        for (boolean f6 : new boolean[]{false, true}) for (boolean targetEsistente : new boolean[]{true, false}) {
            int a = user("AMMINISTRATORE", false, false), b = user("AMMINISTRATORE", false, false);
            int target = user(null, true, false); if (!targetEsistente) { execute("DELETE FROM utente WHERE id_utente=?", target); }
            var before = targetEsistente ? riga("medico", target) : null;
            var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
            var demoter = new UtenteRepository(AccountInternoRepositoryIntegrationTest.observe(factory, () -> { }, () -> { held.countDown(); AccountInternoRepositoryIntegrationTest.await(release); }));
            var waiting = new MedicoRepository(AccountInternoRepositoryIntegrationTest.observe(factory, attempted::countDown, () -> { }));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var demotion = pool.submit(() -> demoter.updateRuolo(b, a, RuoloGestionale.OPERATORE_DESK)); AccountInternoRepositoryIntegrationTest.await(held);
                var request = pool.submit(() -> f6 ? waiting.cambiaStatoMedico(a, target, "INATTIVO") : waiting.aggiornaDatiProfessionali(a, target, "S", numero())); AccountInternoRepositoryIntegrationTest.await(attempted);
                assertThrows(TimeoutException.class, () -> request.get(200, TimeUnit.MILLISECONDS)); release.countDown();
                demotion.get(10, TimeUnit.SECONDS);
                var error = assertThrows(ExecutionException.class, () -> request.get(10, TimeUnit.SECONDS)).getCause();
                assertInstanceOf(OperazioneNonAutorizzataException.class, error, "target esistente=" + targetEsistente);
                assertEquals("Operazione non autorizzata.", error.getMessage());
            } finally { release.countDown(); }
            if (targetEsistente) { assertEquals(before, riga("medico", target)); }
        }
    }
}
