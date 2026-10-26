package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** DML soltanto nel database di test esplicitamente configurato, con fixture e pulizia isolate. */
class PrenotazioneIndisponibilitaIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void reportsOnlyFlagWithCorrectCompetenceAndKeepsSlotOccupied(StatoPrenotazione state) throws Exception {
        try (Fixture f = new Fixture()) {
            int booking = f.booking(state, NOW.plusHours(1));
            int doctor = state == StatoPrenotazione.RICHIESTA ? f.owner : f.effective;
            List<Object> before = f.snapshot(booking);
            assertFalse(f.flag(booking));
            f.repository.segnalaIndisponibilitaAtomically(doctor, List.of(booking));
            List<Object> after = f.snapshot(booking);
            for (int i = 0; i < before.size(); i++) {
                if (i != f.flagColumn) { assertEquals(before.get(i), after.get(i)); }
            }
            assertTrue(f.flag(booking));
            f.repository.segnalaIndisponibilitaAtomically(doctor, List.of(booking));
            assertEquals(after, f.snapshot(booking));
            assertTrue(new DisponibilitaRepository(f.factory).findPrenotabili(f.owner, null, null, NOW).isEmpty());
            var agenda = f.repository.findAgenda(doctor, NOW.toLocalDate(), NOW.toLocalDate());
            assertEquals(1, agenda.size()); assertTrue(agenda.getFirst().isIndisponibilitaSegnalata());
            var desk = f.repository.findSegnalazioniPendenti().stream()
                    .filter(row -> row.getIdPrenotazione() == booking).toList();
            assertEquals(1, desk.size());
            assertEquals(f.owner, desk.getFirst().getIdMedicoTitolare());
            assertEquals(state == StatoPrenotazione.RICHIESTA ? null : f.effective, desk.getFirst().getIdMedicoEffettivo());
            assertEquals(f.prestazione, desk.getFirst().getIdPrestazione());
            assertEquals(f.stanza, desk.getFirst().getIdStanza());
            if (state == StatoPrenotazione.RICHIESTA) {
                assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.confermaAtomically(booking, f.operator));
                assertEquals(after, f.snapshot(booking));
            }
        }
    }

    @Test
    void wrongDoctorAndPastVisitsNeverChangeAnything() throws Exception {
        try (Fixture f = new Fixture()) {
            int request = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int confirmed = f.booking(StatoPrenotazione.CONFERMATA, NOW.plusHours(2));
            assertThrows(RisorsaNonTrovataException.class, () -> f.repository.segnalaIndisponibilitaAtomically(f.effective, List.of(request)));
            assertThrows(RisorsaNonTrovataException.class, () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(confirmed)));
            assertFalse(f.flag(request)); assertFalse(f.flag(confirmed));
            for (LocalDateTime time : List.of(NOW.minusHours(1), NOW)) {
                int expired = f.booking(StatoPrenotazione.RICHIESTA, time);
                assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(expired)));
                assertFalse(f.flag(expired));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RIFIUTATA", "ANNULLATA", "EFFETTUATA", "PAZIENTE_ASSENTE", "NON_EROGATA"})
    void terminalStatesCannotBeReportedAndDoNotAppearInDeskList(StatoPrenotazione state) throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(state, NOW.plusHours(1));
            assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(id)));
            assertFalse(f.flag(id));
            f.execute("UPDATE prenotazione SET indisponibilita_segnalata = TRUE WHERE id_prenotazione = ?", id);
            assertTrue(f.repository.findSegnalazioniPendenti().stream().noneMatch(row -> row.getIdPrenotazione() == id));
            List<Object> anomalous = f.snapshot(id);
            assertTrue(it.medbook.config.TestPrenotazioneDiagnostics.terminalReports().contains(id));
            assertEquals(anomalous, f.snapshot(id));
        }
    }

    @Test
    void wholeGroupSucceedsOrStaysUnchanged() throws Exception {
        try (Fixture f = new Fixture()) {
            int a = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int b = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(2));
            int bad = f.booking(StatoPrenotazione.RIFIUTATA, NOW.plusHours(3));
            assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(a, b, bad)));
            assertFalse(f.flag(a)); assertFalse(f.flag(b)); assertFalse(f.flag(bad));
            f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(b, a));
            assertTrue(f.flag(a)); assertTrue(f.flag(b));
        }
    }

    @Test
    void commitFailureRollsBackEveryFlag() throws Exception {
        try (Fixture f = new Fixture()) {
            int a = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int b = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(2));
            ConnectionFactory broken = proxyFactory(f.factory, true, null);
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(broken, CLOCK)
                    .segnalaIndisponibilitaAtomically(f.owner, List.of(a, b)));
            assertFalse(f.flag(a)); assertFalse(f.flag(b));
        }
    }

    @Test
    void rejectionAndBothCancellationsResolveReportAtomically() throws Exception {
        try (Fixture f = new Fixture()) {
            int rejected = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int cancelled = f.booking(StatoPrenotazione.CONFERMATA, NOW.plusHours(2));
            int patientCancelled = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(3));
            f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(rejected, patientCancelled));
            f.repository.segnalaIndisponibilitaAtomically(f.effective, List.of(cancelled));
            f.repository.rifiutaAtomically(rejected, f.operator);
            f.repository.annullaAtomically(cancelled, f.operator, StatoPrenotazione.CONFERMATA);
            f.repository.annullaPazienteAtomically(patientCancelled, f.patient, StatoPrenotazione.RICHIESTA);
            assertFalse(f.flag(rejected)); assertFalse(f.flag(cancelled)); assertFalse(f.flag(patientCancelled));
        }
    }

    @Test
    void failedCancellationPreservesReportAndPayment() throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(StatoPrenotazione.CONFERMATA, NOW.plusHours(1));
            f.repository.segnalaIndisponibilitaAtomically(f.effective, List.of(id));
            f.execute("INSERT INTO pagamento (id_prenotazione, modalita, importo, stato, creato_il) VALUES (?, 'ONLINE', 50, 'IN_ATTESA', ?)", id, NOW);
            List<Object> before = f.snapshot(id);
            assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.annullaAtomically(id, f.operator, StatoPrenotazione.CONFERMATA));
            assertEquals(before, f.snapshot(id)); assertTrue(f.flag(id));
        }
    }

    @Test
    void concurrentReportsAreIdempotentAndDeskRejectionCannotLosePendingState() throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            List<Throwable> reports = race(
                    () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(id)),
                    () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(id)));
            assertTrue(reports.isEmpty()); assertTrue(f.flag(id));
            List<Throwable> failures = race(
                    () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(id)),
                    () -> f.repository.rifiutaAtomically(id, f.operator));
            assertTrue(failures.stream().allMatch(e -> e instanceof ConflittoPrenotazioneException));
            assertFalse(f.flag(id));
            try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT stato FROM prenotazione WHERE id_prenotazione = ?")) {
                s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); assertEquals("RIFIUTATA", r.getString(1)); }
            }
        }
    }

    @Test
    void concurrentConfirmationEitherWinsBeforeReportOrIsBlockedByIt() throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            List<Throwable> failures = race(
                    () -> f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(id)),
                    () -> f.repository.confermaAtomically(id, f.operator));
            assertTrue(failures.stream().allMatch(e -> e instanceof ConflittoPrenotazioneException));
            assertTrue(f.flag(id));
            // Se la conferma arriva prima, la segnalazione valida segue la visita ancora assegnata al titolare.
            assertTrue(f.repository.findSegnalazioniPendenti().stream().anyMatch(row -> row.getIdPrenotazione() == id));
        }
    }

    @Test
    void clockIsReadAfterWaitingForSlotAndEntireGroupRollsBackAtBoundary() throws Exception {
        try (Fixture f = new Fixture(); Connection held = f.factory.getConnection()) {
            int id = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            held.setAutoCommit(false);
            try (PreparedStatement lock = held.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                lock.setInt(1, f.slots.getFirst()); try (ResultSet r = lock.executeQuery()) { assertTrue(r.next()); }
            }
            CountDownLatch attempting = new CountDownLatch(1);
            AtomicReference<Instant> instant = new AtomicReference<>(CLOCK.instant());
            Clock mutable = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return instant.get(); }
            };
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = workers.submit(() -> {
                    try { new PrenotazioneRepository(proxyFactory(f.factory, false, attempting), mutable)
                            .segnalaIndisponibilitaAtomically(f.owner, List.of(id)); }
                    catch (SQLException e) { throw new CompletionException(e); }
                });
                assertTrue(attempting.await(5, TimeUnit.SECONDS));
                instant.set(NOW.plusHours(1).toInstant(ZoneOffset.UTC)); held.commit();
                assertInstanceOf(ConflittoPrenotazioneException.class,
                        assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS)).getCause());
                assertFalse(f.flag(id));
            } finally { held.rollback(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NULL", "2", "-1"})
    void databaseRejectsInvalidBooleanAndPreservesBooking(String invalid) throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            List<Object> before = f.snapshot(id);
            SQLException error = assertThrows(SQLException.class, () -> f.execute(
                    "UPDATE prenotazione SET indisponibilita_segnalata = ? WHERE id_prenotazione = ?",
                    invalid.equals("NULL") ? null : Integer.valueOf(invalid), id));
            assertEquals(invalid.equals("NULL") ? 1048 : 3819, error.getErrorCode());
            assertEquals(before, f.snapshot(id));
        }
    }

    @Test
    void secondRealUpdateFailureRollsBackWholeSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            int a = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int b = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(2));
            ConnectionFactory broken = proxyFactory(f.factory, false, null, true);
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(broken, CLOCK)
                    .segnalaIndisponibilitaAtomically(f.owner, List.of(a, b)));
            assertFalse(f.flag(a)); assertFalse(f.flag(b));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void configurationWaitsForReportLockAndPreservesPendingFlag(boolean reverseTimes) throws Exception {
        try (Fixture f = new Fixture()) {
            int id = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(reverseTimes ? 2 : 1));
            int companion = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(reverseTimes ? 1 : 2));
            int slot = f.slots.getFirst();
            // Rappresenta uno slot divenuto non configurato, senza alterare la richiesta.
            f.execute("UPDATE disponibilita SET id_prestazione=NULL, id_stanza=NULL WHERE id_disponibilita=?", slot);
            List<Object> before = f.snapshot(id);
            CountDownLatch reportLocksHeld = new CountDownLatch(1);
            CountDownLatch configAttemptingSlot = new CountDownLatch(1);
            CountDownLatch releaseReport = new CountDownLatch(1);
            Clock heldClock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() {
                    reportLocksHeld.countDown();
                    try { if (!releaseReport.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timeout sincronizzazione test"); } }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return CLOCK.instant();
                }
            };
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> report = workers.submit(() -> new PrenotazioneRepository(f.factory, heldClock)
                        .segnalaIndisponibilitaAtomically(f.owner, List.of(companion, id)));
                assertTrue(reportLocksHeld.await(5, TimeUnit.SECONDS));
                Future<?> configuration = workers.submit(() -> new DisponibilitaRepository(
                        proxyFactory(f.factory, false, configAttemptingSlot))
                        .configuraAtomically(slot, f.prestazione, f.stanza));
                assertTrue(configAttemptingSlot.await(5, TimeUnit.SECONDS));
                assertFalse(configuration.isDone());
                releaseReport.countDown();
                report.get(15, TimeUnit.SECONDS); configuration.get(15, TimeUnit.SECONDS);
                assertTrue(f.flag(id)); assertTrue(f.flag(companion));
                assertTrue(new DisponibilitaRepository(f.factory).findById(slot).orElseThrow().isConfigurata());
                List<Object> after = f.snapshot(id);
                for (int i = 0; i < before.size(); i++) {
                    if (i != f.flagColumn) { assertEquals(before.get(i), after.get(i)); }
                }
            } finally { releaseReport.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS)); }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RIFIUTA", "DESK_RICHIESTA", "DESK_CONFERMATA", "PAZIENTE_RICHIESTA", "PAZIENTE_CONFERMATA"})
    void terminalRollbackPreservesWholeReportedGroupAndSuccessReopensSlot(String action) throws Exception {
        try (Fixture f = new Fixture()) {
            StatoPrenotazione state = action.endsWith("CONFERMATA") ? StatoPrenotazione.CONFERMATA : StatoPrenotazione.RICHIESTA;
            int a = f.booking(state, NOW.plusHours(1));
            int b = f.booking(state, NOW.plusHours(2));
            int doctor = state == StatoPrenotazione.RICHIESTA ? f.owner : f.effective;
            f.repository.segnalaIndisponibilitaAtomically(doctor, List.of(b, a));
            List<Object> beforeA = f.snapshot(a), beforeB = f.snapshot(b);
            DisponibilitaRepository slots = new DisponibilitaRepository(f.factory, CLOCK);
            assertTrue(slots.findPrenotabili(f.owner, null, null, NOW).isEmpty());
            assertThrows(ConflittoPrenotazioneException.class, () -> f.repository.creaRichiestaAtomically(f.patient, f.slots.getFirst()));
            PrenotazioneRepository broken = new PrenotazioneRepository(proxyFactory(f.factory, true, null), CLOCK);
            assertThrows(SQLException.class, () -> terminalAction(broken, f, action, a, state));
            assertEquals(beforeA, f.snapshot(a)); assertEquals(beforeB, f.snapshot(b));
            terminalAction(f.repository, f, action, a, state);
            assertFalse(f.flag(a)); assertTrue(f.flag(b)); assertEquals(beforeB, f.snapshot(b));
            assertEquals(List.of(f.slots.getFirst()), slots.findPrenotabili(f.owner, null, null, NOW).stream()
                    .map(it.medbook.model.Disponibilita::getIdDisponibilita).toList());
            var created = f.repository.creaRichiestaAtomically(f.patient, f.slots.getFirst());
            f.bookings.add(created.getIdPrenotazione());
            assertFalse(created.isIndisponibilitaSegnalata()); assertFalse(f.flag(created.getIdPrenotazione()));
            assertTrue(slots.findPrenotabili(f.owner, null, null, NOW).isEmpty());
            assertTrue(f.repository.findRichieste(f.owner, null, null, null).stream().noneMatch(row -> row.getIdPrenotazione() == a));
        }
    }

    private static void terminalAction(PrenotazioneRepository repository, Fixture f, String action,
                                       int id, StatoPrenotazione state) throws SQLException {
        if (action.equals("RIFIUTA")) { repository.rifiutaAtomically(id, f.operator); }
        else if (action.startsWith("DESK")) { repository.annullaAtomically(id, f.operator, state); }
        else { repository.annullaPazienteAtomically(id, f.patient, state); }
    }

    @Test
    void deskReadsBothFlagsWithUnchangedFiltersOrderAndSnapshotWhilePatientNeverSeesFlag() throws Exception {
        try (Fixture f = new Fixture()) {
            int later = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(2));
            int earlier = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(later));
            var rows = f.repository.findRichieste(f.owner, f.prestazione, NOW.toLocalDate(), NOW.toLocalDate());
            assertEquals(List.of(earlier, later), rows.stream().map(it.medbook.dto.RichiestaPrenotazioneResponse::getIdPrenotazione).toList());
            assertFalse(rows.getFirst().isIndisponibilitaSegnalata()); assertTrue(rows.getLast().isIndisponibilitaSegnalata());
            assertEquals(f.patient, rows.getLast().getIdPaziente()); assertEquals(f.owner, rows.getLast().getIdMedico());
            assertEquals(f.prestazione, rows.getLast().getIdPrestazione()); assertEquals(f.stanza, rows.getLast().getIdStanza());
            assertEquals(0, new java.math.BigDecimal("50").compareTo(rows.getLast().getPrezzo()));
            var patient = f.repository.findByPaziente(f.patient, null, null, null, NOW);
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(patient);
            assertFalse(json.contains("indisponibilitaSegnalata"));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void agendaDistinguishesOwnerAndEffectiveWithBothFlagValues(boolean pending) throws Exception {
        try (Fixture f = new Fixture()) {
            int request = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int confirmed = f.booking(StatoPrenotazione.CONFERMATA, NOW.plusHours(2));
            if (pending) {
                f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(request));
                f.repository.segnalaIndisponibilitaAtomically(f.effective, List.of(confirmed));
            }
            var owner = f.repository.findAgenda(f.owner, NOW.toLocalDate(), NOW.toLocalDate());
            var effective = f.repository.findAgenda(f.effective, NOW.toLocalDate(), NOW.toLocalDate());
            assertEquals(1, owner.size()); assertEquals(request, owner.getFirst().getIdPrenotazione());
            assertNull(owner.getFirst().getIdMedicoEffettivo()); assertEquals(f.owner, owner.getFirst().getIdMedicoTitolare());
            assertEquals(1, effective.size()); assertEquals(confirmed, effective.getFirst().getIdPrenotazione());
            assertEquals(f.owner, effective.getFirst().getIdMedicoTitolare()); assertEquals(f.effective, effective.getFirst().getIdMedicoEffettivo());
            assertEquals(pending, owner.getFirst().isIndisponibilitaSegnalata());
            assertEquals(pending, effective.getFirst().isIndisponibilitaSegnalata());
        }
    }

    @Test
    void reportedGroupPreventsDoctorChangingOrDeletingEvenUnconfiguredSlots() throws Exception {
        try (Fixture f = new Fixture()) {
            int a = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(1));
            int b = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(2));
            for (int slot : f.slots) { f.execute("UPDATE disponibilita SET id_prestazione=NULL,id_stanza=NULL WHERE id_disponibilita=?", slot); }
            f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(b, a));
            DisponibilitaRepository slots = new DisponibilitaRepository(f.factory, CLOCK);
            for (int slot : f.slots) {
                var before = slots.findById(slot).orElseThrow();
                assertThrows(it.medbook.exception.ConflittoDisponibilitaException.class,
                        () -> slots.modificaOrarioAtomically(slot, f.owner, NOW.plusHours(5), NOW.plusHours(6)));
                assertThrows(it.medbook.exception.ConflittoDisponibilitaException.class, () -> slots.eliminaAtomically(slot, f.owner));
                assertEquals(before.getDataOraInizio(), slots.findById(slot).orElseThrow().getDataOraInizio());
            }
            assertTrue(f.flag(a)); assertTrue(f.flag(b));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void multipleReportsAndRoomConflictNoLongerDeadlock(boolean reverseTimes) throws Exception {
        try (Fixture f = new Fixture()) {
            // La prenotazione sullo slot da configurare rappresenta dati storici non configurati.
            // Gli ID degli slot seguono l'inserimento, gli orari possono seguire l'ordine opposto.
            int b = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(reverseTimes ? 2 : 1));
            int a = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusHours(reverseTimes ? 1 : 2));
            int slotB = f.slots.get(0), slotA = f.slots.get(1);
            // Rendiamo gli intervalli sovrapposti mantenendo l'ordine temporale scelto.
            LocalDateTime startA = NOW.plusMinutes(reverseTimes ? 90 : 120);
            LocalDateTime startB = NOW.plusMinutes(reverseTimes ? 120 : 90);
            f.execute("UPDATE disponibilita SET data_ora_inizio=?,data_ora_fine=? WHERE id_disponibilita=?", startB, startB.plusHours(1), slotB);
            f.execute("UPDATE disponibilita SET data_ora_inizio=?,data_ora_fine=?,id_prestazione=NULL,id_stanza=NULL WHERE id_disponibilita=?", startA, startA.plusHours(1), slotA);
            CountDownLatch reportHasB = new CountDownLatch(1), configHasA = new CountDownLatch(1);
            CountDownLatch configQueriesRoom = new CountDownLatch(1);
            ConnectionFactory reportFactory = observeQueries(f.factory, (sql, id, after) -> {
                if (sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                    if (after && id == slotB) { reportHasB.countDown(); await(configHasA); }
                    if (!after && id == slotA) { await(configQueriesRoom); }
                }
            });
            ConnectionFactory configFactory = observeQueries(f.factory, (sql, id, after) -> {
                if (after && sql.contains("FROM disponibilita WHERE id_disponibilita") && id == slotA) { configHasA.countDown(); }
                if (!after && sql.contains("WHERE id_stanza = ? AND id_disponibilita <> ?")) { configQueriesRoom.countDown(); }
            });
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Throwable> report = workers.submit(() -> capture(() -> new PrenotazioneRepository(reportFactory, CLOCK)
                        .segnalaIndisponibilitaAtomically(f.owner, reverseTimes ? List.of(a, b) : List.of(b, a))));
                assertTrue(reportHasB.await(5, TimeUnit.SECONDS));
                Future<Throwable> config = workers.submit(() -> capture(() -> new DisponibilitaRepository(configFactory, CLOCK)
                        .configuraAtomically(slotA, f.prestazione, f.stanza)));
                Throwable reportError = report.get(15, TimeUnit.SECONDS), configError = config.get(15, TimeUnit.SECONDS);
                List<Throwable> errors = new ArrayList<>();
                if (reportError != null) { errors.add(reportError); }
                if (configError != null) { errors.add(configError); }
                assertNull(reportError, "La segnalazione multipla deve riuscire senza deadlock o timeout.");
                assertInstanceOf(it.medbook.exception.ConflittoConfigurazioneException.class, configError);
                assertTrue(errors.stream().noneMatch(error -> error instanceof SQLException sql
                        && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)));
                assertEquals(reportError == null, f.flag(a)); assertEquals(reportError == null, f.flag(b));
                // Il conflitto di stanza preesiste: UC-27 non deve assegnare due volte la stessa stanza.
                assertNull(new DisponibilitaRepository(f.factory).findById(slotA).orElseThrow().getStanza());
                assertEquals(f.stanza, new DisponibilitaRepository(f.factory).findById(slotB).orElseThrow().getStanza().getIdStanza());
                System.out.println("UC20/UC27: nessun deadlock; reverseTimes=" + reverseTimes
                        + "; rollback gruppo=" + (reportError != null));
            } finally {
                configHasA.countDown(); configQueriesRoom.countDown();
                workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @FunctionalInterface
    interface QueryObserver { void observe(String sql, int id, boolean after) throws Exception; }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timeout sincronizzazione controllata"); }
    }

    private static Throwable capture(Callable<?> task) {
        try { task.call(); return null; } catch (Throwable error) { return error; }
    }

    static ConnectionFactory observeQueries(ConnectionFactory delegate, QueryObserver observer) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = delegate.getConnection();
                try (Statement statement = connection.createStatement()) { statement.execute("SET SESSION innodb_lock_wait_timeout=5"); }
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, args) -> {
                    try {
                        Object result = m.invoke(connection, args);
                        if (!m.getName().equals("prepareStatement")) { return result; }
                        String sql = (String) args[0]; PreparedStatement statement = (PreparedStatement) result;
                        int[] id = {0};
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                            if (sm.getName().equals("setInt") && ((Integer) sa[0]) == 1) { id[0] = (Integer) sa[1]; }
                            if (sm.getName().equals("executeQuery")) { observer.observe(sql, id[0], false); }
                            try {
                                Object value = sm.invoke(statement, sa);
                                if (sm.getName().equals("executeQuery")) { observer.observe(sql, id[0], true); }
                                return value;
                            } catch (InvocationTargetException error) { throw error.getCause(); }
                        });
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }
        };
    }

    private static List<Throwable> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                results.add(workers.submit(() -> { start.await(); try { task.call(); return null; } catch (Throwable e) { return e; } }));
            }
            start.countDown(); List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> result : results) { Throwable failure = result.get(15, TimeUnit.SECONDS); if (failure != null) failures.add(failure); }
            return failures;
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    private static ConnectionFactory proxyFactory(ConnectionFactory delegate, boolean failCommit, CountDownLatch attempting) {
        return proxyFactory(delegate, failCommit, attempting, false);
    }

    private static ConnectionFactory proxyFactory(ConnectionFactory delegate, boolean failCommit,
                                                   CountDownLatch attempting, boolean failSecondUpdate) {
        java.util.concurrent.atomic.AtomicInteger updates = new java.util.concurrent.atomic.AtomicInteger();
        return new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = delegate.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (failCommit && method.getName().equals("commit")) { throw new SQLException("Commit simulato fallito"); }
                    try {
                        Object result = method.invoke(connection, args);
                        if (failSecondUpdate && method.getName().equals("prepareStatement")
                                && ((String) args[0]).contains("SET indisponibilita_segnalata = TRUE")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                if (m.getName().equals("executeUpdate") && updates.incrementAndGet() == 2) {
                                    throw new SQLException("Secondo aggiornamento simulato fallito");
                                }
                                try { return m.invoke(statement, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        if (attempting != null && method.getName().equals("prepareStatement") && ((String) args[0]).contains("FROM disponibilita") && ((String) args[0]).contains("FOR UPDATE")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                if (m.getName().equals("executeQuery")) { attempting.countDown(); }
                                try { return m.invoke(statement, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
    }

    static class Fixture implements AutoCloseable {
        final ConnectionFactory factory;
        final PrenotazioneRepository repository;
        final List<Integer> users = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();
        final List<Integer> bookings = new ArrayList<>();
        int owner, effective, patient, operator, prestazione, stanza;
        int flagColumn;

        Fixture() throws Exception {
            factory = TestDatabaseConnections.isolatedFactory();
            repository = new PrenotazioneRepository(factory, CLOCK);
            try {
                owner = doctor(); effective = doctor(); patient = user(); operator = user();
                execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?", operator);
                execute("INSERT INTO paziente (id_utente, codice_fiscale, data_nascita) VALUES (?, ?, '1990-01-01')", patient,
                        UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                prestazione = insert("INSERT INTO prestazione (nome, durata, prezzo) VALUES ('Visita UC20', 60, 50)");
                stanza = insert("INSERT INTO stanza (nome) VALUES (?)", "UC20-" + UUID.randomUUID());
                execute("INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?)", owner, prestazione);
                execute("INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?)", effective, prestazione);
            } catch (Exception e) { try { close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); } throw e; }
        }

        int user() throws SQLException {
            int id = insert("INSERT INTO utente (nome,cognome,email,password_hash) VALUES ('UC20','Test',?,?)",
                    UUID.randomUUID() + "@example.invalid", "hash-fittizio"); users.add(id); return id;
        }
        int doctor() throws SQLException {
            int id = user(); execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?, 'Generale', ?)", id, UUID.randomUUID().toString()); return id;
        }
        int booking(StatoPrenotazione state, LocalDateTime start) throws SQLException {
            int slot = insert("INSERT INTO disponibilita (id_medico,id_prestazione,id_stanza,data_ora_inizio,data_ora_fine) VALUES (?,?,?,?,?)",
                    owner, prestazione, stanza, start, start.plusHours(1)); slots.add(slot);
            boolean managed = state != StatoPrenotazione.RICHIESTA;
            int id = insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,stato,data_ora_richiesta,prezzo,gestita_da,data_ora_gestione,id_medico_effettivo) VALUES (?,?,?,?,50,?,?,?)",
                    patient, slot, state.name(), NOW, managed ? operator : null, managed ? NOW : null, managed ? effective : null);
            bookings.add(id); return id;
        }
        List<Object> snapshot(int id) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione=?")) {
                s.setInt(1, id); try (ResultSet rows = s.executeQuery()) {
                    assertTrue(rows.next()); List<Object> result = new ArrayList<>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                        if (rows.getMetaData().getColumnLabel(i).equals("indisponibilita_segnalata")) { flagColumn = i - 1; }
                        result.add(rows.getObject(i));
                    }
                    return result;
                }
            }
        }
        boolean flag(int id) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione=?")) {
                s.setInt(1, id); try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getBoolean(1); }
            }
        }
        int insert(String sql, Object... values) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); }
                s.executeUpdate(); try (ResultSet keys = s.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getInt(1); }
            }
        }
        void execute(String sql, Object... values) throws SQLException {
            try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); } s.executeUpdate();
            }
        }
        public void close() throws SQLException {
            for (Integer id : bookings) {
                execute("DELETE FROM pagamento WHERE id_prenotazione=?", id);
                execute("DELETE FROM prenotazione WHERE id_prenotazione=?", id);
            }
            for (Integer id : slots) { execute("DELETE FROM disponibilita WHERE id_disponibilita=?", id); }
            for (Integer id : users) { execute("DELETE FROM medico_prestazione WHERE id_medico=?", id); }
            if (stanza != 0) { execute("DELETE FROM stanza WHERE id_stanza=?", stanza); }
            if (prestazione != 0) { execute("DELETE FROM prestazione WHERE id_prestazione=?", prestazione); }
            for (Integer id : users) { execute("DELETE FROM utente WHERE id_utente=?", id); }
        }
    }
}
