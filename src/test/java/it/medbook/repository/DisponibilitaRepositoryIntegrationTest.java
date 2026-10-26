package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.model.Disponibilita;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.exception.ConflittoPrenotazioneException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DisponibilitaRepositoryIntegrationTest {
    private static final Clock FIXTURE_CLOCK = Clock.fixed(Instant.parse("2030-01-14T00:00:00Z"), ZoneOffset.UTC);
    @Test
    void occupyingStatesSharedWithUc05RemainImmutableAndUnchanged() {
        List<StatoPrenotazione> stati = DisponibilitaRepository.statiBloccanti();
        assertEquals(List.of(StatoPrenotazione.RICHIESTA, StatoPrenotazione.CONFERMATA,
                StatoPrenotazione.EFFETTUATA, StatoPrenotazione.PAZIENTE_ASSENTE,
                StatoPrenotazione.NON_EROGATA), stati);
        assertThrows(UnsupportedOperationException.class, () -> stati.add(StatoPrenotazione.ANNULLATA));
    }

    @Test
    void savesAndReadsUnconfiguredSlotsForControlledMedico() throws SQLException {

        ConnectionFactory factory = it.medbook.config.TestDatabaseConnections.isolatedFactory();
        DisponibilitaRepository repository = new DisponibilitaRepository(factory, FIXTURE_CLOCK);
        String token = UUID.randomUUID().toString();
        String email = "disponibilita-" + token + "@example.invalid";
        LocalDateTime inizio = LocalDateTime.of(2030, 1, 15, 9, 0);
        LocalDateTime fine = inizio.plusHours(1);

        try (Connection connection = factory.getConnection()) {
            Integer idMedico = null;
            try {
                String insertUtente = """
                        INSERT INTO utente (nome, cognome, email, password_hash, ruolo_gestionale)
                        VALUES (?, ?, ?, ?, NULL)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(
                        insertUtente, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, "Medico test");
                    statement.setString(2, "Disponibilita");
                    statement.setString(3, email);
                    // Valore fittizio per il campo obbligatorio, non una credenziale reale.
                    statement.setString(4, UUID.randomUUID().toString());
                    assertEquals(1, statement.executeUpdate());
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        assertTrue(keys.next());
                        idMedico = keys.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO medico (id_utente, specializzazione, numero_iscrizione_ordine) VALUES (?, ?, ?)")) {
                    statement.setInt(1, idMedico);
                    statement.setString(2, "Medicina generale");
                    statement.setString(3, token);
                    assertEquals(1, statement.executeUpdate());
                }

                assertTrue(repository.findByMedico(idMedico).isEmpty());
                // Inserimento in ordine inverso per verificare l'ordinamento della lettura.
                Disponibilita successiva = repository.save(idMedico, fine, fine.plusHours(1));
                Disponibilita creata = repository.save(idMedico, inizio, fine);
                assertNotNull(creata.getIdDisponibilita());
                assertTrue(creata.getIdDisponibilita() > 0);
                assertSlot(creata, idMedico, inizio, fine);

                Disponibilita riletta = repository.findById(creata.getIdDisponibilita()).orElseThrow();
                assertEquals(creata.getIdDisponibilita(), riletta.getIdDisponibilita());
                assertSlot(riletta, idMedico, inizio, fine);
                assertEquals(email, riletta.getMedico().getUtente().getEmail());
                assertEquals("Medicina generale", riletta.getMedico().getSpecializzazione());
                assertEquals(token, riletta.getMedico().getNumeroIscrizioneOrdine());

                List<Disponibilita> slots = repository.findByMedico(idMedico);
                assertEquals(2, slots.size());
                assertEquals(creata.getIdDisponibilita(), slots.get(0).getIdDisponibilita());
                assertEquals(successiva.getIdDisponibilita(), slots.get(1).getIdDisponibilita());
                assertSlot(slots.get(0), idMedico, inizio, fine);
                assertSlot(slots.get(1), idMedico, fine, fine.plusHours(1));
                assertTrue(repository.findById(0).isEmpty());
            } finally {
                // Elimina solo gli slot del medico creato da questa esecuzione.
                if (idMedico != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM disponibilita WHERE id_medico = ?")) {
                        statement.setInt(1, idMedico);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM medico WHERE id_utente = ?")) {
                        statement.setInt(1, idMedico);
                        statement.executeUpdate();
                    }
                }
                // L'email univoca consente la pulizia anche se il recupero dell'ID fallisce.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM utente WHERE email = ?")) {
                    statement.setString(1, email);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    void createsThreeSlotsAtomically() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Disponibilita> saved = fixture.repository.saveAllAtomically(fixture.id, slots(9, 3));
            assertEquals(3, saved.size());
            List<Disponibilita> found = fixture.repository.findByMedico(fixture.id);
            assertEquals(3, found.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(saved.get(i).getIdDisponibilita(), found.get(i).getIdDisponibilita());
                assertSlot(found.get(i), fixture.id, time(9 + i), time(10 + i));
            }
        }
    }

    @Test
    void rejectsExactOverlapWithoutNewRows() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.repository.save(fixture.id, time(9), time(10));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.repository.saveAllAtomically(fixture.id, slots(9, 1)));
            assertEquals(1, fixture.repository.findByMedico(fixture.id).size());
        }
    }

    @Test
    void rejectsPartialOverlapsAndSingleSaveCannotBypassCheck() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.repository.save(fixture.id, time(9), time(10));
            for (LocalDateTime start : List.of(time(8).plusMinutes(30), time(9).plusMinutes(30))) {
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.repository.save(fixture.id, start, start.plusHours(1)));
            }
            assertEquals(1, fixture.repository.findByMedico(fixture.id).size());
        }
    }

    @Test
    void rejectsWholeRequestWhenOnlyLastSlotOverlaps() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disponibilita existing = fixture.repository.save(fixture.id, time(11), time(12));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.repository.saveAllAtomically(fixture.id, slots(9, 3)));
            List<Disponibilita> found = fixture.repository.findByMedico(fixture.id);
            assertEquals(1, found.size());
            assertEquals(existing.getIdDisponibilita(), found.getFirst().getIdDisponibilita());
        }
    }

    @Test
    void permitsAdjacentIntervals() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.repository.save(fixture.id, time(9), time(10));
            fixture.repository.save(fixture.id, time(8), time(9));
            fixture.repository.save(fixture.id, time(10), time(11));
            assertEquals(3, fixture.repository.findByMedico(fixture.id).size());
        }
    }

    @Test
    void permitsSameIntervalForDifferentMedici() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            first.repository.saveAllAtomically(first.id, slots(9, 3));
            second.repository.saveAllAtomically(second.id, slots(9, 3));
            assertEquals(3, first.repository.findByMedico(first.id).size());
            assertEquals(3, second.repository.findByMedico(second.id).size());
        }
    }

    @Test
    void rejectsMissingMedicoAndInsertsNothing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (Connection connection = fixture.factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM medico WHERE id_utente = ?")) {
                statement.setInt(1, fixture.id);
                assertEquals(1, statement.executeUpdate());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.repository.saveAllAtomically(fixture.id, slots(9, 3)));
            assertTrue(fixture.repository.findByMedico(fixture.id).isEmpty());
        }
    }

    @Test
    void concurrentOverlappingRequestsPersistOnlyOneCompleteSeries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> request = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timeout sincronizzazione.");
                }
                try {
                    fixture.repository.saveAllAtomically(fixture.id, slots(9, 3));
                    return true;
                } catch (IllegalArgumentException e) {
                    assertEquals("L'intervallo si sovrappone a una disponibilità esistente del medico.",
                            e.getMessage());
                    return false;
                }
            };
            try {
                Future<Boolean> first = workers.submit(request);
                Future<Boolean> second = workers.submit(request);
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                boolean firstResult = first.get(20, TimeUnit.SECONDS);
                boolean secondResult = second.get(20, TimeUnit.SECONDS);
                assertNotEquals(firstResult, secondResult);
                assertEquals(3, fixture.repository.findByMedico(fixture.id).size());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sqlErrorOnSecondInsertRollsBackFirstSlot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            LocalDateTime start = LocalDateTime.of(9999, 12, 31, 22, 0);
            Disponibilita first = new Disponibilita();
            first.setDataOraInizio(start);
            first.setDataOraFine(start.plusHours(1));
            Disponibilita second = new Disponibilita();
            second.setDataOraInizio(start.plusHours(1));
            second.setDataOraFine(start.plusHours(2));
            // Il primo slot è valido per DATETIME; il secondo termina nell'anno 10000.
            // MySQL non può rappresentare quella data: errore reale, senza fault injection.
            assertThrows(SQLException.class,
                    () -> fixture.repository.saveAllAtomically(fixture.id, List.of(first, second)));
            assertTrue(fixture.repository.findByMedico(fixture.id).isEmpty());
        }
    }

    @Test
    void invalidSlotListsAreRejectedBeforeOpeningConnection() {
        DisponibilitaRepository repository = new DisponibilitaRepository(null, FIXTURE_CLOCK);
        for (Integer id : new Integer[]{null, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> repository.saveAllAtomically(id, slots(9, 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> repository.saveAllAtomically(1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.saveAllAtomically(1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveAllAtomically(1, java.util.Arrays.asList((Disponibilita) null)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveAllAtomically(1, List.of(new Disponibilita())));
        List<Disponibilita> nonConsecutive = slots(9, 2);
        nonConsecutive.get(1).setDataOraInizio(time(11));
        nonConsecutive.get(1).setDataOraFine(time(12));
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveAllAtomically(1, nonConsecutive));
        Disponibilita invalid = slots(9, 1).getFirst();
        for (LocalDateTime end : List.of(time(8), time(9), time(9).plusMinutes(30), time(10).plusNanos(1))) {
            invalid.setDataOraFine(end);
            assertThrows(IllegalArgumentException.class,
                    () -> repository.saveAllAtomically(1, List.of(invalid)));
        }
    }

    @Test
    void configurationPersistsBothReferencesAndMapsReads() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            Disponibilita configured = fixture.configure(slot);
            assertConfigured(configured, fixture);
            assertConfigured(fixture.medico.repository.findById(slot.getIdDisponibilita()).orElseThrow(), fixture);
            assertConfigured(fixture.medico.repository.findByMedico(fixture.medico.id).getFirst(), fixture);
            assertEquals(time(9), configured.getDataOraInizio());
            assertEquals(time(10), configured.getDataOraFine());
        }
    }

    @Test
    void configurationRejectsMissingSlot() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            execute(fixture.medico.factory, "DELETE FROM disponibilita WHERE id_disponibilita = ?", slot.getIdDisponibilita());
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.configure(slot));
        }
    }

    @Test
    void configurationRejectsMissingPrestazione() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            execute(fixture.medico.factory, "DELETE FROM medico_prestazione WHERE id_prestazione = ?", fixture.prestazione);
            execute(fixture.medico.factory, "DELETE FROM prestazione WHERE id_prestazione = ?", fixture.prestazione);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.configure(slot));
            assertUnconfigured(fixture, slot);
        }
    }

    @Test
    void configurationRejectsMissingStanza() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            execute(fixture.medico.factory, "DELETE FROM stanza WHERE id_stanza = ?", fixture.stanza);
            assertThrows(RisorsaNonTrovataException.class, () -> fixture.configure(slot));
            assertUnconfigured(fixture, slot);
        }
    }

    @Test
    void configurationRejectsUnqualifiedMedico() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            execute(fixture.medico.factory, "DELETE FROM medico_prestazione WHERE id_prestazione = ?", fixture.prestazione);
            assertThrows(ConflittoConfigurazioneException.class, () -> fixture.configure(slot));
            assertUnconfigured(fixture, slot);
        }
    }

    @Test
    void configurationRejectsAlreadyConfiguredSlotAndPreservesReferences() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            fixture.configure(slot);
            assertThrows(ConflittoConfigurazioneException.class, () -> fixture.configure(slot));
            assertConfigured(fixture.medico.repository.findById(slot.getIdDisponibilita()).orElseThrow(), fixture);
        }
    }

    @Test
    void configurationRejectsPartiallyConfiguredSlots() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            for (String column : List.of("id_prestazione", "id_stanza")) {
                Disponibilita slot = fixture.medico.repository.save(fixture.medico.id,
                        time(column.equals("id_stanza") ? 11 : 9), time(column.equals("id_stanza") ? 12 : 10));
                try (Connection connection = fixture.medico.factory.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE disponibilita SET " + column + " = ? WHERE id_disponibilita = ?")) {
                    statement.setInt(1, column.equals("id_stanza") ? fixture.stanza : fixture.prestazione);
                    statement.setInt(2, slot.getIdDisponibilita());
                    statement.executeUpdate();
                }
                assertThrows(ConflittoConfigurazioneException.class, () -> fixture.configure(slot));
            }
        }
    }

    @Test
    void configurationRejectsExactAndPartialRoomOverlapAcrossMedici() throws Exception {
        try (ConfigFixture first = new ConfigFixture(); ConfigFixture second = new ConfigFixture()) {
            Disponibilita existing = first.medico.repository.save(first.medico.id, time(9), time(10));
            first.configure(existing);
            for (LocalDateTime start : List.of(time(9), time(8).plusMinutes(30), time(9).plusMinutes(30))) {
                // Usa slot temporaneamente distinti per il secondo medico.
                Disponibilita candidate = second.medico.repository.save(second.medico.id, start, start.plusHours(1));
                assertThrows(ConflittoConfigurazioneException.class, () -> second.medico.repository.configuraAtomically(
                        candidate.getIdDisponibilita(), second.prestazione, first.stanza));
                assertUnconfigured(second, candidate);
                execute(second.medico.factory, "DELETE FROM disponibilita WHERE id_disponibilita = ?", candidate.getIdDisponibilita());
            }
        }
    }

    @Test
    void configurationPermitsAdjacentSlotsOnSameRoom() throws Exception {
        try (ConfigFixture first = new ConfigFixture(); ConfigFixture second = new ConfigFixture()) {
            first.configure(first.medico.repository.save(first.medico.id, time(9), time(10)));
            for (int hour : List.of(8, 10)) {
                Disponibilita slot = second.medico.repository.save(second.medico.id, time(hour), time(hour + 1));
                assertTrue(second.medico.repository.configuraAtomically(slot.getIdDisponibilita(),
                        second.prestazione, first.stanza).isConfigurata());
            }
        }
    }

    @Test
    void concurrentConfigurationOfOverlappingSlotsUsesRoomOnce() throws Exception {
        try (ConfigFixture first = new ConfigFixture(); ConfigFixture second = new ConfigFixture()) {
            Disponibilita a = first.medico.repository.save(first.medico.id, time(9), time(10));
            Disponibilita b = second.medico.repository.save(second.medico.id, time(9), time(10));
            assertOneConfigurationWins(
                    () -> first.configure(a),
                    () -> second.medico.repository.configuraAtomically(b.getIdDisponibilita(), second.prestazione, first.stanza));
            Disponibilita readA = first.medico.repository.findById(a.getIdDisponibilita()).orElseThrow();
            Disponibilita readB = second.medico.repository.findById(b.getIdDisponibilita()).orElseThrow();
            assertNotEquals(readA.isConfigurata(), readB.isConfigurata());
            Disponibilita loser = readA.isConfigurata() ? readB : readA;
            assertNull(loser.getPrestazione());
            assertNull(loser.getStanza());
        }
    }

    @Test
    void concurrentConfigurationOfSameSlotHasOnlyOneWinner() throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            Disponibilita slot = fixture.medico.repository.save(fixture.medico.id, time(9), time(10));
            assertOneConfigurationWins(() -> fixture.configure(slot),
                    () -> fixture.medico.repository.configuraAtomically(slot.getIdDisponibilita(), fixture.prestazione, fixture.stanza));
            assertTrue(fixture.medico.repository.findById(slot.getIdDisponibilita()).orElseThrow().isConfigurata());
        }
    }

    private static void assertOneConfigurationWins(Callable<Disponibilita> first,
                                                  Callable<Disponibilita> second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Disponibilita> operation : List.of(first, second)) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Timeout avvio."); }
                    try { operation.call(); return true; }
                    catch (ConflittoConfigurazioneException e) { return false; }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private static void assertUnconfigured(ConfigFixture fixture, Disponibilita slot) throws SQLException {
        Disponibilita read = fixture.medico.repository.findById(slot.getIdDisponibilita()).orElseThrow();
        assertNull(read.getPrestazione());
        assertNull(read.getStanza());
    }

    private static void assertConfigured(Disponibilita slot, ConfigFixture fixture) {
        assertTrue(slot.isConfigurata());
        assertEquals(fixture.prestazione, slot.getPrestazione().getIdPrestazione());
        assertEquals("Prestazione UC27", slot.getPrestazione().getNome());
        assertEquals(fixture.stanza, slot.getStanza().getIdStanza());
        assertEquals(fixture.roomName, slot.getStanza().getNome());
    }

    private static void execute(ConnectionFactory factory, String sql, Integer id) throws SQLException {
        try (Connection connection = factory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    private static final LocalDateTime UC04_NOW = LocalDateTime.of(2030, 1, 15, 8, 0);

    @ParameterizedTest
    @ValueSource(strings = {"futuro", "nonConfigurato", "soloPrestazione", "soloStanza", "passato", "adesso"})
    void uc04FiltersConfigurationAndTime(String scenario) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            LocalDateTime start = switch (scenario) {
                case "passato" -> UC04_NOW.minusHours(2);
                case "adesso" -> UC04_NOW;
                default -> UC04_NOW.plusHours(1);
            };
            Integer prestazione = List.of("nonConfigurato", "soloStanza").contains(scenario)
                    ? null : fixture.config.prestazione;
            Integer stanza = List.of("nonConfigurato", "soloPrestazione").contains(scenario)
                    ? null : fixture.config.stanza;
            int id = fixture.slot(start, prestazione, stanza);
            List<Disponibilita> result = fixture.search(null, null, null);
            assertEquals(scenario.equals("futuro"), result.stream().anyMatch(d -> d.getIdDisponibilita() == id));
            assertEquals(scenario.equals("futuro") ? 1 : 0,
                    fixture.search(fixture.config.medico.id, null, null).size());
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc04ChecksEveryBookingState(StatoPrenotazione stato) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.configuredSlot(UC04_NOW.plusHours(1));
            fixture.booking(id, stato);
            boolean free = stato == StatoPrenotazione.RIFIUTATA || stato == StatoPrenotazione.ANNULLATA;
            List<Disponibilita> result = fixture.search(fixture.config.medico.id, null, null);
            assertEquals(free ? 1 : 0, result.size());
            if (free) { assertEquals(id, result.getFirst().getIdDisponibilita().intValue()); }
        }
    }

    @Test
    void uc04HistoricalBookingsDoNotDuplicateSlotAndAnyBlockingBookingExcludesIt() throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.configuredSlot(UC04_NOW.plusHours(1));
            fixture.booking(id, StatoPrenotazione.RIFIUTATA);
            fixture.booking(id, StatoPrenotazione.ANNULLATA);
            fixture.booking(id, StatoPrenotazione.RIFIUTATA);
            List<Disponibilita> result = fixture.search(null, fixture.config.prestazione, null);
            assertEquals(List.of(id), result.stream().map(Disponibilita::getIdDisponibilita).toList());
            fixture.booking(id, StatoPrenotazione.RICHIESTA);
            assertTrue(fixture.search(null, fixture.config.prestazione, null).isEmpty());
        }
    }

    @Test
    void uc04AppliesIndividualAndCombinedFilters() throws SQLException {
        try (SearchFixture first = new SearchFixture(); SearchFixture second = new SearchFixture()) {
            int id = first.configuredSlot(UC04_NOW.plusHours(1));
            int tomorrow = first.configuredSlot(UC04_NOW.plusDays(1));
            int other = second.configuredSlot(UC04_NOW.plusHours(2));
            LocalDate day = UC04_NOW.toLocalDate();
            List<Integer> owned = List.of(id, tomorrow, other);
            assertEquals(3, first.search(null, null, null).stream()
                    .filter(d -> owned.contains(d.getIdDisponibilita())).count());
            assertEquals(List.of(id, tomorrow), first.search(first.config.medico.id, null, null).stream()
                    .map(Disponibilita::getIdDisponibilita).toList());
            assertEquals(List.of(id, tomorrow), first.search(null, first.config.prestazione, null).stream()
                    .map(Disponibilita::getIdDisponibilita).toList());
            assertEquals(List.of(id, other), first.search(null, null, day).stream()
                    .map(Disponibilita::getIdDisponibilita).filter(owned::contains).toList());
            assertEquals(List.of(id), first.search(first.config.medico.id, first.config.prestazione, day).stream()
                    .map(Disponibilita::getIdDisponibilita).toList());
            assertTrue(first.search(first.config.medico.id, second.config.prestazione, day).isEmpty());
            assertTrue(first.search(first.config.medico.id, null, day.minusDays(1)).isEmpty());
        }
    }

    @Test
    void uc04UsesHalfOpenDayBoundsAndOrdersByTimeThenId() throws SQLException {
        try (SearchFixture first = new SearchFixture(); SearchFixture second = new SearchFixture()) {
            LocalDate day = UC04_NOW.toLocalDate().plusDays(1);
            int late = first.configuredSlot(day.atTime(23, 0));
            int midnight = first.configuredSlot(day.atStartOfDay());
            int tie = second.configuredSlot(day.atStartOfDay());
            int before = first.configuredSlot(day.minusDays(1).atTime(23, 0));
            int after = first.configuredSlot(day.plusDays(1).atStartOfDay());
            List<Integer> owned = List.of(late, midnight, tie, before, after);
            List<Disponibilita> result = first.search(null, null, day).stream()
                    .filter(d -> owned.contains(d.getIdDisponibilita())).toList();
            assertEquals(List.of(midnight, tie, late), result.stream().map(Disponibilita::getIdDisponibilita).toList());
            assertEquals(day.atStartOfDay(), result.getFirst().getDataOraInizio());
            assertEquals(day.plusDays(1).atStartOfDay(), result.getLast().getDataOraFine());
        }
    }

    private static final Clock UC19_CLOCK = Clock.fixed(UC04_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private static DisponibilitaRepository uc19Repository(SearchFixture fixture) {
        return new DisponibilitaRepository(fixture.config.medico.factory, UC19_CLOCK);
    }

    private static List<List<Object>> uc19Snapshot(SearchFixture fixture, int slot) throws SQLException {
        List<List<Object>> result = new ArrayList<>();
        for (String sql : List.of("SELECT * FROM disponibilita WHERE id_disponibilita = ?",
                "SELECT * FROM prenotazione WHERE id_disponibilita = ? ORDER BY id_prenotazione")) {
            try (Connection connection = fixture.config.medico.factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, slot);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) { row.add(rows.getObject(i)); }
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    @Test
    void uc19EditOnlyTimesThenDeleteOnlyTargetSlot() throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            int other = fixture.slot(time(11), null, null);
            List<List<Object>> beforeOther = uc19Snapshot(fixture, other);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            Disponibilita updated = uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15));
            assertEquals(time(14), updated.getDataOraInizio()); assertEquals(time(15), updated.getDataOraFine());
            assertFalse(updated.isConfigurata()); assertEquals(id, updated.getIdDisponibilita());
            List<List<Object>> after = uc19Snapshot(fixture, id);
            /* JDBC può rappresentare DATETIME come LocalDateTime: confrontare solo le colonne modificate per posizione. */
            for (int i = 0; i < before.getFirst().size(); i++) {
                if (i != 1 && i != 2) { assertEquals(before.getFirst().get(i), after.getFirst().get(i)); }
            }
            assertEquals(beforeOther, uc19Snapshot(fixture, other));
            uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id);
            assertTrue(uc19Snapshot(fixture, id).isEmpty());
            assertEquals(beforeOther, uc19Snapshot(fixture, other));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, -1, 0})
    void uc19PastInProgressAndExactStartConflict(int hours) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(UC04_NOW.plusHours(hours), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            assertThrows(ConflittoDisponibilitaException.class,
                    () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)));
            assertThrows(ConflittoDisponibilitaException.class, () -> uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id));
            assertEquals(before, uc19Snapshot(fixture, id));
        }
    }

    @Test
    void uc19NewPastOrStartedIntervalConflicts() throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            for (LocalDateTime start : List.of(UC04_NOW.minusHours(1), UC04_NOW)) {
                assertThrows(ConflittoDisponibilitaException.class,
                        () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, start, start.plusHours(1)));
            }
            assertEquals(before, uc19Snapshot(fixture, id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prestazione", "stanza", "entrambi"})
    void uc19PartialAndFullConfigurationConflict(String configured) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), configured.equals("stanza") ? null : fixture.config.prestazione,
                    configured.equals("prestazione") ? null : fixture.config.stanza);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            assertThrows(ConflittoDisponibilitaException.class,
                    () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)));
            assertThrows(ConflittoDisponibilitaException.class, () -> uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id));
            assertEquals(before, uc19Snapshot(fixture, id));
        }
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc19EveryBookingStateBlocksAndHistoryIsPreserved(StatoPrenotazione state) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            fixture.booking(id, state); fixture.booking(id, StatoPrenotazione.ANNULLATA); fixture.booking(id, StatoPrenotazione.RIFIUTATA);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            assertThrows(ConflittoDisponibilitaException.class,
                    () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)));
            assertThrows(ConflittoDisponibilitaException.class, () -> uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id));
            assertEquals(before, uc19Snapshot(fixture, id));
        }
    }

    @Test
    void uc19MissingSlotMissingDoctorAndWrongOwnerAreNotFound() throws SQLException {
        try (SearchFixture fixture = new SearchFixture(); Fixture other = new Fixture()) {
            int id = fixture.slot(time(9), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            for (int doctor : List.of(other.id, fixture.paziente)) {
                assertThrows(RisorsaNonTrovataException.class, () -> uc19Repository(fixture).modificaOrarioAtomically(id, doctor, time(14), time(15)));
                assertThrows(RisorsaNonTrovataException.class, () -> uc19Repository(fixture).eliminaAtomically(id, doctor));
            }
            assertEquals(before, uc19Snapshot(fixture, id));
            uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id);
            assertThrows(RisorsaNonTrovataException.class,
                    () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)));
            assertThrows(RisorsaNonTrovataException.class, () -> uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -30, 30})
    void uc19ExactAndPartialOverlapConflictButAdjacentAllowed(int minutes) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null); int other = fixture.slot(time(14), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id), otherBefore = uc19Snapshot(fixture, other);
            LocalDateTime start = time(14).plusMinutes(minutes);
            assertThrows(ConflittoDisponibilitaException.class,
                    () -> uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, start, start.plusHours(1)));
            assertEquals(before, uc19Snapshot(fixture, id)); assertEquals(otherBefore, uc19Snapshot(fixture, other));
            uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(13), time(14));
            uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(15), time(16));
        }
    }

    private static Object uc19Invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uc19CommitFailureRollsBackEditAndDelete(boolean delete) throws SQLException {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            AtomicBoolean rolledBack = new AtomicBoolean();
            ConnectionFactory failing = new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection real = fixture.config.medico.factory.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("commit")) { throw new SQLException("Commit simulato fallito."); }
                                if (method.getName().equals("rollback")) { rolledBack.set(true); }
                                return uc19Invoke(real, method, args);
                            });
                }
            };
            DisponibilitaRepository repository = new DisponibilitaRepository(failing, UC19_CLOCK);
            assertThrows(SQLException.class, () -> {
                if (delete) { repository.eliminaAtomically(id, fixture.config.medico.id); }
                else { repository.modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)); }
            });
            assertTrue(rolledBack.get()); assertEquals(before, uc19Snapshot(fixture, id));
        }
    }

    private static List<Boolean> uc19Race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> action : List.of(first, second)) {
                futures.add(pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return action.call(); }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uc19EditVersusEditOrCreateNeverOverlaps(boolean create) throws Exception {
        try (SearchFixture fixture = new SearchFixture()) {
            int first = fixture.slot(time(9), null, null);
            int second = create ? 0 : fixture.slot(time(11), null, null);
            List<Boolean> winners = uc19Race(() -> {
                try { uc19Repository(fixture).modificaOrarioAtomically(first, fixture.config.medico.id, time(14), time(15)); return true; }
                catch (ConflittoDisponibilitaException e) { return false; }
            }, () -> {
                try {
                    if (create) { fixture.config.medico.repository.save(fixture.config.medico.id, time(14), time(15)); }
                    else { uc19Repository(fixture).modificaOrarioAtomically(second, fixture.config.medico.id, time(14), time(15)); }
                    return true;
                } catch (ConflittoDisponibilitaException | IllegalArgumentException e) { return false; }
            });
            assertNotEquals(winners.getFirst(), winners.getLast());
            assertEquals(1, fixture.config.medico.repository.findByMedico(fixture.config.medico.id).stream()
                    .filter(slot -> slot.getDataOraInizio().equals(time(14))).count());
        }
    }

    @Test
    void uc19DeleteVersusConfigurationHasOneWinner() throws Exception {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            List<Boolean> winners = uc19Race(() -> {
                try { uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id); return true; }
                catch (ConflittoDisponibilitaException e) { return false; }
            }, () -> {
                try { fixture.config.medico.repository.configuraAtomically(id, fixture.config.prestazione, fixture.config.stanza); return true; }
                catch (RisorsaNonTrovataException e) { return false; }
            });
            assertNotEquals(winners.getFirst(), winners.getLast());
            if (winners.getFirst()) { assertTrue(uc19Snapshot(fixture, id).isEmpty()); }
            else { assertTrue(fixture.config.medico.repository.findById(id).orElseThrow().isConfigurata()); }
        }
    }

    @Test
    void uc19DeleteVersusEditPreservesSerialOutcome() throws Exception {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.slot(time(9), null, null);
            List<Boolean> results = uc19Race(() -> { uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id); return true; }, () -> {
                try { uc19Repository(fixture).modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)); return true; }
                catch (RisorsaNonTrovataException e) { return false; }
            });
            assertTrue(results.getFirst()); assertTrue(uc19Snapshot(fixture, id).isEmpty());
        }
    }

    @Test
    void uc19DeleteVersusUc05NeverRemovesBookedSlot() throws Exception {
        try (SearchFixture fixture = new SearchFixture()) {
            int id = fixture.configuredSlot(time(9));
            List<Boolean> results = uc19Race(() -> {
                try { uc19Repository(fixture).eliminaAtomically(id, fixture.config.medico.id); return true; }
                catch (ConflittoDisponibilitaException e) { return false; }
            }, () -> {
                new PrenotazioneRepository(fixture.config.medico.factory, UC19_CLOCK).creaRichiestaAtomically(fixture.paziente, id);
                return true;
            });
            assertEquals(List.of(false, true), results);
            assertEquals(2, uc19Snapshot(fixture, id).size());
        }
    }

    private static ConnectionFactory uc19ObservedFactory(SearchFixture fixture, CountDownLatch attemptingSlot) {
        return new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection real = fixture.config.medico.factory.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            Object result = uc19Invoke(real, method, args);
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                    && sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) {
                                PreparedStatement prepared = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                        (ps, operation, parameters) -> {
                                            if (operation.getName().equals("executeQuery")) { attemptingSlot.countDown(); }
                                            return uc19Invoke(prepared, operation, parameters);
                                        });
                            }
                            return result;
                        });
            }
        };
    }

    private static void uc19HoldSlot(Connection connection, int slot) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE")) {
            statement.setInt(1, slot);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uc19ReadsClockAfterLockWaitAndRejectsStartedOriginalSlot(boolean delete) throws Exception {
        try (SearchFixture fixture = new SearchFixture(); Connection holder = fixture.config.medico.factory.getConnection()) {
            int id = fixture.slot(time(9), null, null);
            List<List<Object>> before = uc19Snapshot(fixture, id);
            uc19HoldSlot(holder, id);
            AtomicReference<Instant> instant = new AtomicReference<>(UC19_CLOCK.instant());
            AtomicInteger reads = new AtomicInteger();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant.get(), zone); }
                @Override public Instant instant() { reads.incrementAndGet(); return instant.get(); }
            };
            CountDownLatch attempting = new CountDownLatch(1);
            DisponibilitaRepository repository = new DisponibilitaRepository(uc19ObservedFactory(fixture, attempting), clock);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(() -> {
                    if (delete) { repository.eliminaAtomically(id, fixture.config.medico.id); }
                    else { repository.modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)); }
                    return null;
                });
                assertTrue(attempting.await(10, TimeUnit.SECONDS)); assertEquals(0, reads.get()); assertFalse(result.isDone());
                instant.set(time(9).toInstant(ZoneOffset.UTC)); holder.commit();
                ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoDisponibilitaException.class, error.getCause());
                assertEquals(1, reads.get()); assertEquals(before, uc19Snapshot(fixture, id));
            } finally { holder.rollback(); worker.shutdownNow(); assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS)); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uc19BookingInsertedWhileWaitingIsSeenAndHistoryStaysIntact(boolean delete) throws Exception {
        try (SearchFixture fixture = new SearchFixture(); Connection holder = fixture.config.medico.factory.getConnection()) {
            int id = fixture.slot(time(9), null, null);
            uc19HoldSlot(holder, id);
            CountDownLatch attempting = new CountDownLatch(1);
            DisponibilitaRepository repository = new DisponibilitaRepository(uc19ObservedFactory(fixture, attempting), UC19_CLOCK);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(() -> {
                    if (delete) { repository.eliminaAtomically(id, fixture.config.medico.id); }
                    else { repository.modificaOrarioAtomically(id, fixture.config.medico.id, time(14), time(15)); }
                    return null;
                });
                assertTrue(attempting.await(10, TimeUnit.SECONDS)); assertFalse(result.isDone());
                /* Lo scrittore controllato possiede lo slot e crea lo storico prima di rilasciarlo. */
                try (PreparedStatement statement = holder.prepareStatement("""
                        INSERT INTO prenotazione (id_paziente, id_disponibilita, stato, data_ora_richiesta, prezzo)
                        VALUES (?, ?, 'ANNULLATA', ?, 10)
                        """)) {
                    statement.setInt(1, fixture.paziente); statement.setInt(2, id); statement.setObject(3, UC04_NOW);
                    assertEquals(1, statement.executeUpdate());
                }
                holder.commit();
                List<List<Object>> history = uc19Snapshot(fixture, id);
                ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
                assertInstanceOf(ConflittoDisponibilitaException.class, error.getCause());
                assertEquals(2, history.size()); assertEquals(history, uc19Snapshot(fixture, id));
            } finally { holder.rollback(); worker.shutdownNow(); assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS)); }
        }
    }

    // Dati dedicati al test: prenotazioni e paziente sono eliminati prima degli slot.
    private static class SearchFixture implements AutoCloseable {
        private final ConfigFixture config = new ConfigFixture();
        private Integer paziente;

        SearchFixture() throws SQLException {
            try {
                paziente = insert("INSERT INTO utente (nome, cognome, email, password_hash) VALUES (?, ?, ?, ?)",
                        "Paziente", "UC04", "uc04-" + UUID.randomUUID() + "@example.invalid",
                        UUID.randomUUID().toString());
                update("INSERT INTO paziente (id_utente, codice_fiscale, data_nascita) VALUES (?, ?, ?)",
                        paziente, UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                        LocalDate.of(1990, 1, 1));
            } catch (SQLException | RuntimeException e) {
                try { close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }

        int configuredSlot(LocalDateTime start) throws SQLException {
            return slot(start, config.prestazione, config.stanza);
        }

        int slot(LocalDateTime start, Integer prestazione, Integer stanza) throws SQLException {
            return insert("""
                    INSERT INTO disponibilita
                    (id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza)
                    VALUES (?, ?, ?, ?, ?)
                    """, config.medico.id, start, start.plusHours(1), prestazione, stanza);
        }

        void booking(int slot, StatoPrenotazione stato) throws SQLException {
            update("""
                    INSERT INTO prenotazione
                    (id_paziente, id_disponibilita, stato, data_ora_richiesta, prezzo)
                    VALUES (?, ?, ?, ?, ?)
                    """, paziente, slot, stato.name(), UC04_NOW.minusDays(1), 10);
        }

        List<Disponibilita> search(Integer medico, Integer prestazione, LocalDate data) throws SQLException {
            return config.medico.repository.findPrenotabili(medico, prestazione, data, UC04_NOW);
        }

        private int insert(String sql, Object... values) throws SQLException {
            try (Connection connection = config.medico.factory.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, values);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) { throw new SQLException("ID fixture UC04 non disponibile."); }
                    return keys.getInt(1);
                }
            }
        }

        private void update(String sql, Object... values) throws SQLException {
            try (Connection connection = config.medico.factory.getConnection();
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
            if (paziente != null) {
                update("DELETE FROM prenotazione WHERE id_paziente = ?", paziente);
                update("DELETE FROM paziente WHERE id_utente = ?", paziente);
                update("DELETE FROM utente WHERE id_utente = ?", paziente);
            }
            config.close();
        }
    }

    private static class ConfigFixture implements AutoCloseable {
        private final Fixture medico = new Fixture();
        private Integer prestazione;
        private Integer stanza;
        private final String roomName = "UC27-" + UUID.randomUUID();

        ConfigFixture() throws SQLException {
            try (Connection connection = medico.factory.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO prestazione (nome, durata, prezzo) VALUES ('Prestazione UC27', 30, 10)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) { throw new SQLException("ID prestazione non disponibile."); }
                        prestazione = keys.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO stanza (nome) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, roomName);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) { throw new SQLException("ID stanza non disponibile."); }
                        stanza = keys.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO medico_prestazione (id_medico, id_prestazione) VALUES (?, ?)")) {
                    statement.setInt(1, medico.id);
                    statement.setInt(2, prestazione);
                    statement.executeUpdate();
                }
            } catch (SQLException | RuntimeException e) {
                try { close(); } catch (SQLException cleanupError) { e.addSuppressed(cleanupError); }
                throw e;
            }
        }

        Disponibilita configure(Disponibilita slot) throws SQLException {
            return medico.repository.configuraAtomically(slot.getIdDisponibilita(), prestazione, stanza);
        }

        @Override
        public void close() throws SQLException {
            medico.close();
            if (prestazione != null) {
                execute(medico.factory, "DELETE FROM prestazione WHERE id_prestazione = ?", prestazione);
            }
            if (stanza != null) { execute(medico.factory, "DELETE FROM stanza WHERE id_stanza = ?", stanza); }
        }
    }

    private static LocalDateTime time(int hour) {
        return LocalDateTime.of(2030, 1, 15, hour, 0);
    }

    private static List<Disponibilita> slots(int hour, int count) {
        List<Disponibilita> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Disponibilita slot = new Disponibilita();
            slot.setDataOraInizio(time(hour).plusHours(i));
            slot.setDataOraFine(time(hour).plusHours(i + 1));
            result.add(slot);
        }
        return result;
    }

    private static class Fixture implements AutoCloseable {
        private final ConnectionFactory factory;
        private final DisponibilitaRepository repository;
        private final String email = "atomic-" + UUID.randomUUID() + "@example.invalid";
        private Integer id;

        Fixture() throws SQLException {
            factory = it.medbook.config.TestDatabaseConnections.isolatedFactory();
            repository = new DisponibilitaRepository(factory, FIXTURE_CLOCK);
            try (Connection connection = factory.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO utente (nome, cognome, email, password_hash) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, "Test");
                    statement.setString(2, "Atomicita");
                    statement.setString(3, email);
                    statement.setString(4, UUID.randomUUID().toString());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) { throw new SQLException("ID utente non disponibile."); }
                        id = keys.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO medico (id_utente, specializzazione, numero_iscrizione_ordine) VALUES (?, ?, ?)")) {
                    statement.setInt(1, id);
                    statement.setString(2, "Medicina generale");
                    statement.setString(3, UUID.randomUUID().toString());
                    statement.executeUpdate();
                }
            } catch (SQLException | RuntimeException e) {
                try { close(); } catch (SQLException cleanupError) { e.addSuppressed(cleanupError); }
                throw e;
            }
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = factory.getConnection()) {
                if (id != null) {
                    for (String sql : List.of("DELETE FROM disponibilita WHERE id_medico = ?",
                            "DELETE FROM medico WHERE id_utente = ?")) {
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setInt(1, id);
                            statement.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM utente WHERE email = ?")) {
                    statement.setString(1, email);
                    statement.executeUpdate();
                }
            }
        }
    }

    private static void assertSlot(Disponibilita slot, Integer idMedico,
                                   LocalDateTime inizio, LocalDateTime fine) {
        assertEquals(inizio, slot.getDataOraInizio());
        assertEquals(fine, slot.getDataOraFine());
        assertNotNull(slot.getMedico());
        assertNotNull(slot.getMedico().getUtente());
        assertEquals(idMedico, slot.getMedico().getUtente().getIdUtente());
        assertNull(slot.getPrestazione());
        assertNull(slot.getStanza());
    }
}
