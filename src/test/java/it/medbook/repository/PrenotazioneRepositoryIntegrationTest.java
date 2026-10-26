package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.RichiestaPrenotazioneResponse;
import it.medbook.dto.AppuntamentoAgendaResponse;
import it.medbook.dto.AppuntamentoPazienteResponse;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.model.StatoPagamento;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.Prenotazione;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PrenotazioneRepositoryIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);
    private static final LocalDateTime START = NOW.plusHours(1);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Test
    void createsInitialRequestWithSnapshotPriceAndRemovesSlotFromUc04() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            MutableClock clock = new MutableClock(FIXED.instant());
            Prenotazione result = new PrenotazioneRepository(fixture.factory, clock)
                    .creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot);
            assertNotNull(result.getIdPrenotazione());
            assertEquals(StatoPrenotazione.RICHIESTA, result.getStato());
            assertEquals(NOW, result.getDataOraRichiesta());
            assertEquals(1, clock.reads.get());
            assertEquals(new BigDecimal("50.00"), result.getPrezzo());
            assertNull(result.getGestitaDa());
            assertNull(result.getDataOraGestione());
            assertNull(result.getMedicoEffettivo());
            assertEquals(fixture.pazienti.getFirst(), result.getPaziente().getUtente().getIdUtente());
            assertEquals(fixture.slot, result.getDisponibilita().getIdDisponibilita());
            assertEquals(fixture.medico, result.getDisponibilita().getMedico().getUtente().getIdUtente());
            assertEquals(START, result.getDisponibilita().getDataOraInizio());
            assertEquals(START.plusHours(1), result.getDisponibilita().getDataOraFine());
            assertPersistedInitialRequest(fixture, result);
            assertTrue(new DisponibilitaRepository(fixture.factory)
                    .findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
            fixture.update("UPDATE prestazione SET prezzo = ? WHERE id_prestazione = ?",
                    new BigDecimal("60.00"), fixture.prestazione);
            try (Connection connection = fixture.factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT prezzo FROM prenotazione WHERE id_prenotazione = ?")) {
                statement.setInt(1, result.getIdPrenotazione());
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(new BigDecimal("50.00"), rows.getBigDecimal("prezzo"));
                }
            }
        }
    }

    @Test
    void rejectsMissingPatientWithoutInserting() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.pazienti.getFirst();
            fixture.update("DELETE FROM paziente WHERE id_utente = ?", id);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.creaRichiestaAtomically(id, fixture.slot));
            assertEquals(0, fixture.bookingCount());
        }
    }

    @Test
    void rejectsMissingSlotWithoutInserting() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.update("DELETE FROM disponibilita WHERE id_disponibilita = ?", fixture.slot);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot));
            assertEquals(0, fixture.bookingCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nessuna", "soloPrestazione", "soloStanza"})
    void rejectsUnconfiguredAndPartiallyConfiguredSlots(String configuration) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.update("UPDATE disponibilita SET id_prestazione = ?, id_stanza = ? WHERE id_disponibilita = ?",
                    configuration.equals("soloPrestazione") ? fixture.prestazione : null,
                    configuration.equals("soloStanza") ? fixture.stanza : null, fixture.slot);
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot));
            assertEquals(0, fixture.bookingCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 0})
    void rejectsPastSlotAndSlotStartingExactlyNow(int hours) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            LocalDateTime start = NOW.plusHours(hours);
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    start, start.plusHours(1), fixture.slot);
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot));
            assertEquals(0, fixture.bookingCount());
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void appliesSameOccupyingStatesAsUc04(StatoPrenotazione stato) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(stato);
            boolean free = stato == StatoPrenotazione.RIFIUTATA || stato == StatoPrenotazione.ANNULLATA;
            if (free) {
                assertEquals(StatoPrenotazione.RICHIESTA, fixture.repository
                        .creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot).getStato());
            } else {
                assertThrows(ConflittoPrenotazioneException.class,
                        () -> fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot));
            }
            assertEquals(free ? 2 : 1, fixture.bookingCount());
        }
    }

    @Test
    void permitsMultipleReleasedHistoricalBookings() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(StatoPrenotazione.RIFIUTATA);
            fixture.history(StatoPrenotazione.ANNULLATA);
            fixture.history(StatoPrenotazione.RIFIUTATA);
            fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot);
            assertEquals(4, fixture.bookingCount());
            assertEquals(1, fixture.requestCount());
        }
    }

    @Test
    void rejectsMixedHistoryContainingBlockingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(StatoPrenotazione.RIFIUTATA);
            fixture.history(StatoPrenotazione.CONFERMATA);
            fixture.history(StatoPrenotazione.ANNULLATA);
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot));
            assertEquals(3, fixture.bookingCount());
        }
    }

    @Test
    void commitFailureAfterSuccessfulInsertRollsBackRealDatabase() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            AtomicBoolean commitAttempted = new AtomicBoolean();
            AtomicBoolean rollbackCalled = new AtomicBoolean();
            AtomicBoolean closed = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    // L'INSERT è già riuscito; falliamo prima del commit reale.
                                    commitAttempted.set(true);
                                    throw new SQLException("Commit simulato fallito.");
                                }
                                if (method.getName().equals("rollback")) { rollbackCalled.set(true); }
                                if (method.getName().equals("close")) { closed.set(true); }
                                return invoke(real, method, args);
                            });
                }
            };
            SQLException error = assertThrows(SQLException.class,
                    () -> new PrenotazioneRepository(failing, FIXED)
                            .creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot));
            assertEquals("Commit simulato fallito.", error.getMessage());
            assertTrue(commitAttempted.get());
            assertTrue(rollbackCalled.get());
            assertTrue(closed.get());
            assertEquals(0, fixture.bookingCount());
            // Una nuova Connection deve poter acquisire il lock e creare la richiesta.
            fixture.repository.creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot);
            assertEquals(1, fixture.bookingCount());
        }
    }

    @Test
    void concurrentPatientsCreateExactlyOneRequestAndOneConflict() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Integer paziente : fixture.pazienti) {
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try {
                            fixture.repository.creaRichiestaAtomically(paziente, fixture.slot);
                            successes.incrementAndGet();
                        } catch (ConflittoPrenotazioneException e) { conflicts.incrementAndGet(); }
                        return null;
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> future : futures) { future.get(20, TimeUnit.SECONDS); }
                assertEquals(1, successes.get());
                assertEquals(1, conflicts.get());
                assertEquals(1, fixture.bookingCount());
                assertEquals(1, fixture.requestCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void readsClockAfterWaitingForSlotLockAndRejectsExpiredSlot() throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Prenotazione> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .creaRichiestaAtomically(fixture.pazienti.getFirst(), fixture.slot));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoPrenotazioneException.class, error.getCause());
                assertEquals(1, clock.reads.get());
                assertEquals(0, fixture.bookingCount());
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc10IncludesOnlyRequestsAndDoesNotModifyData(StatoPrenotazione stato) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(stato);
            List<List<Object>> before = fixture.snapshot();
            List<RichiestaPrenotazioneResponse> result = fixture.repository
                    .findRichieste(fixture.medico, null, null, null);
            assertEquals(stato == StatoPrenotazione.RICHIESTA ? 1 : 0, result.size());
            if (!result.isEmpty()) { assertEquals(StatoPrenotazione.RICHIESTA, result.getFirst().getStato()); }
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc10MapsMultiplePatientsDoctorsAndSnapshotPrice() throws SQLException {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            first.update("UPDATE utente SET nome = ?, cognome = ? WHERE id_utente = ?", "Anna", "Bianchi", first.pazienti.getFirst());
            first.update("UPDATE utente SET nome = ?, cognome = ? WHERE id_utente = ?", "Mario", "Rossi", first.medico);
            int id = first.request(first.slot, first.pazienti.getFirst(), NOW.minusDays(1));
            int otherPatient = first.request(first.slotAt(START.plusHours(1)), first.pazienti.getLast(), NOW);
            int otherDoctor = second.request(second.slot, second.pazienti.getFirst(), NOW);
            first.update("UPDATE prestazione SET prezzo = ? WHERE id_prestazione = ?", new BigDecimal("60.00"), first.prestazione);
            List<Integer> owned = List.of(id, otherPatient, otherDoctor);
            List<RichiestaPrenotazioneResponse> result = first.repository.findRichieste(null, null, null, null).stream()
                    .filter(r -> owned.contains(r.getIdPrenotazione())).toList();
            assertEquals(3, result.size());
            RichiestaPrenotazioneResponse row = result.stream().filter(r -> r.getIdPrenotazione() == id).findFirst().orElseThrow();
            assertEquals(StatoPrenotazione.RICHIESTA, row.getStato());
            assertEquals(NOW.minusDays(1), row.getDataOraRichiesta());
            assertEquals(START, row.getDataOraInizio());
            assertEquals(START.plusHours(1), row.getDataOraFine());
            assertEquals(new BigDecimal("50.00"), row.getPrezzo());
            assertEquals(first.pazienti.getFirst(), row.getIdPaziente());
            assertEquals("Anna", row.getNomePaziente());
            assertEquals("Bianchi", row.getCognomePaziente());
            assertEquals(first.medico, row.getIdMedico());
            assertEquals("Mario", row.getNomeMedico());
            assertEquals("Rossi", row.getCognomeMedico());
            assertEquals(first.prestazione, row.getIdPrestazione());
            assertEquals("Visita UC05", row.getNomePrestazione());
            assertEquals(30, row.getDurataPrestazione());
            assertEquals(first.stanza, row.getIdStanza());
            assertNotNull(row.getNomeStanza());
            assertTrue(row.getNomeStanza().startsWith("UC05-"));
            assertTrue(result.stream().anyMatch(r -> r.getIdPaziente().equals(first.pazienti.getLast())));
            assertTrue(result.stream().anyMatch(r -> r.getIdMedico().equals(second.medico)));
            assertTrue(first.repository.findRichieste(first.medico, second.prestazione, null, null).isEmpty());
        }
    }

    @Test
    void uc10AppliesFiltersOpenRangesAndInclusiveCalendarBounds() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            LocalDate day = START.toLocalDate();
            int before = fixture.request(fixture.slotAt(day.minusDays(1).atTime(23, 0)), fixture.pazienti.getFirst(), NOW);
            int midnight = fixture.request(fixture.slotAt(day.atStartOfDay()), fixture.pazienti.getFirst(), NOW);
            int normal = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int late = fixture.request(fixture.slotAt(day.atTime(23, 0)), fixture.pazienti.getLast(), NOW);
            int after = fixture.request(fixture.slotAt(day.plusDays(1).atStartOfDay()), fixture.pazienti.getLast(), NOW);
            assertEquals(List.of(before, midnight, normal, late, after), ids(fixture.repository
                    .findRichieste(fixture.medico, null, null, null)));
            assertEquals(List.of(before, midnight, normal, late, after), ids(fixture.repository
                    .findRichieste(null, fixture.prestazione, null, null)));
            assertEquals(List.of(midnight, normal, late), ids(fixture.repository
                    .findRichieste(fixture.medico, null, day, day)));
            assertEquals(List.of(midnight, normal, late, after), ids(fixture.repository
                    .findRichieste(fixture.medico, null, day, null)));
            assertEquals(List.of(before, midnight, normal, late), ids(fixture.repository
                    .findRichieste(fixture.medico, null, null, day)));
            assertEquals(List.of(before, midnight, normal, late), ids(fixture.repository
                    .findRichieste(fixture.medico, fixture.prestazione, day.minusDays(1), day)));
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, day.plusDays(2), null).isEmpty());
            RichiestaPrenotazioneResponse last = fixture.repository.findRichieste(fixture.medico, null, day, day).getLast();
            assertEquals(day.plusDays(1).atStartOfDay(), last.getDataOraFine());
        }
    }

    @Test
    void uc10OrdersByAppointmentThenRequestTimeThenId() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int laterAppointment = fixture.request(fixture.slotAt(START.plusDays(1)), fixture.pazienti.getFirst(), NOW.minusDays(5));
            int laterRequest = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int earlierRequest = fixture.request(fixture.slot, fixture.pazienti.getLast(), NOW.minusDays(1));
            int tiedRequest = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            assertEquals(List.of(earlierRequest, tiedRequest, laterRequest, laterAppointment),
                    ids(fixture.repository.findRichieste(fixture.medico, null, null, null)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nessuna", "soloPrestazione", "soloStanza"})
    void uc10KeepsPastAndPartiallyConfiguredRequestsVisible(String configuration) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(2));
            fixture.update("""
                    UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ?, id_prestazione = ?, id_stanza = ?
                    WHERE id_disponibilita = ?
                    """, NOW.minusDays(1), NOW.minusDays(1).plusHours(1),
                    configuration.equals("soloPrestazione") ? fixture.prestazione : null,
                    configuration.equals("soloStanza") ? fixture.stanza : null, fixture.slot);
            RichiestaPrenotazioneResponse row = fixture.repository.findRichieste(fixture.medico, null, null, null).getFirst();
            assertEquals(id, row.getIdPrenotazione());
            assertEquals(NOW.minusDays(1), row.getDataOraInizio());
            assertEquals(configuration.equals("soloPrestazione") ? fixture.prestazione : null, row.getIdPrestazione());
            assertEquals(configuration.equals("soloStanza") ? fixture.stanza : null, row.getIdStanza());
            if (row.getIdPrestazione() == null) {
                assertNull(row.getNomePrestazione());
                assertNull(row.getDurataPrestazione());
            }
            if (row.getIdStanza() == null) { assertNull(row.getNomeStanza()); }
        }
    }

    @Test
    void uc10EmptyFixtureReturnsEmptyList() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
        }
    }

    private static List<Integer> ids(List<RichiestaPrenotazioneResponse> rows) {
        return rows.stream().map(RichiestaPrenotazioneResponse::getIdPrenotazione).toList();
    }

    @Test
    void uc11ConfirmsFourFieldsKeepsOtherDataAndPreservesUc04Uc10() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int operator = fixture.operator("OPERATORE_DESK");
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Object> before = fixture.unchanged(id);
            MutableClock clock = new MutableClock(FIXED.instant());
            ConfermaPrenotazioneResponse result = new PrenotazioneRepository(fixture.factory, clock)
                    .confermaAtomically(id, operator);
            assertEquals(id, result.getIdPrenotazione());
            assertEquals(StatoPrenotazione.CONFERMATA, result.getStato());
            assertEquals(operator, result.getIdOperatore());
            assertEquals(NOW, result.getDataOraGestione());
            assertEquals(fixture.medico, result.getIdMedicoEffettivo());
            assertEquals(1, clock.reads.get());
            assertEquals(before, fixture.unchanged(id));
            assertConfirmation(fixture, id, operator);
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertConfirmation(fixture, id, operator);
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = "RICHIESTA", mode = EnumSource.Mode.EXCLUDE)
    void uc11RejectsEveryNonRequestStateWithoutChanges(StatoPrenotazione stato) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(stato);
            int operator = fixture.operator("OPERATORE_DESK");
            int id = fixture.onlyBookingId();
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc11RejectsMissingOperatorAndMissingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int missing = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM utente WHERE id_utente = ?", missing);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.confermaAtomically(id, missing));
            int valid = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM prenotazione WHERE id_prenotazione = ?", id);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.confermaAtomically(id, valid));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "AMMINISTRATORE"})
    void uc11RejectsUnpermittedRoleWithoutChanges(String role) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator(role.equals("NULL") ? null : role);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(OperazioneNonAutorizzataException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 0})
    void uc11RejectsPastOrStartedAppointments(int hours) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    NOW.plusHours(hours), NOW.plusHours(hours + 1), fixture.slot);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nessuna", "soloPrestazione", "soloStanza"})
    void uc11RejectsIncompleteConfiguration(String configuration) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET id_prestazione = ?, id_stanza = ? WHERE id_disponibilita = ?",
                    configuration.equals("soloPrestazione") ? fixture.prestazione : null,
                    configuration.equals("soloStanza") ? fixture.stanza : null, fixture.slot);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc11RejectsIncoherentRequestsWithoutRepair(String scenario) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (scenario.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = ?, data_ora_gestione = ? WHERE id_prenotazione = ?",
                        operator, NOW.minusHours(1), id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", fixture.medico, id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.confermaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc11ConcurrentConfirmationsHaveOneWinnerAndKeepWinnerData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Integer> operators = List.of(fixture.operator("OPERATORE_DESK"), fixture.operator("OPERATORE_DESK"));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            List<Future<ConfermaPrenotazioneResponse>> futures = new ArrayList<>();
            try {
                for (int operator : operators) {
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try { return fixture.repository.confermaAtomically(id, operator); }
                        catch (ConflittoPrenotazioneException e) { return null; }
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                ConfermaPrenotazioneResponse a = futures.getFirst().get(20, TimeUnit.SECONDS);
                ConfermaPrenotazioneResponse b = futures.getLast().get(20, TimeUnit.SECONDS);
                assertNotEquals(a == null, b == null);
                ConfermaPrenotazioneResponse winner = a != null ? a : b;
                assertConfirmation(fixture, id, winner.getIdOperatore());
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc11FailedCommitRollsBackAllFourFields() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            List<List<Object>> before = fixture.snapshot();
            AtomicBoolean attempted = new AtomicBoolean();
            AtomicBoolean rolledBack = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    attempted.set(true);
                                    throw new SQLException("Commit simulato fallito.");
                                }
                                if (method.getName().equals("rollback")) { rolledBack.set(true); }
                                return invoke(real, method, args);
                            });
                }
            };
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(failing, FIXED).confermaAtomically(id, operator));
            assertTrue(attempted.get());
            assertTrue(rolledBack.get());
            assertEquals(before, fixture.snapshot());
            fixture.repository.confermaAtomically(id, operator);
            assertConfirmation(fixture, id, operator);
        }
    }

    private static void assertConfirmation(Fixture fixture, int id, int operator) throws SQLException {
        try (Connection connection = fixture.factory.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("CONFERMATA", rows.getString("stato"));
                assertEquals(operator, rows.getInt("gestita_da"));
                assertEquals(NOW, rows.getObject("data_ora_gestione", LocalDateTime.class));
                assertEquals(fixture.medico.intValue(), rows.getInt("id_medico_effettivo"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void uc11ReadsClockAfterWaitingForSlotLock() throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            List<List<Object>> before = fixture.snapshot();
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<ConfermaPrenotazioneResponse> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .confermaAtomically(id, operator));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoPrenotazioneException.class, error.getCause());
                assertEquals(1, clock.reads.get());
                assertEquals(before, fixture.snapshot());
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = "RICHIESTA", mode = EnumSource.Mode.EXCLUDE)
    void uc12RejectsEveryNonRequestStateWithoutChanges(StatoPrenotazione stato) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(stato);
            int operator = fixture.operator("OPERATORE_DESK");
            int id = fixture.onlyBookingId();
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.rifiutaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc12RejectsMissingOperatorAndMissingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int missing = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM utente WHERE id_utente = ?", missing);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.rifiutaAtomically(id, missing));
            int valid = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM prenotazione WHERE id_prenotazione = ?", id);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.rifiutaAtomically(id, valid));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "AMMINISTRATORE"})
    void uc12RejectsUnpermittedRoleWithoutChanges(String role) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator(role.equals("NULL") ? null : role);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(OperazioneNonAutorizzataException.class, () -> fixture.repository.rifiutaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc12RejectsIncoherentRequestsWithoutRepair(String scenario) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (scenario.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = ?, data_ora_gestione = ? WHERE id_prenotazione = ?",
                        operator, NOW.minusHours(1), id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", fixture.medico, id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.rifiutaAtomically(id, operator));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc12ConcurrentRejectionsHaveOneWinnerAndKeepWinnerData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Integer> operators = List.of(fixture.operator("OPERATORE_DESK"), fixture.operator("OPERATORE_DESK"));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            List<Future<RifiutoPrenotazioneResponse>> futures = new ArrayList<>();
            try {
                for (int operator : operators) {
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try { return fixture.repository.rifiutaAtomically(id, operator); }
                        catch (ConflittoPrenotazioneException e) { return null; }
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                RifiutoPrenotazioneResponse a = futures.getFirst().get(20, TimeUnit.SECONDS);
                RifiutoPrenotazioneResponse b = futures.getLast().get(20, TimeUnit.SECONDS);
                assertNotEquals(a == null, b == null);
                RifiutoPrenotazioneResponse winner = a != null ? a : b;
                assertRejection(fixture, id, winner.getIdOperatore());
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc12FailedCommitRollsBackAllThreeFields() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            List<List<Object>> before = fixture.snapshot();
            AtomicBoolean attempted = new AtomicBoolean();
            AtomicBoolean rolledBack = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    attempted.set(true);
                                    throw new SQLException("Commit simulato fallito.");
                                }
                                if (method.getName().equals("rollback")) { rolledBack.set(true); }
                                return invoke(real, method, args);
                            });
                }
            };
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(failing, FIXED).rifiutaAtomically(id, operator));
            assertTrue(attempted.get());
            assertTrue(rolledBack.get());
            assertEquals(before, fixture.snapshot());
            fixture.repository.rifiutaAtomically(id, operator);
            assertRejection(fixture, id, operator);
        }
    }

    private static void assertRejection(Fixture fixture, int id, int operator) throws SQLException {
        try (Connection connection = fixture.factory.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("RIFIUTATA", rows.getString("stato"));
                assertEquals(operator, rows.getInt("gestita_da"));
                assertEquals(NOW, rows.getObject("data_ora_gestione", LocalDateTime.class));
                assertNull(rows.getObject("id_medico_effettivo"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void uc12RejectsRequestKeepsOtherFieldsAndReleasesFutureConfiguredSlot() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int operator = fixture.operator("OPERATORE_DESK");
            int otherOperator = fixture.operator("OPERATORE_DESK");
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Object> before = fixture.unchanged(id);
            MutableClock clock = new MutableClock(FIXED.instant());
            RifiutoPrenotazioneResponse result = new PrenotazioneRepository(fixture.factory, clock).rifiutaAtomically(id, operator);
            assertEquals(id, result.getIdPrenotazione());
            assertEquals(StatoPrenotazione.RIFIUTATA, result.getStato());
            assertEquals(operator, result.getIdOperatore());
            assertEquals(NOW, result.getDataOraGestione());
            assertEquals(1, clock.reads.get());
            assertEquals(before, fixture.unchanged(id));
            assertRejection(fixture, id, operator);
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
            assertEquals(1, new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).size());
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.rifiutaAtomically(id, otherOperator));
            assertRejection(fixture, id, operator);
            fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot);
            assertEquals(1, fixture.requestCount());
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 0})
    void uc12PermitsPastAndStartedAppointmentsButUc04StillExcludesThem(int hours) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    NOW.plusHours(hours), NOW.plusHours(hours + 1), fixture.slot);
            List<Object> before = fixture.unchanged(id);
            fixture.repository.rifiutaAtomically(id, operator);
            assertEquals(before, fixture.unchanged(id));
            assertRejection(fixture, id, operator);
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nessuna", "soloPrestazione", "soloStanza"})
    void uc12PermitsIncompleteConfigurationButUc04StillExcludesIt(String configuration) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET id_prestazione = ?, id_stanza = ? WHERE id_disponibilita = ?",
                    configuration.equals("soloPrestazione") ? fixture.prestazione : null,
                    configuration.equals("soloStanza") ? fixture.stanza : null, fixture.slot);
            fixture.repository.rifiutaAtomically(id, operator);
            assertRejection(fixture, id, operator);
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @Test
    void uc12ReleasedHistoryDoesNotHideAnotherBlockingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            fixture.history(StatoPrenotazione.CONFERMATA);
            fixture.repository.rifiutaAtomically(id, fixture.operator("OPERATORE_DESK"));
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @Test
    void uc12ReadsClockAfterWaitingAndCanRejectAnExpiredSlot() throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            List<Object> before = fixture.unchanged(id);
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<RifiutoPrenotazioneResponse> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .rifiutaAtomically(id, operator));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                RifiutoPrenotazioneResponse rejected = result.get(20, TimeUnit.SECONDS);
                assertEquals(StatoPrenotazione.RIFIUTATA, rejected.getStato());
                assertEquals(START, rejected.getDataOraGestione());
                assertEquals(1, clock.reads.get());
                assertEquals(before, fixture.unchanged(id));
                assertEquals(0, fixture.requestCount());

            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc12ConcurrentConfirmationAndRejectionHaveOneWinner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int confirmOperator = fixture.operator("OPERATORE_DESK");
            int rejectOperator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> confirm = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.confermaAtomically(id, confirmOperator); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                Future<Boolean> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.rifiutaAtomically(id, rejectOperator); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                boolean confirmed = confirm.get(20, TimeUnit.SECONDS);
                boolean rejected = reject.get(20, TimeUnit.SECONDS);
                assertNotEquals(confirmed, rejected);
                if (confirmed) { assertConfirmation(fixture, id, confirmOperator); }
                else { assertRejection(fixture, id, rejectOperator); }
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc12ConcurrentRejectionAndNewRequestRespectSlotLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<RifiutoPrenotazioneResponse> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return fixture.repository.rifiutaAtomically(id, operator);
                });
                Future<Boolean> create = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(StatoPrenotazione.RIFIUTATA, reject.get(20, TimeUnit.SECONDS).getStato());
                boolean created = create.get(20, TimeUnit.SECONDS);
                assertRejection(fixture, id, operator);
                assertEquals(created ? 1 : 0, fixture.requestCount());
                assertEquals(created ? 2 : 1, fixture.bookingCount());
                if (!created) { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); }
                assertEquals(1, fixture.requestCount());
                assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = "RICHIESTA", mode = EnumSource.Mode.EXCLUDE)
    void uc13RejectsEveryNonRequestStateWithoutChanges(StatoPrenotazione stato) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(stato);
            int operator = fixture.operator("OPERATORE_DESK");
            int id = fixture.onlyBookingId();
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc13RejectsMissingOperatorAndMissingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int missing = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM utente WHERE id_utente = ?", missing);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.annullaAtomically(id, missing, StatoPrenotazione.RICHIESTA));
            int valid = fixture.operator("OPERATORE_DESK");
            fixture.update("DELETE FROM prenotazione WHERE id_prenotazione = ?", id);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.annullaAtomically(id, valid, StatoPrenotazione.RICHIESTA));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "AMMINISTRATORE"})
    void uc13RejectsUnpermittedRoleWithoutChanges(String role) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator(role.equals("NULL") ? null : role);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(OperazioneNonAutorizzataException.class, () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc13RejectsIncoherentRequestsWithoutRepair(String scenario) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (scenario.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = ?, data_ora_gestione = ? WHERE id_prenotazione = ?",
                        operator, NOW.minusHours(1), id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", fixture.medico, id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc13ConcurrentCancellationsHaveOneWinnerAndKeepWinnerData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Integer> operators = List.of(fixture.operator("OPERATORE_DESK"), fixture.operator("OPERATORE_DESK"));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            List<Future<AnnullamentoPrenotazioneResponse>> futures = new ArrayList<>();
            try {
                for (int operator : operators) {
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try { return fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA); }
                        catch (ConflittoPrenotazioneException e) { return null; }
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                AnnullamentoPrenotazioneResponse a = futures.getFirst().get(20, TimeUnit.SECONDS);
                AnnullamentoPrenotazioneResponse b = futures.getLast().get(20, TimeUnit.SECONDS);
                assertNotEquals(a == null, b == null);
                AnnullamentoPrenotazioneResponse winner = a != null ? a : b;
                assertCancellation(fixture, id, winner.getIdOperatore());
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc13FailedCommitRollsBackAllThreeFields() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            List<List<Object>> before = fixture.snapshot();
            AtomicBoolean attempted = new AtomicBoolean();
            AtomicBoolean rolledBack = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    attempted.set(true);
                                    throw new SQLException("Commit simulato fallito.");
                                }
                                if (method.getName().equals("rollback")) { rolledBack.set(true); }
                                return invoke(real, method, args);
                            });
                }
            };
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(failing, FIXED).annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertTrue(attempted.get());
            assertTrue(rolledBack.get());
            assertEquals(before, fixture.snapshot());
            fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
            assertCancellation(fixture, id, operator);
        }
    }

    private static void assertCancellation(Fixture fixture, int id, int operator) throws SQLException {
        try (Connection connection = fixture.factory.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("ANNULLATA", rows.getString("stato"));
                assertEquals(operator, rows.getInt("gestita_da"));
                assertEquals(NOW, rows.getObject("data_ora_gestione", LocalDateTime.class));
                assertNull(rows.getObject("id_medico_effettivo"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void uc13CancelsRequestKeepsOtherFieldsAndReleasesFutureConfiguredSlot() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int operator = fixture.operator("OPERATORE_DESK");
            int otherOperator = fixture.operator("OPERATORE_DESK");
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            List<Object> before = fixture.unchanged(id);
            MutableClock clock = new MutableClock(FIXED.instant());
            AnnullamentoPrenotazioneResponse result = new PrenotazioneRepository(fixture.factory, clock).annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
            assertEquals(id, result.getIdPrenotazione());
            assertEquals(StatoPrenotazione.ANNULLATA, result.getStato());
            assertEquals(operator, result.getIdOperatore());
            assertEquals(NOW, result.getDataOraGestione());
            assertEquals(1, clock.reads.get());
            assertEquals(before, fixture.unchanged(id));
            assertCancellation(fixture, id, operator);
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
            assertEquals(1, new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).size());
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.annullaAtomically(id, otherOperator, StatoPrenotazione.RICHIESTA));
            assertCancellation(fixture, id, operator);
            fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot);
            assertEquals(1, fixture.requestCount());
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 0})
    void uc13PermitsPastAndStartedAppointmentsButUc04StillExcludesThem(int hours) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    NOW.plusHours(hours), NOW.plusHours(hours + 1), fixture.slot);
            List<Object> before = fixture.unchanged(id);
            fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
            assertEquals(before, fixture.unchanged(id));
            assertCancellation(fixture, id, operator);
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nessuna", "soloPrestazione", "soloStanza"})
    void uc13PermitsIncompleteConfigurationButUc04StillExcludesIt(String configuration) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.update("UPDATE disponibilita SET id_prestazione = ?, id_stanza = ? WHERE id_disponibilita = ?",
                    configuration.equals("soloPrestazione") ? fixture.prestazione : null,
                    configuration.equals("soloStanza") ? fixture.stanza : null, fixture.slot);
            fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
            assertCancellation(fixture, id, operator);
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @Test
    void uc13ReleasedHistoryDoesNotHideAnotherBlockingBooking() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            fixture.history(StatoPrenotazione.CONFERMATA);
            fixture.repository.annullaAtomically(id, fixture.operator("OPERATORE_DESK"), StatoPrenotazione.RICHIESTA);
            assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
        }
    }

    @Test
    void uc13ReadsClockAfterWaitingAndCanCancelAnExpiredRequest() throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            List<Object> before = fixture.unchanged(id);
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<AnnullamentoPrenotazioneResponse> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                AnnullamentoPrenotazioneResponse rejected = result.get(20, TimeUnit.SECONDS);
                assertEquals(StatoPrenotazione.ANNULLATA, rejected.getStato());
                assertEquals(START, rejected.getDataOraGestione());
                assertEquals(1, clock.reads.get());
                assertEquals(before, fixture.unchanged(id));
                assertEquals(0, fixture.requestCount());

            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc13ConcurrentConfirmationAndCancellationHaveOneWinner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int confirmOperator = fixture.operator("OPERATORE_DESK");
            int rejectOperator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> confirm = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.confermaAtomically(id, confirmOperator); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                Future<Boolean> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.annullaAtomically(id, rejectOperator, StatoPrenotazione.RICHIESTA); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                boolean confirmed = confirm.get(20, TimeUnit.SECONDS);
                boolean rejected = reject.get(20, TimeUnit.SECONDS);
                assertNotEquals(confirmed, rejected);
                if (confirmed) { assertConfirmation(fixture, id, confirmOperator); }
                else { assertCancellation(fixture, id, rejectOperator); }
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc13ConcurrentCancellationAndNewRequestRespectSlotLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<AnnullamentoPrenotazioneResponse> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
                });
                Future<Boolean> create = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(StatoPrenotazione.ANNULLATA, reject.get(20, TimeUnit.SECONDS).getStato());
                boolean created = create.get(20, TimeUnit.SECONDS);
                assertCancellation(fixture, id, operator);
                assertEquals(created ? 1 : 0, fixture.requestCount());
                assertEquals(created ? 2 : 1, fixture.bookingCount());
                if (!created) { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); }
                assertEquals(1, fixture.requestCount());
                assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc13ConfirmedCancellationPreservesAssignedDoctorAndReplacesManagement() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int confirming = fixture.operator("OPERATORE_DESK");
            int cancelling = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, confirming);
            List<Object> before = fixture.unchanged(id);
            MutableClock clock = new MutableClock(NOW.plusMinutes(5).toInstant(ZoneOffset.UTC));
            AnnullamentoPrenotazioneResponse result = new PrenotazioneRepository(fixture.factory, clock)
                    .annullaAtomically(id, cancelling, StatoPrenotazione.CONFERMATA);
            assertEquals(StatoPrenotazione.ANNULLATA, result.getStato());
            assertEquals(cancelling, result.getIdOperatore());
            assertEquals(NOW.plusMinutes(5), result.getDataOraGestione());
            assertEquals(1, clock.reads.get());
            assertEquals(before, fixture.unchanged(id));
            try (Connection connection = fixture.factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione = ?")) {
                statement.setInt(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(fixture.medico, rows.getObject("id_medico_effettivo", Integer.class));
                    assertEquals(cancelling, rows.getInt("gestita_da"));
                    assertEquals(result.getDataOraGestione(), rows.getObject("data_ora_gestione", LocalDateTime.class));
                }
            }
            List<List<Object>> cancelled = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, confirming, StatoPrenotazione.CONFERMATA));
            assertEquals(cancelled, fixture.snapshot());
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
            assertEquals(1, new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).size());
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPagamento.class)
    void uc13PaymentsOnRequestsAlwaysBlockIncludingFailed(StatoPagamento paymentState) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.payment(id, paymentState);
            List<List<Object>> before = fixture.snapshot();
            List<List<Object>> payments = fixture.paymentSnapshot(id);
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
            assertEquals(payments, fixture.paymentSnapshot(id));
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPagamento.class)
    void uc13ConfirmedOnlyFailedPaymentsPermitCancellation(StatoPagamento paymentState) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, operator);
            fixture.payment(id, paymentState);
            fixture.payment(id, StatoPagamento.FALLITO);
            List<List<Object>> before = fixture.snapshot();
            List<List<Object>> payments = fixture.paymentSnapshot(id);
            if (paymentState == StatoPagamento.FALLITO) {
                assertEquals(StatoPrenotazione.ANNULLATA,
                        fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA).getStato());
            } else {
                assertThrows(ConflittoPrenotazioneException.class,
                        () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA));
                assertEquals(before, fixture.snapshot());
            }
            assertEquals(payments, fixture.paymentSnapshot(id));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void uc13ConfirmedPastAndExactClockBoundaryAreRejected(int minutes) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, operator);
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    NOW.plusMinutes(minutes), NOW.plusMinutes(minutes).plusHours(1), fixture.slot);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc13ConfirmedMissingManagementOrDoctorConflicts(String missing) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, operator);
            if (missing.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = NULL, data_ora_gestione = NULL WHERE id_prenotazione = ?", id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = NULL WHERE id_prenotazione = ?", id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc13ExpectedStateNeverSilentlyCancelsAConcurrentConfirmation() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA));
            fixture.repository.confermaAtomically(id, operator);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc13ConfirmedReadsClockOnlyAfterWaitingForSlotLock() throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, operator);
            List<List<Object>> before = fixture.snapshot();
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<AnnullamentoPrenotazioneResponse> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .annullaAtomically(id, operator, StatoPrenotazione.CONFERMATA));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoPrenotazioneException.class, error.getCause());
                assertEquals(1, clock.reads.get());
                assertEquals(before, fixture.snapshot());
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void uc13ConcurrentRejectionAndCancellationHaveOneWinner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int confirmOperator = fixture.operator("OPERATORE_DESK");
            int rejectOperator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> confirm = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.rifiutaAtomically(id, confirmOperator); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                Future<Boolean> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.annullaAtomically(id, rejectOperator, StatoPrenotazione.RICHIESTA); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                boolean confirmed = confirm.get(20, TimeUnit.SECONDS);
                boolean rejected = reject.get(20, TimeUnit.SECONDS);
                assertNotEquals(confirmed, rejected);
                if (confirmed) { assertRejection(fixture, id, confirmOperator); }
                else { assertCancellation(fixture, id, rejectOperator); }
                assertEquals(1, fixture.bookingCount());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void uc07CancelsOnlyStateAndPreservesAllOtherFields(StatoPrenotazione expected) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW.minusDays(1));
            if (expected == StatoPrenotazione.CONFERMATA) {
                fixture.repository.confermaAtomically(id, fixture.operator("OPERATORE_DESK"));
            }
            List<List<Object>> before = fixture.snapshot();
            MutableClock clock = new MutableClock(FIXED.instant());
            AnnullamentoPazienteResponse response = new PrenotazioneRepository(fixture.factory, clock)
                    .annullaPazienteAtomically(id, patient, expected);
            assertEquals(id, response.getIdPrenotazione());
            assertEquals(StatoPrenotazione.ANNULLATA, response.getStato());
            assertEquals(1, clock.reads.get());
            assertOnlyStateChanged(before, fixture.snapshot());
            assertTrue(fixture.repository.findRichieste(fixture.medico, null, null, null).isEmpty());
            assertEquals(1, new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).size());
            List<List<Object>> cancelled = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, patient, expected));
            assertEquals(cancelled, fixture.snapshot());
            fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot);
            assertEquals(1, fixture.requestCount());
        }
    }

    private static void assertOnlyStateChanged(List<List<Object>> before, List<List<Object>> after) {
        /* SELECT * segue l'ordine dello schema: verificare la colonna dello stato. */
        List<List<Object>> expected = new ArrayList<>();
        for (List<Object> row : before) { expected.add(new ArrayList<>(row)); }
        expected.getFirst().set(5, "ANNULLATA");
        assertEquals(expected, after);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"RICHIESTA,FALLITO", "RICHIESTA,IN_ATTESA", "RICHIESTA,PAGATO",
            "CONFERMATA,FALLITO", "CONFERMATA,IN_ATTESA", "CONFERMATA,PAGATO"})
    void uc07PaymentRulesPreserveEveryPayment(StatoPrenotazione expected, StatoPagamento payment) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW);
            if (expected == StatoPrenotazione.CONFERMATA) {
                fixture.repository.confermaAtomically(id, fixture.operator("OPERATORE_DESK"));
            }
            fixture.payment(id, payment);
            fixture.payment(id, StatoPagamento.FALLITO);
            List<List<Object>> before = fixture.snapshot();
            List<List<Object>> payments = fixture.paymentSnapshot(id);
            if (expected == StatoPrenotazione.CONFERMATA && payment == StatoPagamento.FALLITO) {
                fixture.repository.annullaPazienteAtomically(id, patient, expected);
                assertOnlyStateChanged(before, fixture.snapshot());
            } else {
                assertThrows(ConflittoPrenotazioneException.class,
                        () -> fixture.repository.annullaPazienteAtomically(id, patient, expected));
                assertEquals(before, fixture.snapshot());
            }
            assertEquals(payments, fixture.paymentSnapshot(id));
        }
    }

    @Test
    void uc07OtherPatientAndMissingBookingHaveSameNotFoundError() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int other = fixture.pazienti.getLast();
            List<List<Object>> before = fixture.snapshot();
            RisorsaNonTrovataException alien = assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, other, StatoPrenotazione.CONFERMATA));
            assertEquals(before, fixture.snapshot());
            fixture.update("DELETE FROM prenotazione WHERE id_prenotazione = ?", id);
            RisorsaNonTrovataException absent = assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, other, StatoPrenotazione.RICHIESTA));
            assertEquals(absent.getMessage(), alien.getMessage());
            int notPatient = fixture.operator("OPERATORE_DESK");
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, notPatient, StatoPrenotazione.RICHIESTA));
            fixture.update("DELETE FROM utente WHERE id_utente = ?", notPatient);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, notPatient, StatoPrenotazione.RICHIESTA));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"RICHIESTA,-1", "RICHIESTA,0", "CONFERMATA,-1", "CONFERMATA,0"})
    void uc07BothStatesRejectPastAndExactClockBoundary(StatoPrenotazione expected, int minutes) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW.minusDays(1));
            if (expected == StatoPrenotazione.CONFERMATA) {
                fixture.repository.confermaAtomically(id, fixture.operator("OPERATORE_DESK"));
            }
            fixture.update("UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ?",
                    NOW.plusMinutes(minutes), NOW.plusMinutes(minutes).plusHours(1), fixture.slot);
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, patient, expected));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RIFIUTATA", "ANNULLATA", "EFFETTUATA", "PAZIENTE_ASSENTE", "NON_EROGATA"})
    void uc07RejectsTerminalStates(StatoPrenotazione state) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(state);
            int id = fixture.onlyBookingId();
            List<List<Object>> before = fixture.snapshot();
            for (StatoPrenotazione expected : List.of(StatoPrenotazione.RICHIESTA, StatoPrenotazione.CONFERMATA)) {
                assertThrows(ConflittoPrenotazioneException.class,
                        () -> fixture.repository.annullaPazienteAtomically(id, fixture.pazienti.getFirst(), expected));
            }
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc07ExpectedStateProtectsAgainstConfirmation() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW);
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, patient, StatoPrenotazione.CONFERMATA));
            fixture.repository.confermaAtomically(id, fixture.operator("OPERATORE_DESK"));
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, patient, StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"conferma", "rifiuta", "annulla", "paziente"})
    void uc07ConcurrentChangesHaveOneWinner(String operation) throws Exception {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> a = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.annullaPazienteAtomically(id, patient, StatoPrenotazione.RICHIESTA); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                Future<Boolean> b = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        switch (operation) {
                            case "conferma" -> fixture.repository.confermaAtomically(id, operator);
                            case "rifiuta" -> fixture.repository.rifiutaAtomically(id, operator);
                            case "annulla" -> fixture.repository.annullaAtomically(id, operator, StatoPrenotazione.RICHIESTA);
                            default -> fixture.repository.annullaPazienteAtomically(id, patient, StatoPrenotazione.RICHIESTA);
                        }
                        return true;
                    } catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                boolean patientWon = a.get(20, TimeUnit.SECONDS);
                assertNotEquals(patientWon, b.get(20, TimeUnit.SECONDS));
                if (patientWon || operation.equals("paziente")) {
                    try (Connection connection = fixture.factory.getConnection();
                         PreparedStatement statement = connection.prepareStatement("SELECT stato, gestita_da, data_ora_gestione, id_medico_effettivo FROM prenotazione WHERE id_prenotazione = ?")) {
                        statement.setInt(1, id);
                        try (ResultSet rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals("ANNULLATA", rows.getString("stato"));
                            assertNull(rows.getObject("gestita_da"));
                            assertNull(rows.getObject("data_ora_gestione"));
                            assertNull(rows.getObject("id_medico_effettivo"));
                        }
                    }
                } else if (operation.equals("conferma")) { assertConfirmation(fixture, id, operator); }
                else if (operation.equals("rifiuta")) { assertRejection(fixture, id, operator); }
                else { assertCancellation(fixture, id, operator); }
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc07RejectsIncoherentRequestsWithoutRepair(String scenario) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (scenario.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = ?, data_ora_gestione = ? WHERE id_prenotazione = ?",
                        operator, NOW.minusHours(1), id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", fixture.medico, id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class, () -> fixture.repository.annullaPazienteAtomically(id, fixture.pazienti.getFirst(), StatoPrenotazione.RICHIESTA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gestione", "medico"})
    void uc07ConfirmedMissingManagementOrDoctorConflicts(String missing) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            fixture.repository.confermaAtomically(id, operator);
            if (missing.equals("gestione")) {
                fixture.update("UPDATE prenotazione SET gestita_da = NULL, data_ora_gestione = NULL WHERE id_prenotazione = ?", id);
            } else {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = NULL WHERE id_prenotazione = ?", id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(ConflittoPrenotazioneException.class,
                    () -> fixture.repository.annullaPazienteAtomically(id, fixture.pazienti.getFirst(), StatoPrenotazione.CONFERMATA));
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void uc07ReadsClockOnlyAfterWaitingForSlotLock(StatoPrenotazione expected) throws Exception {
        try (Fixture fixture = new Fixture(); Connection holder = fixture.factory.getConnection()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            if (expected == StatoPrenotazione.CONFERMATA) { fixture.repository.confermaAtomically(id, operator); }
            List<List<Object>> before = fixture.snapshot();
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
                statement.setInt(1, fixture.slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            MutableClock clock = new MutableClock(FIXED.instant());
            CountDownLatch attemptingLock = new CountDownLatch(1);
            ConnectionFactory observed = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(real, method, args);
                                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                        && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                    PreparedStatement prepared = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class}, (ps, operation, parameters) -> {
                                                if (operation.getName().equals("executeQuery")) {
                                                    attemptingLock.countDown();
                                                }
                                                return invoke(prepared, operation, parameters);
                                            });
                                }
                                return result;
                            });
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<AnnullamentoPazienteResponse> result = worker.submit(() -> new PrenotazioneRepository(observed, clock)
                        .annullaPazienteAtomically(id, fixture.pazienti.getFirst(), expected));
                assertTrue(attemptingLock.await(10, TimeUnit.SECONDS));
                assertEquals(0, clock.reads.get());
                assertFalse(result.isDone());
                clock.instant.set(START.toInstant(ZoneOffset.UTC));
                holder.commit();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoPrenotazioneException.class, error.getCause());
                assertEquals(1, clock.reads.get());
                assertEquals(before, fixture.snapshot());
            } finally {
                holder.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void uc07FailedCommitRollsBackOnlyState(StatoPrenotazione expected) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (expected == StatoPrenotazione.CONFERMATA) { fixture.repository.confermaAtomically(id, operator); }
            List<List<Object>> before = fixture.snapshot();
            AtomicBoolean attempted = new AtomicBoolean();
            AtomicBoolean rolledBack = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection real = fixture.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) {
                                    attempted.set(true);
                                    throw new SQLException("Commit simulato fallito.");
                                }
                                if (method.getName().equals("rollback")) { rolledBack.set(true); }
                                return invoke(real, method, args);
                            });
                }
            };
            assertThrows(SQLException.class, () -> new PrenotazioneRepository(failing, FIXED).annullaPazienteAtomically(id, fixture.pazienti.getFirst(), expected));
            assertTrue(attempted.get());
            assertTrue(rolledBack.get());
            assertEquals(before, fixture.snapshot());
            fixture.repository.annullaPazienteAtomically(id, fixture.pazienti.getFirst(), expected);
            assertOnlyStateChanged(before, fixture.snapshot());
        }
    }

    @Test
    void uc07ConcurrentCancellationAndNewRequestRespectSlotLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW.minusDays(1));
            int operator = fixture.operator("OPERATORE_DESK");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<AnnullamentoPazienteResponse> reject = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return fixture.repository.annullaPazienteAtomically(id, fixture.pazienti.getFirst(), StatoPrenotazione.RICHIESTA);
                });
                Future<Boolean> create = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); return true; }
                    catch (ConflittoPrenotazioneException e) { return false; }
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(StatoPrenotazione.ANNULLATA, reject.get(20, TimeUnit.SECONDS).getStato());
                boolean created = create.get(20, TimeUnit.SECONDS);
                try (Connection connection = fixture.factory.getConnection();
                     PreparedStatement statement = connection.prepareStatement("SELECT stato, gestita_da FROM prenotazione WHERE id_prenotazione = ?")) {
                    statement.setInt(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("ANNULLATA", rows.getString("stato"));
                        assertNull(rows.getObject("gestita_da"));
                    }
                }
                assertEquals(created ? 1 : 0, fixture.requestCount());
                assertEquals(created ? 2 : 1, fixture.bookingCount());
                if (!created) { fixture.repository.creaRichiestaAtomically(fixture.pazienti.getLast(), fixture.slot); }
                assertEquals(1, fixture.requestCount());
                assertTrue(new DisponibilitaRepository(fixture.factory).findPrenotabili(fixture.medico, null, null, NOW).isEmpty());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }


    @Test
    void uc18EmptyAgendaAndUnknownMedicoAreDistinct() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            LocalDate day = NOW.toLocalDate();
            assertTrue(fixture.repository.findAgenda(fixture.medico, day, day).isEmpty());
            int nonMedico = fixture.operator(null);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.findAgenda(nonMedico, day, day));
            fixture.update("DELETE FROM utente WHERE id_utente = ?", nonMedico);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.repository.findAgenda(nonMedico, day, day));
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc18AttributesStatesAndExcludesCancelledRejected(StatoPrenotazione state) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int substitute = fixture.anotherMedico();
            int operator = fixture.operator("OPERATORE_DESK");
            if (state != StatoPrenotazione.RICHIESTA) {
                fixture.update("UPDATE prenotazione SET stato = ?, gestita_da = ?, data_ora_gestione = ?, id_medico_effettivo = ? WHERE id_prenotazione = ?",
                        state.name(), operator, NOW, substitute, id);
            }
            List<List<Object>> before = fixture.snapshot();
            LocalDate day = NOW.toLocalDate();
            List<AppuntamentoAgendaResponse> owner = fixture.repository.findAgenda(fixture.medico, day, day);
            List<AppuntamentoAgendaResponse> replacement = fixture.repository.findAgenda(substitute, day, day);
            if (state == StatoPrenotazione.RICHIESTA) {
                assertEquals(1, owner.size()); assertTrue(replacement.isEmpty());
                assertNull(owner.getFirst().getIdMedicoEffettivo());
            } else if (state == StatoPrenotazione.ANNULLATA || state == StatoPrenotazione.RIFIUTATA) {
                assertTrue(owner.isEmpty()); assertTrue(replacement.isEmpty());
            } else {
                assertTrue(owner.isEmpty()); assertEquals(1, replacement.size());
                AppuntamentoAgendaResponse row = replacement.getFirst();
                assertEquals(substitute, row.getIdMedicoEffettivo());
                assertEquals(fixture.medico, row.getIdMedicoTitolare());
                assertEquals(state, row.getStato());
                assertEquals(id, row.getIdPrenotazione());
                assertEquals(fixture.slot, row.getIdDisponibilita());
                assertEquals(START, row.getDataOraInizio());
                assertEquals(START.plusHours(1), row.getDataOraFine());
                assertEquals(fixture.pazienti.getFirst(), row.getIdPaziente());
                assertEquals("Paziente", row.getNomePaziente()); assertEquals("UC05", row.getCognomePaziente());
                assertEquals(fixture.prestazione, row.getIdPrestazione()); assertEquals("Visita UC05", row.getNomePrestazione());
                assertEquals(30, row.getDurataPrestazione());
                assertEquals(fixture.stanza, row.getIdStanza()); assertNotNull(row.getNomeStanza());
            }
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc18CalendarBoundsPastAppointmentsStableOrderAndNoJoinDuplicates() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            LocalDate day = NOW.toLocalDate();
            int before = fixture.slotAt(day.minusDays(1).atTime(23, 59, 59));
            int midnight = fixture.slotAt(day.atStartOfDay());
            int sameTime = fixture.slotAt(START);
            int last = fixture.slotAt(day.atTime(23, 59, 59));
            int tomorrow = fixture.slotAt(day.plusDays(1).atStartOfDay());
            fixture.request(before, fixture.pazienti.getFirst(), NOW);
            int firstId = fixture.request(midnight, fixture.pazienti.getFirst(), NOW);
            int baseId = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int sameSlotA = fixture.request(sameTime, fixture.pazienti.getLast(), NOW);
            int sameSlotB = fixture.request(sameTime, fixture.pazienti.getFirst(), NOW);
            int lastId = fixture.request(last, fixture.pazienti.getFirst(), NOW);
            int nextId = fixture.request(tomorrow, fixture.pazienti.getFirst(), NOW);
            /* Righe occupanti duplicate inserite appositamente: DISTINCT non deve nascondere anomalie del database. */
            List<AppuntamentoAgendaResponse> rows = fixture.repository.findAgenda(fixture.medico, day, day);
            assertEquals(List.of(firstId, baseId, sameSlotA, sameSlotB, lastId),
                    rows.stream().map(AppuntamentoAgendaResponse::getIdPrenotazione).toList());
            assertEquals(rows.size(), rows.stream().map(AppuntamentoAgendaResponse::getIdPrenotazione).distinct().count());
            assertEquals(fixture.pazienti.getLast(), rows.get(2).getIdPaziente());
            assertEquals(day.atStartOfDay(), rows.getFirst().getDataOraInizio());
            List<AppuntamentoAgendaResponse> inclusive = fixture.repository.findAgenda(fixture.medico, day, day.plusDays(1));
            assertEquals(nextId, inclusive.getLast().getIdPrenotazione());
            assertEquals(6, inclusive.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prestazione", "stanza", "entrambi"})
    void uc18PartialConfigurationRemainsVisible(String missing) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            if (!missing.equals("stanza")) {
                fixture.update("UPDATE disponibilita SET id_prestazione = NULL WHERE id_disponibilita = ?", fixture.slot);
            }
            if (!missing.equals("prestazione")) {
                fixture.update("UPDATE disponibilita SET id_stanza = NULL WHERE id_disponibilita = ?", fixture.slot);
            }
            List<List<Object>> before = fixture.snapshot();
            List<AppuntamentoAgendaResponse> rows = fixture.repository.findAgenda(fixture.medico, NOW.toLocalDate(), NOW.toLocalDate());
            assertEquals(1, rows.size()); assertEquals(id, rows.getFirst().getIdPrenotazione());
            if (!missing.equals("stanza")) {
                assertNull(rows.getFirst().getIdPrestazione()); assertNull(rows.getFirst().getNomePrestazione());
                assertNull(rows.getFirst().getDurataPrestazione());
            }
            if (!missing.equals("prestazione")) {
                assertNull(rows.getFirst().getIdStanza()); assertNull(rows.getFirst().getNomeStanza());
            }
            assertEquals(before, fixture.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"richiesta-medico", "richiesta-gestione", "confermata-medico", "confermata-gestione"})
    void uc18RelevantIncoherenceFailsWithoutCorrections(String scenario) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            int operator = fixture.operator("OPERATORE_DESK");
            if (scenario.startsWith("confermata")) {
                fixture.repository.confermaAtomically(id, operator);
                if (scenario.endsWith("medico")) {
                    fixture.update("UPDATE prenotazione SET id_medico_effettivo = NULL WHERE id_prenotazione = ?", id);
                } else {
                    fixture.update("UPDATE prenotazione SET gestita_da = NULL, data_ora_gestione = NULL WHERE id_prenotazione = ?", id);
                }
            } else if (scenario.endsWith("medico")) {
                fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", fixture.medico, id);
            } else {
                fixture.update("UPDATE prenotazione SET gestita_da = ?, data_ora_gestione = ? WHERE id_prenotazione = ?", operator, NOW, id);
            }
            List<List<Object>> before = fixture.snapshot();
            assertThrows(SQLException.class, () -> fixture.repository.findAgenda(fixture.medico, NOW.toLocalDate(), NOW.toLocalDate()));
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc18UnrelatedAnomaliesDoNotBreakMedicoOrPeriodAndCancelledStatesStayExcluded() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            fixture.update("UPDATE prenotazione SET stato = 'CONFERMATA' WHERE id_prenotazione = ?", id);
            int another = fixture.anotherMedico();
            LocalDate day = NOW.toLocalDate();
            assertTrue(fixture.repository.findAgenda(another, day, day).isEmpty());
            assertTrue(fixture.repository.findAgenda(fixture.medico, day.plusDays(1), day.plusDays(1)).isEmpty());
            fixture.update("UPDATE prenotazione SET stato = 'ANNULLATA' WHERE id_prenotazione = ?", id);
            assertTrue(fixture.repository.findAgenda(fixture.medico, day, day).isEmpty());
            fixture.update("UPDATE prenotazione SET stato = 'RIFIUTATA' WHERE id_prenotazione = ?", id);
            assertTrue(fixture.repository.findAgenda(fixture.medico, day, day).isEmpty());
        }
    }

    @Test
    void uc06ExistingPatientEmptyAndUnknownPatient() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.repository.findByPaziente(fixture.pazienti.getFirst(), null, null, null, NOW).isEmpty());
            int noPatient = fixture.operator(null);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.findByPaziente(noPatient, null, null, null, NOW));
            fixture.update("DELETE FROM utente WHERE id_utente = ?", noPatient);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> fixture.repository.findByPaziente(noPatient, null, null, null, NOW));
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc06IncludesEveryStateAndIsolatesPatient(StatoPrenotazione state) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            fixture.history(state);
            int id = fixture.onlyBookingId();
            int other = fixture.request(fixture.slotAt(START.plusHours(1)), fixture.pazienti.getLast(), NOW);
            List<List<Object>> before = fixture.snapshot();
            List<AppuntamentoPazienteResponse> rows = fixture.repository.findByPaziente(fixture.pazienti.getFirst(), null, null, null, NOW);
            assertEquals(1, rows.size()); assertEquals(id, rows.getFirst().getIdPrenotazione());
            assertEquals(state, rows.getFirst().getStato());
            assertNull(rows.getFirst().getIdMedicoEffettivo());
            assertEquals(1, fixture.repository.findByPaziente(fixture.pazienti.getFirst(), state, null, null, NOW).size());
            assertEquals(other, fixture.repository.findByPaziente(fixture.pazienti.getLast(), null, null, null, NOW).getFirst().getIdPrenotazione());
            assertEquals(before, fixture.snapshot());
        }
    }

    @Test
    void uc06FutureAndInProgressAscThenPastDescWithExactEndBoundary() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int old = fixture.request(fixture.slotAt(NOW.minusHours(3)), patient, NOW.minusDays(2));
            int ended = fixture.request(fixture.slotAt(NOW.minusHours(1)), patient, NOW.minusDays(1));
            int endsLater = fixture.request(fixture.slotAt(NOW.minusHours(1).plusSeconds(1)), patient, NOW.minusDays(1));
            int ongoing = fixture.request(fixture.slotAt(NOW.minusMinutes(30)), patient, NOW.minusDays(1));
            int future = fixture.request(fixture.slot, patient, NOW);
            int last = fixture.request(fixture.slotAt(START.plusHours(2)), patient, NOW);
            fixture.update("UPDATE prenotazione SET stato = 'ANNULLATA' WHERE id_prenotazione = ?", ongoing);
            fixture.update("UPDATE prenotazione SET stato = 'RIFIUTATA' WHERE id_prenotazione = ?", old);
            assertEquals(List.of(endsLater, ongoing, future, last, ended, old),
                    fixture.repository.findByPaziente(patient, null, null, null, NOW).stream()
                            .map(AppuntamentoPazienteResponse::getIdPrenotazione).toList());
            assertEquals(List.of(last, future, ongoing, endsLater, ended, old),
                    fixture.repository.findByPaziente(patient, null, null, null, START.plusHours(4)).stream()
                            .map(AppuntamentoPazienteResponse::getIdPrenotazione).toList());
        }
    }

    @Test
    void uc06TieBreaksByRequestTimeThenIdDescendingInBothGroups() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int oldRequest = fixture.request(fixture.slot, patient, NOW.minusDays(2));
            int a = fixture.request(fixture.slot, patient, NOW.minusDays(1));
            int b = fixture.request(fixture.slot, patient, NOW.minusDays(1));
            for (LocalDateTime now : List.of(NOW, START.plusHours(1))) {
                List<AppuntamentoPazienteResponse> rows = fixture.repository.findByPaziente(patient, null, null, null, now);
                assertEquals(List.of(b, a, oldRequest), rows.stream().map(AppuntamentoPazienteResponse::getIdPrenotazione).toList());
                assertEquals(3, rows.size()); /* Righe storiche diverse sullo stesso slot restano distinte. */
            }
        }
    }

    @Test
    void uc06DateStateCombinedFiltersCalendarBoundsAndLongHistory() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            LocalDate day = NOW.toLocalDate();
            fixture.request(fixture.slotAt(day.minusDays(1).atTime(23, 59, 59)), patient, NOW);
            int midnight = fixture.request(fixture.slotAt(day.atStartOfDay()), patient, NOW);
            int middle = fixture.request(fixture.slot, patient, NOW);
            int end = fixture.request(fixture.slotAt(day.atTime(23, 59, 59)), patient, NOW);
            fixture.update("UPDATE prenotazione SET stato = 'RIFIUTATA' WHERE id_prenotazione = ?", end);
            int next = fixture.request(fixture.slotAt(day.plusDays(1).atStartOfDay()), patient, NOW);
            List<AppuntamentoPazienteResponse> single = fixture.repository.findByPaziente(patient, null, day, day, NOW);
            assertEquals(java.util.Set.of(midnight, middle, end),
                    single.stream().map(AppuntamentoPazienteResponse::getIdPrenotazione).collect(java.util.stream.Collectors.toSet()));
            assertEquals(List.of(end), fixture.repository.findByPaziente(patient, StatoPrenotazione.RIFIUTATA, day, day, NOW).stream()
                    .map(AppuntamentoPazienteResponse::getIdPrenotazione).toList());
            assertEquals(3, fixture.repository.findByPaziente(patient, StatoPrenotazione.RICHIESTA, day, day.plusDays(1), NOW).size());
            assertTrue(fixture.repository.findByPaziente(patient, StatoPrenotazione.CONFERMATA, day, day, NOW).isEmpty());
            assertTrue(fixture.repository.findByPaziente(patient, null, day.plusDays(2), day.plusDays(3), NOW).isEmpty());
            List<AppuntamentoPazienteResponse> history = fixture.repository.findByPaziente(patient, null, day.minusYears(1), day.plusYears(1), NOW);
            assertEquals(5, history.size());
            assertTrue(history.stream().anyMatch(row -> row.getIdPrenotazione() == next));
        }
    }

    @Test
    void uc06MapsDoctorsSnapshotPriceAndPreservesAllData() throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int patient = fixture.pazienti.getFirst();
            int id = fixture.request(fixture.slot, patient, NOW.minusDays(1));
            int substitute = fixture.anotherMedico();
            fixture.update("UPDATE utente SET nome = 'Mario', cognome = 'Rossi' WHERE id_utente = ?", fixture.medico);
            fixture.update("UPDATE utente SET nome = 'Anna', cognome = 'Bianchi' WHERE id_utente = ?", substitute);
            fixture.repository.confermaAtomically(id, fixture.operator("OPERATORE_DESK"));
            fixture.update("UPDATE prenotazione SET id_medico_effettivo = ? WHERE id_prenotazione = ?", substitute, id);
            fixture.update("UPDATE prestazione SET prezzo = ? WHERE id_prestazione = ?", new BigDecimal("80.00"), fixture.prestazione);
            List<List<Object>> before = fixture.snapshot();
            List<Object> other = fixture.unchanged(id);
            AppuntamentoPazienteResponse row = fixture.repository.findByPaziente(patient, null, null, null, NOW).getFirst();
            assertEquals(id, row.getIdPrenotazione()); assertEquals(StatoPrenotazione.CONFERMATA, row.getStato());
            assertEquals(NOW.minusDays(1), row.getDataOraRichiesta()); assertEquals(new BigDecimal("50.00"), row.getPrezzo());
            assertEquals(fixture.slot, row.getIdDisponibilita()); assertEquals(START, row.getDataOraInizio());
            assertEquals(START.plusHours(1), row.getDataOraFine());
            assertEquals(fixture.medico, row.getIdMedicoTitolare()); assertEquals("Mario", row.getNomeMedicoTitolare());
            assertEquals("Rossi", row.getCognomeMedicoTitolare());
            assertEquals(substitute, row.getIdMedicoEffettivo()); assertEquals("Anna", row.getNomeMedicoEffettivo());
            assertEquals("Bianchi", row.getCognomeMedicoEffettivo());
            assertEquals(fixture.prestazione, row.getIdPrestazione()); assertEquals("Visita UC05", row.getNomePrestazione());
            assertEquals(30, row.getDurataPrestazione()); assertEquals(fixture.stanza, row.getIdStanza());
            assertTrue(row.getNomeStanza().startsWith("UC05-"));
            assertEquals(before, fixture.snapshot()); assertEquals(other, fixture.unchanged(id));
            fixture.update("UPDATE prenotazione SET stato = 'ANNULLATA' WHERE id_prenotazione = ?", id);
            assertEquals(substitute, fixture.repository.findByPaziente(patient, null, null, null, NOW).getFirst().getIdMedicoEffettivo());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prestazione", "stanza", "entrambi"})
    void uc06OptionalConfigurationAndUnassignedDoctorStayNullable(String missing) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            int id = fixture.request(fixture.slot, fixture.pazienti.getFirst(), NOW);
            if (!missing.equals("stanza")) {
                fixture.update("UPDATE disponibilita SET id_prestazione = NULL WHERE id_disponibilita = ?", fixture.slot);
            }
            if (!missing.equals("prestazione")) {
                fixture.update("UPDATE disponibilita SET id_stanza = NULL WHERE id_disponibilita = ?", fixture.slot);
            }
            List<List<Object>> before = fixture.snapshot();
            AppuntamentoPazienteResponse row = fixture.repository.findByPaziente(fixture.pazienti.getFirst(), null, null, null, NOW).getFirst();
            assertEquals(id, row.getIdPrenotazione());
            assertNull(row.getIdMedicoEffettivo()); assertNull(row.getNomeMedicoEffettivo()); assertNull(row.getCognomeMedicoEffettivo());
            if (!missing.equals("stanza")) {
                assertNull(row.getIdPrestazione()); assertNull(row.getNomePrestazione()); assertNull(row.getDurataPrestazione());
            }
            if (!missing.equals("prestazione")) { assertNull(row.getIdStanza()); assertNull(row.getNomeStanza()); }
            assertEquals(before, fixture.snapshot());
        }
    }

    private static void assertPersistedInitialRequest(Fixture fixture, Prenotazione result) throws SQLException {
        try (Connection connection = fixture.factory.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM prenotazione WHERE id_prenotazione = ?")) {
            statement.setInt(1, result.getIdPrenotazione());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("RICHIESTA", rows.getString("stato"));
                assertNull(rows.getObject("gestita_da"));
                assertNull(rows.getObject("data_ora_gestione"));
                assertNull(rows.getObject("id_medico_effettivo"));
                assertEquals(NOW, rows.getObject("data_ora_richiesta", LocalDateTime.class));
                assertEquals(new BigDecimal("50.00"), rows.getBigDecimal("prezzo"));
                assertEquals(fixture.slot.intValue(), rows.getInt("id_disponibilita"));
                assertEquals(fixture.pazienti.getFirst().intValue(), rows.getInt("id_paziente"));
                assertFalse(rows.next());
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final AtomicInteger reads = new AtomicInteger();

        MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant.get(), zone); }
        @Override public Instant instant() { reads.incrementAndGet(); return instant.get(); }
    }

    private static class Fixture implements AutoCloseable {
        private final ConnectionFactory factory;
        private final PrenotazioneRepository repository;
        private final List<Integer> utenti = new ArrayList<>();
        private final List<Integer> pazienti = new ArrayList<>();
        private final List<Integer> extraSlots = new ArrayList<>();
        private final List<Integer> extraMedici = new ArrayList<>();

        int anotherMedico() throws SQLException {
            int id = user("Sostituto");
            update("INSERT INTO medico (id_utente, specializzazione, numero_iscrizione_ordine) VALUES (?, ?, ?)",
                    id, "Medicina generale", UUID.randomUUID().toString());
            extraMedici.add(id);
            return id;
        }

        void payment(int booking, StatoPagamento state) throws SQLException {
            insert("""
                    INSERT INTO pagamento (id_prenotazione, modalita, importo, stato, creato_il, pagato_il)
                    VALUES (?, 'ONLINE', ?, ?, ?, ?)
                    """, booking, new BigDecimal("50.00"), state.name(), NOW,
                    state == StatoPagamento.PAGATO ? NOW : null);
        }

        List<List<Object>> paymentSnapshot(int booking) throws SQLException {
            List<List<Object>> result = new ArrayList<>();
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM pagamento WHERE id_prenotazione = ? ORDER BY id_pagamento")) {
                statement.setInt(1, booking);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        List<Object> values = new ArrayList<>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) { values.add(rows.getObject(i)); }
                        result.add(values);
                    }
                }
            }
            return result;
        }

        int operator(String role) throws SQLException {
            int id = user("Operatore");
            update("UPDATE utente SET ruolo_gestionale = ? WHERE id_utente = ?", role, id);
            return id;
        }

        int onlyBookingId() throws SQLException {
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT id_prenotazione FROM prenotazione WHERE id_disponibilita = ?")) {
                statement.setInt(1, slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
            }
        }

        List<Object> unchanged(int id) throws SQLException {
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT pr.id_paziente, pr.id_disponibilita, pr.data_ora_richiesta, pr.prezzo,
                                d.*, p.nome, p.durata, p.prezzo, s.nome
                         FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                         JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                         JOIN stanza s ON s.id_stanza = d.id_stanza WHERE pr.id_prenotazione = ?
                         """)) {
                statement.setInt(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    List<Object> values = new ArrayList<>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) { values.add(rows.getObject(i)); }
                    return values;
                }
            }
        }

        int slotAt(LocalDateTime start) throws SQLException {
            int id = insert("""
                    INSERT INTO disponibilita
                    (id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza)
                    VALUES (?, ?, ?, ?, ?)
                    """, medico, start, start.plusHours(1), prestazione, stanza);
            extraSlots.add(id);
            return id;
        }

        int request(int slot, int paziente, LocalDateTime requested) throws SQLException {
            return insert("""
                    INSERT INTO prenotazione
                    (id_paziente, id_disponibilita, stato, data_ora_richiesta, prezzo)
                    VALUES (?, ?, 'RICHIESTA', ?, ?)
                    """, paziente, slot, requested, new BigDecimal("50.00"));
        }

        List<List<Object>> snapshot() throws SQLException {
            List<List<Object>> result = new ArrayList<>();
            // Confronta tutti i valori, inclusi stato e timestamp, non soltanto il numero di righe.
            for (String sql : List.of(
                    "SELECT * FROM prenotazione WHERE id_disponibilita = ? ORDER BY id_prenotazione",
                    "SELECT * FROM disponibilita WHERE id_disponibilita = ?")) {
                try (Connection connection = factory.getConnection();
                     PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            List<Object> values = new ArrayList<>();
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                                values.add(rows.getObject(i));
                            }
                            result.add(values);
                        }
                    }
                }
            }
            return result;
        }

        private Integer medico;
        private Integer prestazione;
        private Integer stanza;
        private Integer slot;

        Fixture() throws SQLException {
            factory = it.medbook.config.TestDatabaseConnections.isolatedFactory();
            repository = new PrenotazioneRepository(factory, FIXED);
            try {
                medico = user("Medico");
                update("INSERT INTO medico (id_utente, specializzazione, numero_iscrizione_ordine) VALUES (?, ?, ?)",
                        medico, "Medicina generale", UUID.randomUUID().toString());
                for (int i = 0; i < 2; i++) {
                    int id = user("Paziente");
                    update("INSERT INTO paziente (id_utente, codice_fiscale, data_nascita) VALUES (?, ?, ?)",
                            id, UUID.randomUUID().toString().replace("-", "").substring(0, 16), LocalDate.of(1990, 1, 1));
                    pazienti.add(id);
                }
                prestazione = insert("INSERT INTO prestazione (nome, durata, prezzo) VALUES (?, ?, ?)",
                        "Visita UC05", 30, new BigDecimal("50.00"));
                stanza = insert("INSERT INTO stanza (nome) VALUES (?)", "UC05-" + UUID.randomUUID());
                update("INSERT INTO medico_prestazione (id_medico, id_prestazione) VALUES (?, ?)", medico, prestazione);
                slot = insert("""
                        INSERT INTO disponibilita
                        (id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza)
                        VALUES (?, ?, ?, ?, ?)
                        """, medico, START, START.plusHours(1), prestazione, stanza);
            } catch (SQLException | RuntimeException e) {
                try { close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }

        private int user(String nome) throws SQLException {
            int id = insert("INSERT INTO utente (nome, cognome, email, password_hash) VALUES (?, ?, ?, ?)",
                    nome, "UC05", "uc05-" + UUID.randomUUID() + "@example.invalid", UUID.randomUUID().toString());
            utenti.add(id);
            return id;
        }

        void history(StatoPrenotazione stato) throws SQLException {
            update("""
                    INSERT INTO prenotazione
                    (id_paziente, id_disponibilita, stato, data_ora_richiesta, prezzo)
                    VALUES (?, ?, ?, ?, ?)
                    """, pazienti.getFirst(), slot, stato.name(), NOW.minusDays(1), new BigDecimal("50.00"));
        }

        int bookingCount() throws SQLException { return count(false); }
        int requestCount() throws SQLException { return count(true); }

        private int count(boolean onlyRequests) throws SQLException {
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita = ?"
                                 + (onlyRequests ? " AND stato = 'RICHIESTA'" : ""))) {
                statement.setInt(1, slot);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
            }
        }

        private int insert(String sql, Object... values) throws SQLException {
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, values);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) { throw new SQLException("ID fixture UC05 non disponibile."); }
                    return keys.getInt(1);
                }
            }
        }

        void update(String sql, Object... values) throws SQLException {
            try (Connection connection = factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                statement.executeUpdate();
            }
        }

        private void bind(PreparedStatement statement, Object[] values) throws SQLException {
            for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
        }

        @Override
        public void close() throws SQLException {
            for (Integer id : extraSlots) {
                update("DELETE pa FROM pagamento pa JOIN prenotazione pr ON pr.id_prenotazione = pa.id_prenotazione WHERE pr.id_disponibilita = ?", id);
                update("DELETE FROM prenotazione WHERE id_disponibilita = ?", id);
                update("DELETE FROM disponibilita WHERE id_disponibilita = ?", id);
            }
            if (slot != null) {
                update("DELETE pa FROM pagamento pa JOIN prenotazione pr ON pr.id_prenotazione = pa.id_prenotazione WHERE pr.id_disponibilita = ?", slot);
                update("DELETE FROM prenotazione WHERE id_disponibilita = ?", slot);
                update("DELETE FROM disponibilita WHERE id_disponibilita = ?", slot);
            }
            if (medico != null) {
                update("DELETE FROM medico_prestazione WHERE id_medico = ?", medico);
                update("DELETE FROM medico WHERE id_utente = ?", medico);
            }
            for (Integer id : extraMedici) {
                update("DELETE FROM medico WHERE id_utente = ?", id);
            }
            for (Integer id : utenti) {
                update("DELETE FROM paziente WHERE id_utente = ?", id);
                update("DELETE FROM utente WHERE id_utente = ?", id);
            }
            if (prestazione != null) { update("DELETE FROM prestazione WHERE id_prestazione = ?", prestazione); }
            if (stanza != null) { update("DELETE FROM stanza WHERE id_stanza = ?", stanza); }
        }
    }
}
