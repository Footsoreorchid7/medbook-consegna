package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.RegistrazioneDuplicataException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Concorrenza tra modifica email e registrazione paziente. La registrazione non acquisisce il mutex
 * degli account: serializza le scritture il vincolo UNIQUE sull'email della riga non confermata. Le
 * attese sono osservate in performance_schema; servono SELECT su data_locks e data_lock_waits.
 */
class MedicoEmailConcorrenzaIntegrationTest {
    static final int ROUNDS = MedicoConcorrenzaIntegrationTest.ROUNDS;
    static final String HASH = MedicoConcorrenzaIntegrationTest.HASH;
    static final String INDICE_EMAIL = "uq_utente_email";
    static final long OSSERVAZIONE_SECONDI = 5;

    @FunctionalInterface interface Azione { void run() throws Exception; }

    /*
     * Sospende o segnala executeUpdate per il prefisso SQL indicato e registra CONNECTION_ID. Le
     * attese InnoDB sono limitate a 10 secondi.
     */
    static ConnectionFactory writer(ConnectionFactory delegate, String prefisso, AtomicLong sessione, Azione prima, Azione dopo) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = delegate.getConnection();
                long id;
                try (Statement s = c.createStatement()) {
                    s.execute("SET SESSION innodb_lock_wait_timeout=10");
                    try (ResultSet r = s.executeQuery("SELECT CONNECTION_ID()")) { r.next(); id = r.getLong(1); }
                } catch (SQLException e) { c.close(); throw e; }
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    try {
                        Object value = m.invoke(c, a);
                        if (!m.getName().equals("prepareStatement")) { return value; }
                        String sql = ((String) a[0]).replaceAll("\\s+", " ").trim(); PreparedStatement s = (PreparedStatement) value;
                        if (!sql.startsWith(prefisso)) { return s; }
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (q, n, b) -> {
                            boolean update = n.getName().equals("executeUpdate");
                            if (update) { sessione.set(id); prima.run(); }
                            try { Object r = n.invoke(s, b); if (update) { dopo.run(); } return r; }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }

    @ParameterizedTest(name = "F5 prima: {0}") @ValueSource(booleans = {true, false})
    void emailUpdateAgainstUc01Registration(boolean f5Prima) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                int admin = f.user(); f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", admin);
                int medico = f.doctor();
                List<String> prima = anagrafica(f.factory, medico);
                String email = "f9e1-" + UUID.randomUUID() + "@example.invalid", cf = MedicoConcorrenzaIntegrationTest.cf();
                String primoSql = f5Prima ? "UPDATE utente" : "INSERT INTO utente", secondoSql = f5Prima ? "INSERT INTO utente" : "UPDATE utente";
                var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
                var sessionePrimo = new AtomicLong(); var sessioneSecondo = new AtomicLong();
                ConnectionFactory primo = writer(f.factory, primoSql, sessionePrimo, () -> { },
                        () -> { held.countDown(); MedicoConcorrenzaIntegrationTest.await(release); });
                ConnectionFactory secondo = writer(f.factory, secondoSql, sessioneSecondo, attempted::countDown, () -> { });
                Callable<Object> aggiorna = () -> new MedicoRepository(f5Prima ? primo : secondo).aggiornaDatiPersonali(admin, medico, "Nuovo", "Cognome", email);
                Callable<Object> registra = () -> new RegistrazioneRepository(f5Prima ? secondo : primo)
                        .registraPaziente("P", "Q", email, HASH, cf, LocalDate.of(1990, 1, 1), null);
                Object vincitore; Throwable perdente;
                try (var pool = Executors.newFixedThreadPool(2)) {
                    try {
                        Future<Object> a = pool.submit(f5Prima ? aggiorna : registra); MedicoConcorrenzaIntegrationTest.await(held);
                        Future<Object> b = pool.submit(f5Prima ? registra : aggiorna); MedicoConcorrenzaIntegrationTest.await(attempted);
                        assertNotEquals(sessionePrimo.get(), sessioneSecondo.get(), "sessioni MySQL distinte");
                        attesaSullaChiaveUnique(f.factory, sessioneSecondo.get(), sessionePrimo.get(), email, b);
                        /* La scrittura è eseguita ma non confermata: le altre sessioni non la vedono. */
                        assertEquals(0, MedicoConcorrenzaIntegrationTest.contaEmail(f, email), "primo writer non ancora confermato");
                        assertEquals(prima, anagrafica(f.factory, medico), "anagrafica del medico non ancora modificata");
                        release.countDown();
                        vincitore = MedicoConcorrenzaIntegrationTest.ok(a);
                        if (!f5Prima) { f.users.add((Integer) vincitore); }
                        perdente = MedicoConcorrenzaIntegrationTest.cause(assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS)));
                    } finally { release.countDown(); } /* Prima che close() dell'esecutore attenda entrambi gli scrittori. */
                }
                nessunDeadlockNeTimeout(perdente);
                assertEquals(1062, primaSqlException(perdente).getErrorCode(), "il perdente vede soltanto il duplicato");
                assertEquals(1, MedicoConcorrenzaIntegrationTest.contaEmail(f, email), "una sola riga con quell'email");
                if (f5Prima) {
                    assertInstanceOf(RegistrazioneDuplicataException.class, perdente);
                    assertEquals(List.of("Nuovo", "Cognome", email), anagrafica(f.factory, medico), "aggiornamento F5 confermato");
                    assertEquals(0, conta(f.factory, "SELECT COUNT(*) FROM paziente WHERE codice_fiscale=?", cf), "UC-01 interamente annullata");
                } else {
                    assertInstanceOf(ConflittoMedicoException.class, perdente);
                    assertEquals(MedicoRepository.EMAIL_DUPLICATA, perdente.getMessage());
                    assertEquals(prima, anagrafica(f.factory, medico), "rollback F5: nome, cognome ed email invariati");
                    int paziente = (Integer) vincitore;
                    assertEquals(1, conta(f.factory, "SELECT COUNT(*) FROM paziente WHERE codice_fiscale=? AND id_utente=" + paziente, cf));
                    assertEquals(1, conta(f.factory, "SELECT COUNT(*) FROM utente WHERE email=? AND id_utente=" + paziente, email));
                }
                assertTrue(mutexLibero(f.factory), "mutex rilasciato");
            }
        }
    }

    /*
     * Interroga performance_schema ogni 10 ms, entro un limite temporale, finché il lock RECORD su
     * uq_utente_email risulta in attesa del lock concesso alla transazione bloccante.
     */
    static void attesaSullaChiaveUnique(ConnectionFactory factory, long inAttesa, long bloccante, String email, Future<?> secondo) throws Exception {
        String sql = """
                SELECT r.OBJECT_SCHEMA, r.OBJECT_NAME, r.INDEX_NAME, r.LOCK_TYPE, r.LOCK_MODE, r.LOCK_STATUS, r.LOCK_DATA,
                       b.OBJECT_SCHEMA, b.OBJECT_NAME, b.INDEX_NAME, b.LOCK_TYPE, b.LOCK_MODE, b.LOCK_STATUS, b.LOCK_DATA
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
                WHERE w.ENGINE = 'INNODB' AND w.REQUESTING_THREAD_ID = PS_THREAD_ID(?)
                  AND w.REQUESTING_ENGINE_TRANSACTION_ID <> w.BLOCKING_ENGINE_TRANSACTION_ID
                  AND w.BLOCKING_ENGINE_TRANSACTION_ID IN
                      (SELECT h.ENGINE_TRANSACTION_ID FROM performance_schema.data_locks h WHERE h.THREAD_ID = PS_THREAD_ID(?))
                  AND w.REQUESTING_ENGINE_TRANSACTION_ID NOT IN
                      (SELECT h.ENGINE_TRANSACTION_ID FROM performance_schema.data_locks h WHERE h.THREAD_ID = PS_THREAD_ID(?))""";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OSSERVAZIONE_SECONDI);
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            String catalogo = c.getCatalog(); List<String> osservate = new ArrayList<>();
            while (true) {
                s.setLong(1, inAttesa); s.setLong(2, bloccante); s.setLong(3, bloccante);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        List<String> row = new ArrayList<>(); for (int i = 1; i <= 14; i++) { row.add(r.getString(i)); }
                        if (chiaveEmail(row.subList(0, 7), catalogo, email, "WAITING") && chiaveEmail(row.subList(7, 14), catalogo, email, "GRANTED")) { return; }
                        osservate.add(row.toString());
                    }
                } catch (SQLException e) {
                    throw new AssertionError("performance_schema non leggibile: concedere all'utente di test SELECT su "
                            + "performance_schema.data_locks e performance_schema.data_lock_waits", e);
                }
                if (secondo.isDone() || System.nanoTime() > deadline) {
                    fail("Nessuna attesa InnoDB della sessione " + inAttesa + " sulla chiave " + INDICE_EMAIL + " bloccata dalla sessione "
                            + bloccante + " entro " + OSSERVAZIONE_SECONDI + " s (secondo writer terminato: " + secondo.isDone() + "). "
                            + "Attese osservate tra le due sessioni: " + osservate + ". Lock correnti: " + dump(c, inAttesa, bloccante));
                }
                Thread.sleep(10); /* L'intervallo serve al polling: decide il lock osservato, non il tempo trascorso. */
            }
        }
    }
    /** OBJECT_SCHEMA, OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA of one lock. */
    static boolean chiaveEmail(List<String> lock, String catalogo, String email, String stato) {
        return catalogo.equals(lock.get(0)) && "utente".equals(lock.get(1)) && INDICE_EMAIL.equals(lock.get(2))
                && "RECORD".equals(lock.get(3)) && stato.equals(lock.get(5)) && lock.get(6) != null && lock.get(6).contains(email);
    }
    static List<String> dump(Connection c, long inAttesa, long bloccante) {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT 'lock', THREAD_ID, ENGINE_TRANSACTION_ID, OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
                FROM performance_schema.data_locks WHERE THREAD_ID IN (PS_THREAD_ID(?), PS_THREAD_ID(?))
                UNION ALL
                SELECT 'wait', REQUESTING_THREAD_ID, REQUESTING_ENGINE_TRANSACTION_ID, BLOCKING_THREAD_ID,
                       BLOCKING_ENGINE_TRANSACTION_ID, NULL, NULL, NULL, NULL
                FROM performance_schema.data_lock_waits""")) {
            s.setLong(1, inAttesa); s.setLong(2, bloccante); List<String> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) { List<String> row = new ArrayList<>(); for (int i = 1; i <= 9; i++) { row.add(r.getString(i)); } result.add(row.toString()); }
            }
            return result;
        } catch (SQLException e) { return List.of("dump non disponibile: " + e.getMessage()); }
    }

    static List<String> anagrafica(ConnectionFactory factory, int id) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT nome,cognome,email FROM utente WHERE id_utente=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return List.of(r.getString(1), r.getString(2), r.getString(3)); }
        }
    }
    static int conta(ConnectionFactory factory, String sql, String value) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, value);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
    static boolean mutexLibero(ConnectionFactory factory) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT IS_FREE_LOCK(?)")) {
            s.setString(1, AccountMutex.lockName(c.getCatalog()));
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1) == 1; }
        }
    }
    /* Esamina cause, eccezioni soppresse e concatenate: nessun deadlock 1213 o timeout 1205. */
    static void nessunDeadlockNeTimeout(Throwable t) {
        var visti = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()); var coda = new ArrayDeque<Throwable>(); coda.add(t);
        while (!coda.isEmpty()) {
            Throwable x = coda.poll(); if (!visti.add(x)) { continue; }
            if (x instanceof SQLException e) {
                assertNotEquals(1213, e.getErrorCode(), "deadlock"); assertNotEquals(1205, e.getErrorCode(), "lock wait timeout");
                if (e.getNextException() != null) { coda.add(e.getNextException()); }
            }
            if (x.getCause() != null) { coda.add(x.getCause()); }
            coda.addAll(Arrays.asList(x.getSuppressed()));
        }
    }
    static SQLException primaSqlException(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) { if (x instanceof SQLException e) { return e; } }
        throw new AssertionError("nessuna SQLException nella catena", t);
    }
}
