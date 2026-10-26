package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.Prenotazione;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/* Flussi clinici con medico operativo, inattivo o anomalo su MySQL isolato. */
class MedicoInattivoFlussiIntegrationTest {
    static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);
    static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    static final String NON_OPERATIVO = "Medico non operativo.";
    /* Gli ultimi valori sono ammessi dal CHECK ai_ci ma non sono canonici. */
    static final String[] NON_OPERATIVI = {"INATTIVO", "attivo", "Attivo"};
    private static int ore;

    static synchronized LocalDateTime futuro() { return LocalDateTime.of(2031, 3, 1, 0, 0).plusHours(++ore); }
    static void stato(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, int medico, String stato) throws SQLException {
        f.execute("UPDATE medico SET stato=? WHERE id_utente=?", stato, medico);
    }
    static int slot(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, int medico, LocalDateTime start, boolean configurata) throws SQLException {
        int id = f.insert("INSERT INTO disponibilita (id_medico,id_prestazione,id_stanza,data_ora_inizio,data_ora_fine) VALUES (?,?,?,?,?)",
                medico, configurata ? f.prestazione : null, configurata ? f.stanza : null, start, start.plusHours(1));
        f.slots.add(id); return id;
    }
    static List<Object> riga(ConnectionFactory factory, String table, String key, int id) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM " + table + " WHERE " + key + "=?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) {
                List<Object> row = new ArrayList<>(); if (!r.next()) { return row; }
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) { row.add(r.getObject(i)); } return row;
            }
        }
    }
    static int conta(ConnectionFactory factory, String sql, int id) throws SQLException {
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
    static <T extends Throwable> void rifiutato(Class<T> type, Executable action) {
        assertEquals(NON_OPERATIVO, assertThrows(type, action::run).getMessage());
    }
    @FunctionalInterface interface Executable { void run() throws Exception; }

    @Test void uc04ListsOnlySlotsOfExactlyActiveDoctors() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int s = slot(f, f.owner, futuro(), true); var repo = new DisponibilitaRepository(f.factory, CLOCK);
            assertTrue(repo.findPrenotabili(f.owner, null, null, NOW).stream().anyMatch(d -> d.getIdDisponibilita() == s));
            for (String value : NON_OPERATIVI) {
                stato(f, f.owner, value);
                assertTrue(repo.findPrenotabili(f.owner, null, null, NOW).stream().noneMatch(d -> d.getIdDisponibilita() == s), value);
                assertTrue(repo.findPrenotabili(null, null, null, NOW).stream().noneMatch(d -> d.getIdDisponibilita() == s), value);
            }
            stato(f, f.owner, "ATTIVO");
            assertTrue(repo.findPrenotabili(null, f.prestazione, null, NOW).stream().anyMatch(d -> d.getIdDisponibilita() == s));
            assertEquals(1, conta(f.factory, "SELECT COUNT(*) FROM disponibilita WHERE id_disponibilita=?", s), "slot conservato");
        }
    }

    @Test void uc05RejectsNonOperationalHolderWithoutWrites() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            for (String value : NON_OPERATIVI) {
                int s = slot(f, f.owner, futuro(), true); stato(f, f.owner, value); var before = riga(f.factory, "disponibilita", "id_disponibilita", s);
                rifiutato(ConflittoPrenotazioneException.class, () -> f.repository.creaRichiestaAtomically(f.patient, s));
                assertEquals(0, conta(f.factory, "SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita=?", s), value);
                assertEquals(before, riga(f.factory, "disponibilita", "id_disponibilita", s));
            }
            stato(f, f.owner, "ATTIVO"); int s = slot(f, f.owner, futuro(), true);
            Prenotazione p = f.repository.creaRichiestaAtomically(f.patient, s); f.bookings.add(p.getIdPrenotazione());
            assertEquals(StatoPrenotazione.RICHIESTA, p.getStato());
        }
    }

    /* Registra query e autocommit della connessione per verificare l'ordine dei lock. */
    static ConnectionFactory registra(ConnectionFactory delegate, List<String> log) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = delegate.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    try {
                        Object value = m.invoke(c, a);
                        if (!m.getName().equals("prepareStatement")) { return value; }
                        String sql = ((String) a[0]).replaceAll("\\s+", " ").trim(); PreparedStatement s = (PreparedStatement) value;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (q, n, b) -> {
                            if (n.getName().equals("executeQuery")) { log.add((c.getAutoCommit() ? "AUTO " : "TX ") + sql); }
                            try { return n.invoke(s, b); } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }

    @Test void uc05LocksDoctorBeforeSlotAfterAnAutocommitHolderRead() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int s = slot(f, f.owner, futuro(), true); List<String> log = Collections.synchronizedList(new ArrayList<>());
            var p = new PrenotazioneRepository(registra(f.factory, log), CLOCK).creaRichiestaAtomically(f.patient, s); f.bookings.add(p.getIdPrenotazione());
            assertEquals("AUTO SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?", log.get(0));
            int paziente = indice(log, "TX SELECT p.id_utente"), medico = indice(log, "TX SELECT stato FROM medico WHERE id_utente = ? FOR SHARE"),
                slot = indice(log, "TX SELECT id_medico, data_ora_inizio, id_prestazione, id_stanza FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE");
            assertTrue(0 < paziente && paziente < medico && medico < slot, log.toString());
        }
    }
    static int indice(List<String> log, String prefix) {
        for (int i = 0; i < log.size(); i++) { if (log.get(i).startsWith(prefix)) { return i; } }
        throw new AssertionError("query assente: " + prefix + " in " + log);
    }

    @Test void uc05RechecksHolderUnderLock() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int s = slot(f, f.owner, futuro(), true);
            /* Un altro titolare viene confermato tra lettura autocommit e lock; entrambi sono abilitati. */
            var repo = new PrenotazioneRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.startsWith("SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) {
                    f.execute("UPDATE disponibilita SET id_medico=? WHERE id_disponibilita=?", f.effective, s);
                }
            }), CLOCK);
            assertEquals("Titolare della disponibilità modificato.",
                    assertThrows(ConflittoPrenotazioneException.class, () -> repo.creaRichiestaAtomically(f.patient, s)).getMessage());
            assertEquals(0, conta(f.factory, "SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita=?", s));
        }
    }

    @Test void uc05ResponseReflectsConfigurationCommittedDuringTheInitialRead() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int s = slot(f, f.owner, futuro(), false);
            /*
             * La configurazione reale avviene dopo la lettura del titolare: lo snapshot REPEATABLE
             * READ non deve essere precedente.
             */
            var repo = new PrenotazioneRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.startsWith("SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) {
                    new DisponibilitaRepository(f.factory, CLOCK).configuraAtomically(s, f.prestazione, f.stanza);
                }
            }), CLOCK);
            var p = repo.creaRichiestaAtomically(f.patient, s); f.bookings.add(p.getIdPrenotazione());
            assertNotNull(p.getDisponibilita().getPrestazione(), "risposta con lo slot configurato");
            assertEquals(f.prestazione, p.getDisponibilita().getPrestazione().getIdPrestazione());
            assertEquals(f.stanza, p.getDisponibilita().getStanza().getIdStanza());
        }
    }

    @Test void uc05StillPreventsDoubleBookingWhenSerializedOnTheSlot() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int s = slot(f, f.owner, futuro(), true); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
            var first = new PrenotazioneRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.contains("FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) { held.countDown(); await(release); }
            }), CLOCK);
            var second = new PrenotazioneRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory, (sql, id, after) -> {
                if (!after && sql.contains("FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) { attempted.countDown(); }
            }), CLOCK);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<Prenotazione> a = pool.submit(() -> first.creaRichiestaAtomically(f.patient, s)); await(held);
                Future<Prenotazione> b = pool.submit(() -> second.creaRichiestaAtomically(f.patient, s)); await(attempted);
                assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS));
                release.countDown(); f.bookings.add(a.get(10, TimeUnit.SECONDS).getIdPrenotazione());
                var error = assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS)).getCause();
                assertInstanceOf(ConflittoPrenotazioneException.class, error); assertEquals("Disponibilità già occupata.", error.getMessage());
            } finally { release.countDown(); }
            assertEquals(1, conta(f.factory, "SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita=?", s));
        }
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timeout di sincronizzazione"); }
    }

    @Test void uc11ChecksHolderUnderDoctorLock() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int booking = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusDays(3));
            for (String value : NON_OPERATIVI) {
                stato(f, f.owner, value); var before = f.snapshot(booking);
                rifiutato(ConflittoPrenotazioneException.class, () -> f.repository.confermaAtomically(booking, f.operator));
                assertEquals(before, f.snapshot(booking), value);
            }
            stato(f, f.owner, "ATTIVO");
            assertEquals(StatoPrenotazione.CONFERMATA, f.repository.confermaAtomically(booking, f.operator).getStato());
        }
    }

    @Test void uc17RequiresOperationalSubstituteOnly() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int booking = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusDays(3));
            f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(booking));
            for (String value : NON_OPERATIVI) {
                stato(f, f.effective, value); var before = f.snapshot(booking);
                rifiutato(ConflittoPrenotazioneException.class, () -> f.repository.sostituisciMedicoAtomically(
                        booking, f.operator, f.effective, StatoPrenotazione.RICHIESTA, f.owner));
                assertEquals(before, f.snapshot(booking), value);
            }
            /* Il titolare originale può essere non operativo se il nuovo medico effettivo è diverso e attivo. */
            stato(f, f.effective, "ATTIVO"); stato(f, f.owner, "INATTIVO");
            var result = f.repository.sostituisciMedicoAtomically(booking, f.operator, f.effective, StatoPrenotazione.RICHIESTA, f.owner);
            assertEquals(f.effective, result.getIdMedicoEffettivo());
        }
    }

    @Test void uc17RejectsReturnToANonOperationalHolder() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int booking = f.booking(StatoPrenotazione.CONFERMATA, NOW.plusDays(3));
            f.repository.segnalaIndisponibilitaAtomically(f.effective, List.of(booking)); stato(f, f.owner, "INATTIVO"); var before = f.snapshot(booking);
            rifiutato(ConflittoPrenotazioneException.class, () -> f.repository.sostituisciMedicoAtomically(
                    booking, f.operator, f.owner, StatoPrenotazione.CONFERMATA, f.effective));
            assertEquals(before, f.snapshot(booking));
            stato(f, f.owner, "ATTIVO");
            assertEquals(f.owner, f.repository.sostituisciMedicoAtomically(booking, f.operator, f.owner, StatoPrenotazione.CONFERMATA, f.effective).getIdMedicoEffettivo());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"INATTIVO", "attivo", "Attivo"})
    void uc19CreateEditDeleteLoseMedicoPermissionUnderLock(String value) throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            var repo = new DisponibilitaRepository(f.factory, CLOCK); int s = slot(f, f.owner, futuro(), false); LocalDateTime start = futuro();
            int slots = conta(f.factory, "SELECT COUNT(*) FROM disponibilita WHERE id_medico=?", f.owner);
            var before = riga(f.factory, "disponibilita", "id_disponibilita", s); stato(f, f.owner, value);
            rifiutato(OperazioneNonAutorizzataException.class, () -> repo.save(f.owner, start, start.plusHours(1)));
            rifiutato(OperazioneNonAutorizzataException.class, () -> repo.modificaOrarioAtomically(s, f.owner, start, start.plusHours(1)));
            rifiutato(OperazioneNonAutorizzataException.class, () -> repo.eliminaAtomically(s, f.owner));
            assertEquals(slots, conta(f.factory, "SELECT COUNT(*) FROM disponibilita WHERE id_medico=?", f.owner));
            assertEquals(before, riga(f.factory, "disponibilita", "id_disponibilita", s));
            stato(f, f.owner, "ATTIVO");
            f.slots.add(repo.save(f.owner, start, start.plusHours(1)).getIdDisponibilita());
            LocalDateTime other = futuro(); repo.modificaOrarioAtomically(s, f.owner, other, other.plusHours(1)); repo.eliminaAtomically(s, f.owner);
            assertEquals(0, conta(f.factory, "SELECT COUNT(*) FROM disponibilita WHERE id_disponibilita=?", s));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"INATTIVO", "attivo", "Attivo"})
    void uc22RejectsNonOperationalEffectiveDoctorBeforeCommit(String value) throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int booking = f.booking(StatoPrenotazione.EFFETTUATA, NOW.minusDays(2)); var repo = new RefertoRepository(f.factory, CLOCK);
            try {
                stato(f, f.effective, value); var before = f.snapshot(booking); var outcome = new RefertoRepository.Outcome();
                rifiutato(OperazioneNonAutorizzataException.class, () -> repo.saveAtomically(booking, f.effective, "documents/" + UUID.randomUUID() + "/document.pdf", "referto.pdf", outcome));
                assertTrue(outcome.safeToDelete(), "file pubblicato scartabile: commit non tentato");
                assertEquals(0, conta(f.factory, "SELECT COUNT(*) FROM referto WHERE id_prenotazione=?", booking)); assertEquals(before, f.snapshot(booking));
                stato(f, f.effective, "ATTIVO");
                assertTrue(repo.saveAtomically(booking, f.effective, "documents/" + UUID.randomUUID() + "/document.pdf", "referto.pdf", new RefertoRepository.Outcome()).getIdReferto() > 0);
            } finally { f.execute("DELETE FROM referto WHERE id_prenotazione=?", booking); }
        }
    }

    @Test void uc27RejectsConfigurationOfNonOperationalHolder() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            var repo = new DisponibilitaRepository(f.factory, CLOCK); int s = slot(f, f.owner, futuro(), false);
            for (String value : NON_OPERATIVI) {
                stato(f, f.owner, value); var before = riga(f.factory, "disponibilita", "id_disponibilita", s);
                rifiutato(ConflittoConfigurazioneException.class, () -> repo.configuraAtomically(s, f.prestazione, f.stanza));
                assertEquals(before, riga(f.factory, "disponibilita", "id_disponibilita", s), value);
            }
            stato(f, f.owner, "ATTIVO");
            assertEquals(f.stanza, repo.configuraAtomically(s, f.prestazione, f.stanza).getStanza().getIdStanza());
        }
    }
}
