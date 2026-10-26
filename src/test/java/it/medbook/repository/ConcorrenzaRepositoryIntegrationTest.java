package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Lock reali sincronizzati mediante latch, esclusivamente su database di test verificato. */
class ConcorrenzaRepositoryIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timeout sincronizzazione controllata");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIG_DELETE_FIRST", "CONFIG_FIRST", "CONFIRM_DELETE_FIRST", "CONFIRM_FIRST",
            "CONFIG_EDIT_FIRST", "CONFIG_FIRST_EDIT", "CONFIRM_EDIT_FIRST", "CONFIRM_FIRST_EDIT"})
    void doctorMutexPreventsSlotForeignKeyInversionInBothOrders(String mode) throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            boolean confirm = mode.startsWith("CONFIRM"), mutationFirst = mode.contains("DELETE_FIRST") || mode.contains("EDIT_FIRST");
            boolean edit = mode.contains("EDIT");
            int booking = confirm ? f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1)) : 0;
            int slot;
            if (confirm) { slot = f.slots.getFirst(); }
            else {
                slot = f.insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",
                        f.owner, NOW.plusHours(1), NOW.plusHours(2)); f.slots.add(slot);
            }
            CountDownLatch firstLocked = new CountDownLatch(1), secondAttempting = new CountDownLatch(1), release = new CountDownLatch(1);
            ConnectionFactory first = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.contains("FROM medico WHERE") && sql.contains("FOR ")) {
                    firstLocked.countDown(); await(release);
                }
            });
            ConnectionFactory second = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (!after && sql.contains("FROM medico WHERE") && sql.contains("FOR ")) { secondAttempting.countDown(); }
            });
            Callable<Object> mutation = () -> {
                var repository = new DisponibilitaRepository(mutationFirst ? first : second, CLOCK);
                if (edit) { return repository.modificaOrarioAtomically(slot, f.owner, NOW.plusHours(3), NOW.plusHours(4)); }
                repository.eliminaAtomically(slot, f.owner); return null;
            };
            Callable<Object> operation = () -> confirm
                    ? new PrenotazioneRepository(mutationFirst ? second : first, CLOCK).confermaAtomically(booking, f.operator)
                    : new DisponibilitaRepository(mutationFirst ? second : first, CLOCK).configuraAtomically(slot, f.prestazione, f.stanza);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Object> leading = workers.submit(mutationFirst ? mutation : operation);
                await(firstLocked);
                Future<Object> following = workers.submit(mutationFirst ? operation : mutation);
                await(secondAttempting); assertFalse(following.isDone()); release.countDown();
                if (confirm || !mutationFirst) {
                    Future<Object> mutating = mutationFirst ? leading : following;
                    Future<Object> operating = mutationFirst ? following : leading;
                    assertInstanceOf(ConflittoDisponibilitaException.class,
                            assertThrows(ExecutionException.class, () -> mutating.get(15, TimeUnit.SECONDS)).getCause());
                    assertNotNull(operating.get(15, TimeUnit.SECONDS));
                    if (confirm) {
                        assertEquals(StatoPrenotazione.CONFERMATA, f.repository.findAgenda(f.owner, NOW.toLocalDate(), NOW.toLocalDate()).getFirst().getStato());
                    }
                } else {
                    leading.get(15, TimeUnit.SECONDS);
                    if (edit) { following.get(15, TimeUnit.SECONDS); }
                    else { assertInstanceOf(RisorsaNonTrovataException.class,
                            assertThrows(ExecutionException.class, () -> following.get(15, TimeUnit.SECONDS)).getCause()); }
                }
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS)); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingRoomConfigurationSeesCommittedAssignmentAndAllowsOnlyAdjacentPeriods(boolean adjacent) throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a = f.insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",
                    f.owner, NOW.plusHours(1), NOW.plusHours(2)); f.slots.add(a);
            int b = f.insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",
                    f.effective, NOW.plusMinutes(adjacent ? 120 : 90), NOW.plusMinutes(adjacent ? 180 : 150)); f.slots.add(b);
            CountDownLatch updateDone = new CountDownLatch(1), secondWaiting = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean discoveryDone = new AtomicBoolean();
            ConnectionFactory first = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                // findById dopo UPDATE, ma prima del commit: la seconda operazione deve aspettare la stanza.
                if (after && sql.contains("WHERE d.id_disponibilita = ?")) { updateDone.countDown(); await(release); }
            });
            ConnectionFactory second = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.equals("SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) { discoveryDone.set(true); }
                if (!after && sql.contains("FROM stanza") && sql.contains("FOR UPDATE")) { secondWaiting.countDown(); }
            });
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> one = workers.submit(() -> new DisponibilitaRepository(first, CLOCK).configuraAtomically(a, f.prestazione, f.stanza));
                await(updateDone);
                Future<?> two = workers.submit(() -> new DisponibilitaRepository(second, CLOCK).configuraAtomically(b, f.prestazione, f.stanza));
                await(secondWaiting); assertTrue(discoveryDone.get()); assertFalse(two.isDone()); release.countDown(); one.get(15, TimeUnit.SECONDS);
                if (adjacent) { two.get(15, TimeUnit.SECONDS); }
                else { assertInstanceOf(ConflittoConfigurazioneException.class,
                        assertThrows(ExecutionException.class, () -> two.get(15, TimeUnit.SECONDS)).getCause()); }
                var slots = new DisponibilitaRepository(f.factory);
                assertTrue(slots.findById(a).orElseThrow().isConfigurata());
                assertEquals(adjacent, slots.findById(b).orElseThrow().isConfigurata());
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS)); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILURE", "RESTORE_ISOLATION", "RESTORE_AUTO"})
    void configurationUsesReadCommittedAndRestoresOrClosesUnhealthyConnection(String mode) throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int slot = f.insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",
                    f.owner, NOW.plusHours(1), NOW.plusHours(2)); f.slots.add(slot);
            AtomicBoolean completed = new AtomicBoolean(), closed = new AtomicBoolean();
            AtomicInteger isolationOnClose = new AtomicInteger(), isolationDuringTransaction = new AtomicInteger();
            AtomicBoolean autoOnClose = new AtomicBoolean();
            ConnectionFactory observed = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection connection = f.factory.getConnection();
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, args) -> {
                        if (m.getName().equals("prepareStatement")) { isolationDuringTransaction.set(connection.getTransactionIsolation()); }
                        if (completed.get() && ((mode.equals("RESTORE_ISOLATION") && m.getName().equals("setTransactionIsolation"))
                                || (mode.equals("RESTORE_AUTO") && m.getName().equals("setAutoCommit")))) {
                            throw new SQLException("Ripristino simulato fallito");
                        }
                        if (m.getName().equals("close")) {
                            isolationOnClose.set(connection.getTransactionIsolation()); autoOnClose.set(connection.getAutoCommit()); closed.set(true);
                        }
                        try {
                            Object result = m.invoke(connection, args);
                            if (m.getName().equals("commit") || m.getName().equals("rollback")) { completed.set(true); }
                            return result;
                        } catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                }
            };
            var repository = new DisponibilitaRepository(observed, CLOCK);
            if (mode.equals("FAILURE")) {
                assertThrows(RisorsaNonTrovataException.class, () -> repository.configuraAtomically(slot, f.prestazione, Integer.MAX_VALUE));
            } else if (mode.startsWith("RESTORE")) {
                assertThrows(SQLException.class, () -> repository.configuraAtomically(slot, f.prestazione, f.stanza));
            } else { repository.configuraAtomically(slot, f.prestazione, f.stanza); }
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolationDuringTransaction.get()); assertTrue(closed.get());
            if (!mode.startsWith("RESTORE")) { assertEquals(Connection.TRANSACTION_REPEATABLE_READ, isolationOnClose.get()); assertTrue(autoOnClose.get()); }
            assertEquals(!mode.equals("FAILURE"), new DisponibilitaRepository(f.factory).findById(slot).orElseThrow().isConfigurata());
        }
    }
}
