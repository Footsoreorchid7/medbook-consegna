package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.controller.UtenteController;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.repository.UtenteRepository.BootstrapRifiutatoException;
import it.medbook.repository.UtenteRepository.BootstrapRifiutatoException.Motivo;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import it.medbook.service.AutenticazioneService;
import it.medbook.service.BootstrapService;
import it.medbook.service.UtenteService;
import it.medbook.support.AuthenticatedTestExchange;
import it.medbook.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Bootstrap su MySQL isolato, con cataloghi dedicati senza account gestionali. Le attese GET_LOCK e
 * InnoDB si osservano nel server; mai usare il database applicativo.
 */
class BootstrapIntegrationTest {
    static final String PASSWORD = "Password-bootstrap", BOOTSTRAP = "medbook_test_bootstrap", PRE003 = "medbook_test_pre003";
    final MutableClock clock = new MutableClock();
    final PasswordHasher hasher = new PasswordHasher();

    // ---- dedicated catalogs -------------------------------------------------------------------------------------

    static final class Db implements AutoCloseable {
        final ConnectionFactory factory;
        Db(String variabile, String catalogo, boolean svuota) throws SQLException {
            factory = TestDatabaseConnections.isolatedFactory(variabile);
            try (Connection c = factory.getConnection()) { assertEquals(catalogo, c.getCatalog(), "catalogo dedicato al bootstrap"); }
            if (svuota) { svuota(); }
        }
        static Db bootstrap() throws SQLException { return new Db("MEDBOOK_TEST_BOOTSTRAP_DB_URL", BOOTSTRAP, true); }
        /* Soltanto nel catalogo dedicato, già verificato per nome e UUID. */
        void svuota() throws SQLException {
            try (Connection c = factory.getConnection(); Statement s = c.createStatement()) {
                assertEquals(BOOTSTRAP, c.getCatalog());
                s.executeUpdate("DELETE FROM paziente"); s.executeUpdate("UPDATE utente SET modificato_da = NULL"); s.executeUpdate("DELETE FROM utente");
            }
        }
        @Override public void close() throws SQLException { if (BOOTSTRAP.equals(catalogo())) { svuota(); } }
        String catalogo() throws SQLException { try (Connection c = factory.getConnection()) { return c.getCatalog(); } }
        int insert(String sql, Object... values) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); }
                s.executeUpdate(); try (ResultSet k = s.getGeneratedKeys()) { assertTrue(k.next()); return k.getInt(1); }
            }
        }
        void execute(String sql, Object... values) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.execute();
            }
        }
        long scalar(String sql, Object... values) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); }
                try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
            }
        }
        int utente(String email, String ruolo, String stato) throws SQLException {
            return insert("INSERT INTO utente (nome, cognome, email, password_hash, ruolo_gestionale, stato_accesso) VALUES ('Pre', 'Esistente', ?, 'hash-fittizio', ?, ?)",
                    email, ruolo, stato);
        }
        int paziente(String email) throws SQLException {
            int id = utente(email, null, "ATTIVO");
            execute("INSERT INTO paziente (id_utente, codice_fiscale, data_nascita) VALUES (?, ?, '1990-01-01')", id,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
            return id;
        }
        /* Intera tabella UTENTE e collegamenti PAZIENTE: un rifiuto non deve scrivere nulla. */
        List<List<Object>> tabella() throws SQLException {
            var righe = new ArrayList<List<Object>>();
            try (Connection c = factory.getConnection(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT u.*, p.codice_fiscale FROM utente u LEFT JOIN paziente p ON p.id_utente = u.id_utente ORDER BY u.id_utente")) {
                while (r.next()) { var riga = new ArrayList<Object>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) { riga.add(r.getObject(i)); } righe.add(riga); }
            }
            return righe;
        }
        long gestionali() throws SQLException { return scalar("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale IS NOT NULL"); }
        boolean mutexLibero() throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT IS_FREE_LOCK(?)")) {
                s.setString(1, UtenteRepository.accountLockName(c.getCatalog()));
                try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1) == 1; }
            }
        }
    }

    record Riga(String nome, String cognome, String email, String hash, String ruolo, String stato, BigInteger generazione,
                Integer modificatoDa, LocalDateTime modificatoIl, boolean paziente, boolean medico) { }
    static Optional<Riga> riga(Db db, String email) throws SQLException {
        try (Connection c = db.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT nome, cognome, email, password_hash, ruolo_gestionale, "
                + "stato_accesso, generazione_autenticazione, modificato_da, modificato_il, EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente = u.id_utente), "
                + "EXISTS(SELECT 1 FROM medico m WHERE m.id_utente = u.id_utente) FROM utente u WHERE email = ?")) {
            s.setString(1, email);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { return Optional.empty(); }
                return Optional.of(new Riga(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                        r.getObject(7, BigInteger.class), r.getObject(8, Integer.class), r.getObject(9, LocalDateTime.class), r.getBoolean(10), r.getBoolean(11)));
            }
        }
    }

    // ---- observed connections -------------------------------------------------------------------------------------

    /* Chiamato attorno a ogni esecuzione; mutex indica che GET_LOCK è già tornato sulla connessione. */
    @FunctionalInterface interface Gancio { void osserva(String sql, boolean dopo, boolean mutex) throws Exception; }
    static ConnectionFactory osserva(ConnectionFactory delegate, Gancio gancio, boolean commitIncerto) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = delegate.getConnection();
                try (Statement s = c.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 10"); }
                boolean[] mutex = {false};
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    try {
                        if (commitIncerto && m.getName().equals("commit")) { c.commit(); throw new SQLException("Commit simulato: esito non comunicato."); }
                        Object value = m.invoke(c, a);
                        if (!m.getName().equals("prepareStatement")) { return value; }
                        String sql = (String) a[0]; PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (q, n, b) -> {
                            boolean esecuzione = n.getName().equals("executeQuery") || n.getName().equals("executeUpdate");
                            if (esecuzione) { gancio.osserva(sql, false, mutex[0]); }
                            try {
                                Object r = n.invoke(statement, b);
                                if (esecuzione && sql.startsWith("SELECT GET_LOCK")) { mutex[0] = true; }
                                if (esecuzione) { gancio.osserva(sql, true, mutex[0]); }
                                return r;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }
    static void attendi(CountDownLatch l) throws InterruptedException { assertTrue(l.await(10, TimeUnit.SECONDS)); }
    static boolean conteggio(String sql) { return sql.startsWith("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale IS NOT NULL"); }
    static boolean inserimento(String sql) { return sql.startsWith("INSERT INTO utente (nome, cognome, email, password_hash, ruolo_gestionale, stato_accesso"); }

    /* Attesa GET_LOCK osservata come User lock da un'altra connessione dello stesso account. */
    static void attesaMutex(Db db, Future<?> inAttesa) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (Connection c = db.factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE = 'User lock' AND INFO LIKE 'SELECT GET_LOCK%'")) {
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { break; } }
                assertFalse(inAttesa.isDone(), "il secondo scrittore non deve concludersi mentre il primo tiene il mutex");
                assertTrue(System.nanoTime() < limite, "nessuna attesa sul mutex osservata"); Thread.sleep(10);
            }
        }
        assertFalse(inAttesa.isDone());
    }
    static void attesaInnoDb(Db db, Future<?> inAttesa) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (Connection c = db.factory.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.OBJECT_SCHEMA = DATABASE() AND r.OBJECT_NAME = 'utente'""")) {
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { return; } }
                assertFalse(inAttesa.isDone(), "UC-01 non deve concludersi prima del commit del bootstrap");
                assertTrue(System.nanoTime() < limite, "nessuna attesa InnoDB osservata sulla riga utente"); Thread.sleep(10);
            }
        }
    }



    static final class Terminale implements BootstrapService.Terminale {
        final Deque<String> righe; final Deque<char[]> password = new ArrayDeque<>(); final List<char[]> consegnate = new ArrayList<>();
        final StringBuffer output = new StringBuffer();
        Terminale(String nome, String cognome, String email, String p1, String p2, String conferma) {
            righe = new ArrayDeque<>(List.of(nome, cognome, email, conferma));
            password.add(p1.toCharArray()); password.add(p2.toCharArray());
        }
        @Override public String leggiRiga(String richiesta) { return righe.poll(); }
        @Override public char[] leggiPassword(String richiesta) { char[] p = password.poll(); if (p != null) { consegnate.add(p); } return p; }
        @Override public void scrivi(String messaggio) { output.append(messaggio).append('\n'); }
        boolean azzerate() { return consegnate.stream().allMatch(p -> new String(p).chars().allMatch(c -> c == 0)); }
    }
    Terminale terminale(String email, String password, String conferma) { return new Terminale(" Prima ", " Amministratrice ", email, password, password, conferma); }
    BootstrapService service(ConnectionFactory factory) { return new BootstrapService(new UtenteRepository(factory, clock), hasher); }
    static String email() { return "bootstrap-" + UUID.randomUUID() + "@example.invalid"; }
    LocalDateTime ora() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }

    // ---- scenarios ------------------------------------------------------------------------------------------------

    @Test void a_emptyDatabaseGetsTheFirstAdministratorWithSystemTrackingAndNoSession() throws Exception {
        try (Db db = Db.bootstrap()) {
            String email = email();
            /* L'orologio avanza durante GET_LOCK: il timestamp deve essere letto dopo il mutex. */
            LocalDateTime[] primaDelMutex = new LocalDateTime[1];
            ConnectionFactory factory = osserva(db.factory, (sql, dopo, mutex) -> {
                if (sql.startsWith("SELECT GET_LOCK") && !dopo) { primaDelMutex[0] = ora(); }
                if (sql.startsWith("SELECT GET_LOCK") && dopo) { clock.advance(Duration.ofMinutes(7).plusMillis(900)); }
            }, false);
            Terminale t = terminale(" Admin.Prima@Example.INVALID ".replace("Admin.Prima", email.substring(0, email.indexOf('@'))), PASSWORD, BOOTSTRAP);
            assertEquals(BootstrapService.CREATO, service(factory).esegui(t), t.output.toString());
            Riga r = riga(db, email).orElseThrow();
            assertEquals(new Riga("Prima", "Amministratrice", email, r.hash(), "AMMINISTRATORE", "ATTIVO", BigInteger.ZERO, null, ora(), false, false), r);
            assertNotEquals(primaDelMutex[0], r.modificatoIl(), "istante letto dopo il mutex");
            assertTrue(hasher.isSupported(r.hash()) && hasher.verify(PASSWORD.toCharArray(), r.hash()), "PBKDF2 esistente");
            assertEquals(1, db.scalar("SELECT COUNT(*) FROM utente")); assertEquals(1, db.gestionali());
            assertTrue(db.mutexLibero()); assertTrue(t.azzerate());
            String out = t.output.toString();
            assertFalse(out.contains(PASSWORD) || out.contains(r.hash()) || out.contains("INSERT"), out);
            for (String atteso : List.of(BOOTSTRAP, "server_uuid", "Amministratore creato")) { assertTrue(out.contains(atteso), atteso); }
            /* Il bootstrap non crea sessioni: l'amministratore accede normalmente con generazione 0. */
            SessionStore sessions = new SessionStore(Clock.systemUTC(), 10);
            assertEquals(0, sessions.size());
            var auth = new AutenticazioneService(new UtenteRepository(db.factory, clock), hasher, sessions);
            var sessione = auth.login(email, PASSWORD.toCharArray()).orElseThrow();
            assertEquals(0L, sessione.generazione());
            assertEquals(it.medbook.model.RuoloGestionale.AMMINISTRATORE, auth.identity(sessione).orElseThrow().ruoloGestionale());
            /* Gli amministratori successivi si creano soltanto tramite l'API autorizzata. */
            var mapper = new ObjectMapper(); int admin = sessione.idUtente();
            var controller = new UtenteController(new UtenteService(new UtenteRepository(db.factory, clock), hasher), mapper);
            var ex = new AuthenticatedTestExchange("POST", "/api/utenti", mapper.writeValueAsString(Map.of("nome", "Secondo", "cognome", "Admin",
                    "email", email(), "password", PASSWORD, "ruoloGestionale", "AMMINISTRATORE")), admin);
            controller.handle(ex); assertEquals(201, ex.exchange.status);
            // A second bootstrap now refuses.
            assertEquals(BootstrapService.GIA_INIZIALIZZATO, service(db.factory).esegui(terminale(email(), PASSWORD, BOOTSTRAP)));
        }
    }

    @Test void b_patientsDoNotPreventTheBootstrapAndAreLeftUntouched() throws Exception {
        try (Db db = Db.bootstrap()) {
            db.paziente(email()); db.paziente(email()); db.utente(email(), null, "DISABILITATO");
            var prima = db.tabella(); String email = email();
            assertEquals(BootstrapService.CREATO, service(db.factory).esegui(terminale(email, PASSWORD, BOOTSTRAP)));
            var dopo = db.tabella(); assertEquals(prima, dopo.subList(0, prima.size()), "pazienti invariati");
            assertEquals(prima.size() + 1, dopo.size()); assertEquals(1, db.gestionali());
            assertEquals("AMMINISTRATORE", riga(db, email).orElseThrow().ruolo());
        }
    }

    @Test void c_anyManagementAccountEvenDisabledOrNonCanonicalRefusesWithCodeThree() throws Exception {
        String[][] casi = {{"AMMINISTRATORE", "ATTIVO"}, {"OPERATORE_DESK", "ATTIVO"}, {"AMMINISTRATORE", "DISABILITATO"},
                {"OPERATORE_DESK", "DISABILITATO"}, {"amministratore", "ATTIVO"}};
        for (String[] caso : casi) {
            try (Db db = Db.bootstrap()) {
                db.paziente(email()); db.utente(email(), caso[0], caso[1]);
                var prima = db.tabella(); String email = email();
                Terminale t = terminale(email, PASSWORD, BOOTSTRAP);
                assertEquals(BootstrapService.GIA_INIZIALIZZATO, service(db.factory).esegui(t), Arrays.toString(caso));
                assertTrue(t.output.toString().contains("Sistema già inizializzato"));
                /* Il controllo decisivo è nella transazione sotto mutex. */
                var repo = new UtenteRepository(db.factory, clock);
                var e = assertThrows(BootstrapRifiutatoException.class, () -> repo.creaPrimoAmministratore("N", "C", email, hasher.hash(PASSWORD.toCharArray()), repo.destinazione()));
                assertEquals(Motivo.GIA_INIZIALIZZATO, e.motivo());
                assertEquals(prima, db.tabella(), "nessuna scrittura: " + Arrays.toString(caso)); assertTrue(db.mutexLibero());
            }
        }
    }

    @Test void d_emailOfAnExistingPatientIsRefusedWithCodeFourAndNeverPromoted() throws Exception {
        try (Db db = Db.bootstrap()) {
            String email = email(); db.paziente(email); var prima = db.tabella();
            Terminale t = terminale(email.toUpperCase(Locale.ROOT), PASSWORD, BOOTSTRAP);
            assertEquals(BootstrapService.EMAIL_PRESENTE, service(db.factory).esegui(t));
            assertTrue(t.output.toString().contains("Email già presente"));
            assertEquals(prima, db.tabella()); assertEquals(0, db.gestionali()); assertTrue(db.mutexLibero());
            /* Un'altra email resta creabile: il rifiuto non lascia stato parziale. */
            assertEquals(BootstrapService.CREATO, service(db.factory).esegui(terminale(email(), PASSWORD, BOOTSTRAP)));
        }
    }

    @Test void e_twoConcurrentBootstrapsCreateExactlyOneAdministrator() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Db db = Db.bootstrap()) {
            /* Ordine deterministico: A mantiene il mutex dopo il conteggio zero; B attende e poi conta uno. */
            CountDownLatch contato = new CountDownLatch(1), riprendi = new CountDownLatch(1);
            ConnectionFactory primo = osserva(db.factory, (sql, dopo, mutex) -> {
                if (mutex && dopo && conteggio(sql)) { contato.countDown(); attendi(riprendi); }
            }, false);
            String emailA = email(), emailB = email();
            Future<Integer> a = pool.submit(() -> service(primo).esegui(terminale(emailA, PASSWORD, BOOTSTRAP)));
            attendi(contato);
            Future<Integer> b = pool.submit(() -> service(db.factory).esegui(terminale(emailB, PASSWORD, BOOTSTRAP)));
            attesaMutex(db, b);
            riprendi.countDown();
            assertEquals(BootstrapService.CREATO, a.get(20, TimeUnit.SECONDS));
            assertEquals(BootstrapService.GIA_INIZIALIZZATO, b.get(20, TimeUnit.SECONDS), "il secondo rifiuta (codice 3)");
            assertTrue(riga(db, emailA).isPresent()); assertTrue(riga(db, emailB).isEmpty()); assertEquals(1, db.gestionali());

            /* Entrambi superano il conteggio preliminare: decide la transazione. */
            for (int round = 0; round < 5; round++) {
                db.svuota(); CyclicBarrier via = new CyclicBarrier(2);
                var esiti = new ArrayList<Future<Integer>>();
                for (int i = 0; i < 2; i++) {
                    String e = email();
                    esiti.add(pool.submit(() -> { var t = terminale(e, PASSWORD, BOOTSTRAP); via.await(); return service(db.factory).esegui(t); }));
                }
                var codici = new ArrayList<Integer>(); for (var f : esiti) { codici.add(f.get(30, TimeUnit.SECONDS)); }
                Collections.sort(codici);
                assertEquals(List.of(BootstrapService.CREATO, BootstrapService.GIA_INIZIALIZZATO), codici, "round " + round);
                assertEquals(1, db.gestionali()); assertEquals(1, db.scalar("SELECT COUNT(*) FROM utente")); assertTrue(db.mutexLibero());
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void f_bootstrapAndPostUtentiAreSerializedByTheMutexInBothOrders() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var mapper = new ObjectMapper();
        try {
            for (boolean bootstrapPrima : new boolean[]{true, false}) {
                try (Db db = Db.bootstrap()) {
                    int admin = db.utente(email(), "AMMINISTRATORE", "ATTIVO");
                    CountDownLatch fermo = new CountDownLatch(1), riprendi = new CountDownLatch(1);
                    /* Il primo scrittore mantiene il mutex; bootstrap e creazione HTTP si sospendono ai rispettivi lock decisivi. */
                    Gancio pausa = (sql, dopo, mutex) -> {
                        boolean punto = bootstrapPrima ? sql.startsWith("SELECT GET_LOCK") : sql.contains("FOR UPDATE");
                        if (mutex && dopo && punto) { fermo.countDown(); attendi(riprendi); }
                    };
                    var bootstrapRepo = new UtenteRepository(bootstrapPrima ? osserva(db.factory, pausa, false) : db.factory, clock);
                    var controller = new UtenteController(new UtenteService(new UtenteRepository(bootstrapPrima ? db.factory : osserva(db.factory, pausa, false), clock), hasher), mapper);
                    String emailDesk = email(), emailBootstrap = email();
                    var ex = new AuthenticatedTestExchange("POST", "/api/utenti", mapper.writeValueAsString(Map.of("nome", "Nuovo", "cognome", "Desk",
                            "email", emailDesk, "password", PASSWORD, "ruoloGestionale", "OPERATORE_DESK")), admin);
                    var destinazione = bootstrapRepo.destinazione(); String hash = hasher.hash(PASSWORD.toCharArray());
                    Callable<Object> bootstrap = () -> { try { return bootstrapRepo.creaPrimoAmministratore("Boot", "Strap", emailBootstrap, hash, destinazione); } catch (Exception e) { return e; } };
                    Callable<Object> post = () -> { controller.handle(ex); return ex.exchange.status; };
                    Future<Object> primo = pool.submit(bootstrapPrima ? bootstrap : post);
                    attendi(fermo);
                    Future<Object> secondo = pool.submit(bootstrapPrima ? post : bootstrap);
                    attesaMutex(db, secondo);
                    riprendi.countDown();
                    Object esitoBootstrap = (bootstrapPrima ? primo : secondo).get(20, TimeUnit.SECONDS);
                    Object esitoPost = (bootstrapPrima ? secondo : primo).get(20, TimeUnit.SECONDS);
                    assertInstanceOf(BootstrapRifiutatoException.class, esitoBootstrap, "ordine bootstrapPrima=" + bootstrapPrima);
                    assertEquals(Motivo.GIA_INIZIALIZZATO, ((BootstrapRifiutatoException) esitoBootstrap).motivo());
                    assertEquals(201, esitoPost);
                    assertEquals("OPERATORE_DESK", riga(db, emailDesk).orElseThrow().ruolo()); assertTrue(riga(db, emailBootstrap).isEmpty());
                    assertEquals(1, db.scalar("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale = 'AMMINISTRATORE'")); assertTrue(db.mutexLibero());
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void g_bootstrapAndUc01OnTheSameEmailAreDecidedByTheUniqueKey() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (boolean registrazionePrima : new boolean[]{true, false}) {
                try (Db db = Db.bootstrap()) {
                    String email = email(); CountDownLatch fermo = new CountDownLatch(1), riprendi = new CountDownLatch(1);
                    /*
                     * Registrazione prima: bootstrap sospeso prima dell'INSERT. Bootstrap prima:
                     * sospeso dopo INSERT non confermato.
                     */
                    ConnectionFactory factory = osserva(db.factory, (sql, dopo, mutex) -> {
                        if (inserimento(sql) && dopo != registrazionePrima) { fermo.countDown(); attendi(riprendi); }
                    }, false);
                    var repo = new UtenteRepository(factory, clock); var destinazione = repo.destinazione(); String hash = hasher.hash(PASSWORD.toCharArray());
                    Future<Object> bootstrap = pool.submit(() -> { try { return repo.creaPrimoAmministratore("Boot", "Strap", email, hash, destinazione); } catch (Exception e) { return e; } });
                    attendi(fermo);
                    Future<Object> registrazione = pool.submit(() -> { try {
                        return new RegistrazioneRepository(db.factory, clock).registraPaziente("Paziente", "Concorrente", email, hash,
                                UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT), LocalDate.of(1990, 1, 1), null);
                    } catch (Exception e) { return e; } });
                    if (registrazionePrima) { assertInstanceOf(Integer.class, registrazione.get(20, TimeUnit.SECONDS)); }
                    else { attesaInnoDb(db, registrazione); }
                    riprendi.countDown();
                    Object b = bootstrap.get(20, TimeUnit.SECONDS), r = registrazione.get(20, TimeUnit.SECONDS);
                    Riga riga = riga(db, email).orElseThrow();
                    if (registrazionePrima) {
                        assertInstanceOf(BootstrapRifiutatoException.class, b); assertEquals(Motivo.EMAIL_PRESENTE, ((BootstrapRifiutatoException) b).motivo());
                        assertNull(riga.ruolo(), "nessuna promozione del paziente"); assertTrue(riga.paziente()); assertEquals(0, db.gestionali());
                    } else {
                        assertInstanceOf(Integer.class, b); assertInstanceOf(RegistrazioneDuplicataException.class, r);
                        assertEquals("AMMINISTRATORE", riga.ruolo()); assertFalse(riga.paziente());
                    }
                    assertEquals(1, db.scalar("SELECT COUNT(*) FROM utente")); assertTrue(db.mutexLibero());
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void h_aFailureAfterTheInsertRollsBackEverything() throws Exception {
        try (Db db = Db.bootstrap()) {
            db.paziente(email()); var prima = db.tabella(); String email = email();
            ConnectionFactory guasta = osserva(db.factory, (sql, dopo, mutex) -> {
                if (!dopo && sql.startsWith("SELECT ruolo_gestionale, stato_accesso, generazione_autenticazione FROM utente WHERE id_utente")) {
                    throw new SQLException("Guasto simulato dopo l'INSERT.");
                }
            }, false);
            Terminale t = terminale(email, PASSWORD, BOOTSTRAP);
            assertEquals(BootstrapService.ERRORE, service(guasta).esegui(t));
            assertTrue(t.output.toString().contains("Errore interno")); assertFalse(t.output.toString().contains("Guasto simulato"));
            assertEquals(prima, db.tabella(), "rollback: nessun account parziale"); assertTrue(riga(db, email).isEmpty());
            assertEquals(0, db.gestionali()); assertTrue(db.mutexLibero()); assertTrue(t.azzerate());
            assertEquals(BootstrapService.CREATO, service(db.factory).esegui(terminale(email, PASSWORD, BOOTSTRAP)), "ripetibile dopo il rollback");
        }
    }

    @Test void i_anUncertainCommitIsReportedAndRepeatingIsSafe() throws Exception {
        try (Db db = Db.bootstrap()) {
            String email = email();
            Terminale t = terminale(email, PASSWORD, BOOTSTRAP);
            assertEquals(BootstrapService.ERRORE, service(osserva(db.factory, (sql, dopo, mutex) -> { }, true)).esegui(t));
            assertTrue(t.output.toString().contains("Esito incerto: verificare prima di ripetere."), t.output.toString());
            assertTrue(riga(db, email).isPresent(), "il commit era avvenuto"); assertTrue(db.mutexLibero());
            assertEquals(BootstrapService.GIA_INIZIALIZZATO, service(db.factory).esegui(terminale(email(), PASSWORD, BOOTSTRAP)), "ripetere è sicuro");
            assertEquals(1, db.gestionali());
        }
    }

    @Test void j_aDifferentDatabaseIsNeverWritten() throws Exception {
        try (Db db = Db.bootstrap()) {
            var prima = db.tabella();
            for (String conferma : Arrays.asList("medbook", "medbook_test", BOOTSTRAP.toUpperCase(Locale.ROOT), BOOTSTRAP + " ", "", "si")) {
                Terminale t = terminale(email(), PASSWORD, conferma);
                assertEquals(BootstrapService.INPUT_NON_VALIDO, service(db.factory).esegui(t), conferma);
                assertTrue(t.output.toString().contains("Conferma non ricevuta")); assertTrue(t.azzerate());
            }
            var repo = new UtenteRepository(db.factory, clock); var reale = repo.destinazione();
            assertEquals(BOOTSTRAP, reale.catalogo());
            for (var attesa : List.of(new UtenteRepository.DestinazioneDatabase("medbook", reale.hostname(), reale.porta(), reale.serverUuid(), reale.versione()),
                    new UtenteRepository.DestinazioneDatabase(reale.catalogo(), reale.hostname(), reale.porta(), UUID.randomUUID().toString(), reale.versione()))) {
                var e = assertThrows(BootstrapRifiutatoException.class, () -> repo.creaPrimoAmministratore("N", "C", email(), "hash", attesa));
                assertEquals(Motivo.DESTINAZIONE_DIVERSA, e.motivo());
            }
            assertEquals(prima, db.tabella()); assertTrue(db.mutexLibero());
        }
    }

    @Test void k_schemaWithoutMigration003OrIncompleteIsRefusedWithCodeSix() throws Exception {
        try (Db completo = Db.bootstrap()) { assertTrue(new UtenteRepository(completo.factory).schemaAggiornato()); }
        Db db = new Db("MEDBOOK_TEST_PRE003_DB_URL", PRE003, false);
        var repo = new UtenteRepository(db.factory, clock);
        ripristinaPre003(db);
        try {
            long utenti = db.scalar("SELECT COUNT(*) FROM utente");
            Terminale t = terminale(email(), PASSWORD, PRE003);
            assertEquals(BootstrapService.SCHEMA_NON_AGGIORNATO, service(db.factory).esegui(t), t.output.toString());
            assertTrue(t.output.toString().contains("migrazione 003")); assertTrue(t.consegnate.isEmpty(), "nessuna password richiesta");
            /* Schemi parziali costruiti sul catalogo dedicato: ogni stato intermedio deve essere rifiutato. */
            String[] passi = {
                    "ALTER TABLE utente ADD COLUMN stato_accesso VARCHAR(20) NOT NULL DEFAULT 'ATTIVO'",
                    "ALTER TABLE utente ADD COLUMN generazione_autenticazione BIGINT UNSIGNED NOT NULL DEFAULT 0",
                    "ALTER TABLE utente ADD COLUMN modificato_da INT UNSIGNED NULL",
                    "ALTER TABLE utente ADD COLUMN modificato_il DATETIME NULL",
                    "ALTER TABLE utente ADD CONSTRAINT chk_utente_stato_accesso CHECK (stato_accesso COLLATE utf8mb4_0900_bin IN ('ATTIVO','DISABILITATO'))",
                    "ALTER TABLE utente ADD CONSTRAINT fk_utente_modificato_da FOREIGN KEY (modificato_da) REFERENCES utente(id_utente) ON UPDATE RESTRICT ON DELETE SET NULL"};
            for (String passo : passi) {
                assertFalse(repo.schemaAggiornato(), "prima di: " + passo);
                var destinazione = repo.destinazione();
                var e = assertThrows(BootstrapRifiutatoException.class, () -> repo.creaPrimoAmministratore("N", "C", email(), "hash", destinazione));
                assertEquals(Motivo.SCHEMA_NON_AGGIORNATO, e.motivo());
                db.execute(passo);
            }
            assertTrue(repo.schemaAggiornato(), "schema completo");
            // Sono rifiutate anche definizioni incompatibili.
            for (String[] variante : new String[][]{
                    {"ALTER TABLE utente ALTER CHECK chk_utente_stato_accesso NOT ENFORCED", "ALTER TABLE utente ALTER CHECK chk_utente_stato_accesso ENFORCED"},
                    {"ALTER TABLE utente MODIFY modificato_il DATETIME(3) NULL", "ALTER TABLE utente MODIFY modificato_il DATETIME NULL"},
                    {"ALTER TABLE utente MODIFY generazione_autenticazione BIGINT NOT NULL DEFAULT 0", "ALTER TABLE utente MODIFY generazione_autenticazione BIGINT UNSIGNED NOT NULL DEFAULT 0"}}) {
                db.execute(variante[0]); assertFalse(repo.schemaAggiornato(), variante[0]);
                db.execute(variante[1]); assertTrue(repo.schemaAggiornato(), variante[1]);
            }
            assertEquals(utenti, db.scalar("SELECT COUNT(*) FROM utente"), "nessuna scrittura sullo schema non aggiornato");
            assertTrue(db.mutexLibero());
        } finally { ripristinaPre003(db); }
        assertFalse(repo.schemaAggiornato());
    }
    /* Ripristina lo schema senza 003 qualunque sia lo stato lasciato dalla prova precedente. */
    static void ripristinaPre003(Db db) throws SQLException {
        assertEquals(PRE003, db.catalogo());
        if (db.scalar("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_utente_modificato_da'") > 0) {
            db.execute("ALTER TABLE utente DROP FOREIGN KEY fk_utente_modificato_da");
        }
        if (db.scalar("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_utente_stato_accesso'") > 0) {
            db.execute("ALTER TABLE utente DROP CHECK chk_utente_stato_accesso");
        }
        for (String colonna : List.of("modificato_il", "modificato_da", "generazione_autenticazione", "stato_accesso")) {
            if (db.scalar("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND COLUMN_NAME = ?", colonna) > 0) {
                db.execute("ALTER TABLE utente DROP COLUMN " + colonna);
            }
        }
    }

    @Test void l_passwordBoundariesOnTheRealDatabase() throws Exception {
        Map<String, Boolean> casi = new LinkedHashMap<>();
        casi.put("x".repeat(11), false); casi.put("x".repeat(1025), false); casi.put("abcdefghijk\uD800", false); casi.put("abcde\uDE00\uD83Dfghij", false);
        casi.put("x".repeat(12), true); casi.put("y".repeat(1024), true); casi.put("abcdefghij😀", true);
        for (var caso : casi.entrySet()) {
            try (Db db = Db.bootstrap()) {
                String email = email(); Terminale t = terminale(email, caso.getKey(), BOOTSTRAP);
                int esito = service(db.factory).esegui(t);
                assertTrue(t.azzerate());
                if (!caso.getValue()) { assertEquals(BootstrapService.INPUT_NON_VALIDO, esito); assertEquals(0, db.scalar("SELECT COUNT(*) FROM utente")); continue; }
                assertEquals(BootstrapService.CREATO, esito, caso.getKey().length() + " char");
                var auth = new AutenticazioneService(new UtenteRepository(db.factory, clock), hasher, new SessionStore(Clock.systemUTC(), 10));
                assertTrue(auth.login(email, caso.getKey().toCharArray()).isPresent(), "login con la password del bootstrap");
            }
        }
    }

    @Test void m_aBusyMutexIsCodeFiveWithoutWrites() throws Exception {
        try (Db db = Db.bootstrap(); Connection tiene = db.factory.getConnection()) {
            String nome = UtenteRepository.accountLockName(tiene.getCatalog());
            try (PreparedStatement s = tiene.prepareStatement("SELECT GET_LOCK(?, 0)")) { s.setString(1, nome); try (ResultSet r = s.executeQuery()) { r.next(); assertEquals(1, r.getInt(1)); } }
            Terminale t = terminale(email(), PASSWORD, BOOTSTRAP);
            assertEquals(BootstrapService.MUTEX_OCCUPATO, service(db.factory).esegui(t));
            assertTrue(t.output.toString().contains("riprovare")); assertEquals(0, db.scalar("SELECT COUNT(*) FROM utente"));
            try (PreparedStatement s = tiene.prepareStatement("SELECT RELEASE_LOCK(?)")) { s.setString(1, nome); s.executeQuery().close(); }
            assertTrue(db.mutexLibero());
        }
    }
}
