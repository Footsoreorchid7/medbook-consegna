package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/* Concorrenza su email e medico, nei due ordini, con tre ripetizioni. */
class MedicoConcorrenzaIntegrationTest {
    static final int ROUNDS = 3;
    static final String HASH = "synthetic-test-hash";

    @FunctionalInterface interface UpdateHook { void on(String sql, boolean after) throws Exception; }
    /* Osserva executeUpdate: il controllo delle sole query non intercetta gli inserimenti. */
    static ConnectionFactory hookUpdates(ConnectionFactory delegate, UpdateHook hook) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = delegate.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    try {
                        Object value = m.invoke(c, a);
                        if (!m.getName().equals("prepareStatement")) { return value; }
                        String sql = ((String) a[0]).replaceAll("\\s+", " ").trim(); PreparedStatement s = (PreparedStatement) value;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (q, n, b) -> {
                            boolean update = n.getName().equals("executeUpdate");
                            if (update) { hook.on(sql, false); }
                            try { Object r = n.invoke(s, b); if (update) { hook.on(sql, true); } return r; }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }
    static Throwable cause(ExecutionException e) { return MedicoInattivoConcorrenzaIntegrationTest.cause(e); }
    static <T> T ok(Future<T> f) throws Exception {
        try { return f.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { throw new AssertionError(cause(e)); }
    }
    static void await(CountDownLatch l) throws InterruptedException { MedicoInattivoFlussiIntegrationTest.await(l); }
    static String cf() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT); }

    /* Creazione medico contro account interno con stessa email: serializza AccountMutex. */
    @ParameterizedTest(name = "F5 prima: {0}") @ValueSource(booleans = {true, false})
    void sameEmailAgainstUc26(boolean f5Prima) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                int admin = f.user(); f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", admin);
                String email = "f5c-" + UUID.randomUUID() + "@example.invalid";
                var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
                ConnectionFactory primo = AccountInternoRepositoryIntegrationTest.observe(f.factory, () -> { }, () -> { held.countDown(); await(release); });
                ConnectionFactory secondo = AccountInternoRepositoryIntegrationTest.observe(f.factory, attempted::countDown, () -> { });
                Callable<Integer> medico = () -> new MedicoRepository(f5Prima ? primo : secondo).crea(admin, "N", "C", email, HASH, "S", "F5C-" + UUID.randomUUID()).idUtente();
                Callable<Integer> interno = () -> new UtenteRepository(f5Prima ? secondo : primo).createInterno(admin, "N", "C", email, HASH, RuoloGestionale.OPERATORE_DESK).idUtente();
                try (var pool = Executors.newFixedThreadPool(2)) {
                    Future<Integer> a = pool.submit(f5Prima ? medico : interno); await(held);
                    Future<Integer> b = pool.submit(f5Prima ? interno : medico); await(attempted);
                    assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS), "il secondo attende il mutex");
                    release.countDown(); f.users.add(ok(a));
                    Throwable e = cause(assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS)));
                    if (f5Prima) { assertInstanceOf(UtenteRepository.ConflittoAccountException.class, e); }
                    else { assertInstanceOf(ConflittoMedicoException.class, e); assertEquals(MedicoRepository.EMAIL_DUPLICATA, e.getMessage()); }
                } finally { release.countDown(); }
                assertEquals(1, contaEmail(f, email), "una sola riga con quell'email");
            }
        }
    }
    static int contaEmail(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, String email) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM utente WHERE email=?")) {
            s.setString(1, email); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }

    /* Creazione medico contro registrazione senza mutex: serializza il vincolo UNIQUE sull'email non confermata. */
    @ParameterizedTest(name = "F5 prima: {0}") @ValueSource(booleans = {true, false})
    void sameEmailAgainstUc01Registration(boolean f5Prima) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                int admin = f.user(); f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", admin);
                String email = "f5r-" + UUID.randomUUID() + "@example.invalid";
                var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
                UpdateHook pausa = (sql, after) -> { if (after && sql.startsWith("INSERT INTO utente")) { held.countDown(); await(release); } };
                UpdateHook tenta = (sql, after) -> { if (!after && sql.startsWith("INSERT INTO utente")) { attempted.countDown(); } };
                ConnectionFactory primo = hookUpdates(f.factory, pausa), secondo = hookUpdates(f.factory, tenta);
                Callable<Integer> medico = () -> new MedicoRepository(f5Prima ? primo : secondo).crea(admin, "N", "C", email, HASH, "S", "F5R-" + UUID.randomUUID()).idUtente();
                Callable<Integer> paziente = () -> new RegistrazioneRepository(f5Prima ? secondo : primo).registraPaziente("P", "Q", email, HASH, cf(), LocalDate.of(1990, 1, 1), null);
                try (var pool = Executors.newFixedThreadPool(2)) {
                    Future<Integer> a = pool.submit(f5Prima ? medico : paziente); await(held);
                    Future<Integer> b = pool.submit(f5Prima ? paziente : medico); await(attempted);
                    assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS), "il secondo attende la chiave UNIQUE non confermata");
                    release.countDown(); f.users.add(ok(a));
                    Throwable e = cause(assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS)));
                    if (f5Prima) { assertInstanceOf(RegistrazioneDuplicataException.class, e); }
                    else { assertInstanceOf(ConflittoMedicoException.class, e); assertEquals(MedicoRepository.EMAIL_DUPLICATA, e.getMessage()); }
                } finally { release.countDown(); }
                assertEquals(1, contaEmail(f, email));
            }
        }
    }

    /* Modifica professionale contro conferma e configurazione sullo stesso lock medico. */
    @ParameterizedTest(name = "{0}") @ValueSource(strings = {"UC11-F5_PRIMA", "UC11-CLINICO_PRIMA", "UC27-F5_PRIMA", "UC27-CLINICO_PRIMA"})
    void professionalUpdateAgainstClinicalFlows(String caso) throws Exception {
        boolean uc11 = caso.startsWith("UC11"), f5Prima = caso.endsWith("F5_PRIMA");
        for (int round = 0; round < ROUNDS; round++) {
            try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                int admin = f.user(); f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", admin);
                int booking = uc11 ? f.booking(StatoPrenotazione.RICHIESTA, MedicoInattivoFlussiIntegrationTest.NOW.plusDays(3)) : 0;
                int slot = uc11 ? 0 : MedicoInattivoFlussiIntegrationTest.slot(f, f.owner, MedicoInattivoFlussiIntegrationTest.futuro(), false);
                var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
                ConnectionFactory primo = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                    if (after && MedicoInattivoConcorrenzaIntegrationTest.doctorLock(sql) && id == f.owner) { held.countDown(); await(release); } });
                ConnectionFactory secondo = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                    if (!after && MedicoInattivoConcorrenzaIntegrationTest.doctorLock(sql) && id == f.owner) { attempted.countDown(); } });
                String numero = "F5K-" + UUID.randomUUID();
                Callable<Object> anagrafica = () -> new MedicoRepository(f5Prima ? primo : secondo).aggiornaDatiProfessionali(admin, f.owner, "Nuova", numero);
                Callable<Object> clinico = () -> uc11
                        ? new PrenotazioneRepository(f5Prima ? secondo : primo, MedicoInattivoFlussiIntegrationTest.CLOCK).confermaAtomically(booking, f.operator)
                        : new DisponibilitaRepository(f5Prima ? secondo : primo).configuraAtomically(slot, f.prestazione, f.stanza);
                try (var pool = Executors.newFixedThreadPool(2)) {
                    Future<Object> a = pool.submit(f5Prima ? anagrafica : clinico); await(held);
                    Future<Object> b = pool.submit(f5Prima ? clinico : anagrafica); await(attempted);
                    assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS), "il secondo attende il lock del medico");
                    release.countDown(); ok(a); ok(b);
                } finally { release.countDown(); }
                assertEquals(numero, MedicoInattivoFlussiIntegrationTest.riga(f.factory, "medico", "id_utente", f.owner).get(2));
                if (uc11) { assertEquals("CONFERMATA", f.snapshot(booking).get(5)); }
                else { assertEquals(f.stanza, ((Number) MedicoInattivoFlussiIntegrationTest.riga(f.factory, "disponibilita", "id_disponibilita", slot).get(5)).intValue()); }
            }
        }
    }
}
