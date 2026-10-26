package it.medbook.service;

import it.medbook.model.Disponibilita;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoDisponibilitaException;

import it.medbook.repository.DisponibilitaRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DisponibilitaServiceTest {
    private final FakeDisponibilitaRepository repository = new FakeDisponibilitaRepository();
    /* Orologio fisso prima di ogni data di prova: nessuna lettura dell'ora reale. */
    private final DisponibilitaService service = new DisponibilitaService(repository,
            Clock.fixed(Instant.parse("2026-10-14T10:15:30.250Z"), ZoneOffset.UTC));
    private final LocalDateTime inizio = LocalDateTime.of(2030, 1, 15, 9, 0);

    @Test
    void getByMedicoDelegatesToRepository() throws SQLException {
        repository.results = List.of(new Disponibilita());
        assertSame(repository.results, service.getDisponibilitaByMedico(7));
        assertEquals(Integer.valueOf(7), repository.medicoId);
        assertEquals(1, repository.readCalls);
    }

    @Test
    void getByIdDelegatesToRepository() throws SQLException {
        repository.result = Optional.of(new Disponibilita());
        assertSame(repository.result, service.getDisponibilitaById(8));
        assertEquals(Integer.valueOf(8), repository.disponibilitaId);
        assertEquals(1, repository.readCalls);
    }

    @Test
    void getByIdPreservesEmptyResult() throws SQLException {
        assertTrue(service.getDisponibilitaById(8).isEmpty());
    }

    @Test
    void rejectsNullIds() { assertInvalidId(null); }

    @Test
    void rejectsZeroIds() { assertInvalidId(0); }

    @Test
    void rejectsNegativeIds() { assertInvalidId(-1); }

    @Test
    void createsOneSlot() throws SQLException {
        assertSlots(inizio, inizio.plusHours(1), 1);
    }

    @Test
    void createsThreeConsecutiveSlots() throws SQLException {
        assertSlots(inizio, inizio.plusHours(3), 3);
    }

    @Test
    void createsSlotsStartingAtHalfHour() throws SQLException {
        LocalDateTime start = inizio.withHour(14).withMinute(30);
        assertSlots(start, start.plusHours(2), 2);
    }

    @Test
    void createsSlotsAcrossMidnight() throws SQLException {
        LocalDateTime start = inizio.withHour(23);
        assertSlots(start, start.plusHours(2), 2);
    }

    @Test
    void rejectsThirtyMinutes() { assertInvalidInterval(inizio, inizio.plusMinutes(30)); }

    @Test
    void rejectsNinetyMinutes() { assertInvalidInterval(inizio, inizio.plusMinutes(90)); }

    @Test
    void rejectsEqualStartAndEnd() { assertInvalidInterval(inizio, inizio); }

    @Test
    void rejectsEndBeforeStart() { assertInvalidInterval(inizio, inizio.minusHours(1)); }

    @Test
    void rejectsNullStart() { assertInvalidInterval(null, inizio); }

    @Test
    void rejectsNullEnd() { assertInvalidInterval(inizio, null); }

    @Test
    void rejectsExtraSeconds() { assertInvalidInterval(inizio, inizio.plusHours(1).plusSeconds(1)); }

    @Test
    void rejectsExtraNanoseconds() { assertInvalidInterval(inizio, inizio.plusHours(1).plusNanos(1)); }

    @Test
    void propagatesReadByMedicoException() {
        repository.error = new SQLException("Errore simulato.");
        assertSame(repository.error,
                assertThrows(SQLException.class, () -> service.getDisponibilitaByMedico(7)));
    }

    @Test
    void propagatesReadByIdException() {
        repository.error = new SQLException("Errore simulato.");
        assertSame(repository.error,
                assertThrows(SQLException.class, () -> service.getDisponibilitaById(8)));
    }

    @Test
    void propagatesSaveExceptionAndStopsCreatingSlots() {
        repository.error = new SQLException("Errore simulato.");
        assertSame(repository.error, assertThrows(SQLException.class,
                () -> service.creaDisponibilita(7, inizio, inizio.plusHours(3))));
        assertEquals(1, repository.atomicCalls);
    }

    @Test
    void propagatesOverlapException() {
        repository.overlapError = new IllegalArgumentException("Intervallo sovrapposto.");
        assertSame(repository.overlapError, assertThrows(IllegalArgumentException.class,
                () -> service.creaDisponibilita(7, inizio, inizio.plusHours(3))));
        assertEquals(1, repository.atomicCalls);
    }

    @Test
    void configuresWithOneRepositoryCallAndReturnsResult() throws SQLException {
        assertSame(repository.configured, service.configuraDisponibilita(1, 2, 3));
        assertEquals(1, repository.configCalls);
        assertEquals(List.of(1, 2, 3), repository.configIds);
    }

    @Test
    void configurationRejectsEachInvalidIdWithoutCallingRepository() {
        for (Integer invalid : new Integer[]{null, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> service.configuraDisponibilita(invalid, 2, 3));
            assertThrows(IllegalArgumentException.class, () -> service.configuraDisponibilita(1, invalid, 3));
            assertThrows(IllegalArgumentException.class, () -> service.configuraDisponibilita(1, 2, invalid));
        }
        assertEquals(0, repository.configCalls);
    }

    @Test
    void configurationPropagatesMissingResource() {
        repository.configError = new RisorsaNonTrovataException("Risorsa assente.");
        assertSame(repository.configError, assertThrows(RisorsaNonTrovataException.class,
                () -> service.configuraDisponibilita(1, 2, 3)));
    }

    @Test
    void configurationPropagatesConflict() {
        repository.configError = new ConflittoConfigurazioneException("Conflitto.");
        assertSame(repository.configError, assertThrows(ConflittoConfigurazioneException.class,
                () -> service.configuraDisponibilita(1, 2, 3)));
    }

    @Test
    void configurationPropagatesSqlError() {
        repository.error = new SQLException("Errore simulato.");
        assertSame(repository.error, assertThrows(SQLException.class,
                () -> service.configuraDisponibilita(1, 2, 3)));
    }

    @ParameterizedTest
    @CsvSource(value = {"null,null,null", "7,null,null", "null,11,null",
            "null,null,2030-01-15", "7,11,2030-01-15"}, nullValues = "null")
    void uc04DelegatesOptionalFiltersAndExactClockTime(Integer medico, Integer prestazione, LocalDate data)
            throws SQLException {
        Clock clock = Clock.fixed(Instant.parse("2030-01-15T08:12:34Z"), ZoneOffset.ofHours(2));
        DisponibilitaService controlled = new DisponibilitaService(repository, clock);
        repository.results = List.of(new Disponibilita());
        assertSame(repository.results, controlled.getDisponibilitaPrenotabili(medico, prestazione, data));
        assertEquals(medico, repository.medicoId);
        assertEquals(prestazione, repository.prestazioneId);
        assertEquals(data, repository.data);
        assertEquals(LocalDateTime.of(2030, 1, 15, 10, 12, 34), repository.adesso);
        assertEquals(1, repository.searchCalls);
    }

    @Test
    void uc04RejectsNonPositiveFiltersWithoutRepositoryCall() {
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getDisponibilitaPrenotabili(invalid, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> service.getDisponibilitaPrenotabili(null, invalid, null));
        }
        assertEquals(0, repository.searchCalls);
    }

    @Test
    void uc04PreservesEmptyResult() throws SQLException {
        assertTrue(service.getDisponibilitaPrenotabili(null, null, null).isEmpty());
        assertEquals(1, repository.searchCalls);
    }

    @Test
    void uc04PropagatesSqlException() {
        repository.error = new SQLException("Errore simulato.");
        assertSame(repository.error, assertThrows(SQLException.class,
                () -> service.getDisponibilitaPrenotabili(null, null, null)));
    }

    @Test
    void uc19DelegatesOnlyRequestedMutation() throws SQLException {
        DisponibilitaService controlled = new DisponibilitaService(repository,
                Clock.fixed(inizio.minusHours(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        assertSame(repository.configured, controlled.modificaOrario(3, 7, inizio, inizio.plusHours(1)));
        assertEquals(List.of(3, 7), repository.mutationIds);
        assertEquals(inizio, repository.mutationStart); assertEquals(inizio.plusHours(1), repository.mutationEnd);
        assertEquals(1, repository.editCalls); assertEquals(0, repository.deleteCalls);
        controlled.eliminaDisponibilita(3, 7);
        assertEquals(1, repository.deleteCalls);
        assertEquals(0, repository.atomicCalls); assertEquals(0, repository.configCalls);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,7", "0,7", "-1,7", "3,null", "3,0", "3,-1"}, nullValues = "null")
    void uc19RejectsInvalidIdsBeforeMutations(Integer slot, Integer doctor) {
        assertThrows(IllegalArgumentException.class, () -> service.modificaOrario(slot, doctor, inizio, inizio.plusHours(1)));
        assertThrows(IllegalArgumentException.class, () -> service.eliminaDisponibilita(slot, doctor));
        assertEquals(0, repository.editCalls); assertEquals(0, repository.deleteCalls);
    }

    @Test
    void uc19RejectsInvalidIntervalsAndFractions() {
        for (LocalDateTime[] dates : new LocalDateTime[][] {
                {null, inizio}, {inizio, null}, {inizio, inizio}, {inizio, inizio.minusHours(1)},
                {inizio, inizio.plusMinutes(30)}, {inizio, inizio.plusMinutes(90)},
                {inizio, inizio.plusHours(1).plusSeconds(1)},
                {inizio.plusNanos(1), inizio.plusHours(1).plusNanos(1)},
                {LocalDateTime.of(999, 1, 1, 9, 0), LocalDateTime.of(999, 1, 1, 10, 0)}}) {
            assertThrows(IllegalArgumentException.class, () -> service.modificaOrario(3, 7, dates[0], dates[1]));
        }
        assertEquals(0, repository.editCalls);
    }

    @Test
    void uc19NewStartMustBeStrictlyFuture() {
        DisponibilitaService controlled = new DisponibilitaService(repository,
                Clock.fixed(inizio.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        for (LocalDateTime start : List.of(inizio, inizio.minusSeconds(1))) {
            assertThrows(ConflittoDisponibilitaException.class, () -> controlled.modificaOrario(3, 7, start, start.plusHours(1)));
        }
        assertEquals(0, repository.editCalls);
    }

    @Test
    void creationStartMustBeStrictlyFutureAndIsRefusedAsInvalidInput() throws SQLException {
        /*
         * Diversamente dalla modifica orario (409), una creazione non futura è input invalido (400)
         * prima del repository.
         */
        DisponibilitaService controlled = new DisponibilitaService(repository,
                Clock.fixed(inizio.toInstant(ZoneOffset.UTC).plusMillis(1), ZoneOffset.UTC));
        for (LocalDateTime start : List.of(inizio.minusDays(1), inizio)) {
            var e = assertThrows(IllegalArgumentException.class, () -> controlled.creaDisponibilita(7, start, start.plusHours(1)));
            assertEquals("L'inizio della disponibilità deve essere futuro.", e.getMessage());
        }
        assertEquals(0, repository.atomicCalls);
        assertEquals(1, controlled.creaDisponibilita(7, inizio.plusSeconds(1), inizio.plusHours(1).plusSeconds(1)).size());
        assertEquals(1, repository.atomicCalls);
    }

    @Test
    void uc19PropagatesOwnershipConflictsAndSqlFailures() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Slot altrui."),
                new ConflittoDisponibilitaException("Storico o orario non ammesso."))) {
            repository.configError = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.modificaOrario(3, 7, inizio, inizio.plusHours(1))));
            assertSame(error, assertThrows(error.getClass(), () -> service.eliminaDisponibilita(3, 7)));
        }
        repository.configError = null; repository.error = new SQLException("SQL.");
        assertSame(repository.error, assertThrows(SQLException.class, () -> service.modificaOrario(3, 7, inizio, inizio.plusHours(1))));
        assertSame(repository.error, assertThrows(SQLException.class, () -> service.eliminaDisponibilita(3, 7)));
    }

    private void assertInvalidId(Integer id) {
        assertThrows(IllegalArgumentException.class, () -> service.getDisponibilitaByMedico(id));
        assertThrows(IllegalArgumentException.class, () -> service.getDisponibilitaById(id));
        assertThrows(IllegalArgumentException.class,
                () -> service.creaDisponibilita(id, inizio, inizio.plusHours(1)));
        assertEquals(0, repository.readCalls);
        assertTrue(repository.saveCalls.isEmpty());
        assertEquals(0, repository.atomicCalls);
    }

    private void assertInvalidInterval(LocalDateTime start, LocalDateTime end) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.creaDisponibilita(7, start, end));
        assertFalse(error.getMessage().isBlank());
        assertTrue(repository.saveCalls.isEmpty());
        assertEquals(0, repository.atomicCalls);
    }

    private void assertSlots(LocalDateTime start, LocalDateTime end, int count) throws SQLException {
        List<Disponibilita> slots = service.creaDisponibilita(7, start, end);
        assertEquals(count, slots.size());
        assertEquals(count, repository.saveCalls.size());
        assertEquals(1, repository.atomicCalls);
        assertSame(repository.saved, slots);
        for (int i = 0; i < count; i++) {
            SaveCall call = repository.saveCalls.get(i);
            assertEquals(Integer.valueOf(7), call.idMedico);
            assertEquals(start.plusHours(i), call.inizio);
            assertEquals(start.plusHours(i + 1), call.fine);
            assertFalse(call.fine.isAfter(end));
            assertSame(repository.saved.get(i), slots.get(i));
            assertEquals(call.inizio, slots.get(i).getDataOraInizio());
            assertEquals(call.fine, slots.get(i).getDataOraFine());
            assertNull(slots.get(i).getPrestazione());
            assertNull(slots.get(i).getStanza());
        }
        assertEquals(end, slots.getLast().getDataOraFine());
    }

    private static class SaveCall {
        private final Integer idMedico;
        private final LocalDateTime inizio;
        private final LocalDateTime fine;

        SaveCall(Integer idMedico, LocalDateTime inizio, LocalDateTime fine) {
            this.idMedico = idMedico;
            this.inizio = inizio;
            this.fine = fine;
        }
    }

    private static class FakeDisponibilitaRepository extends DisponibilitaRepository {
        private int editCalls, deleteCalls;
        private List<Integer> mutationIds;
        private LocalDateTime mutationStart, mutationEnd;

        @Override
        public Disponibilita modificaOrarioAtomically(Integer slot, Integer doctor, LocalDateTime start, LocalDateTime end) throws SQLException {
            editCalls++; mutationIds = List.of(slot, doctor); mutationStart = start; mutationEnd = end;
            if (configError != null) { throw configError; }
            if (error != null) { throw error; }
            return configured;
        }

        @Override
        public void eliminaAtomically(Integer slot, Integer doctor) throws SQLException {
            deleteCalls++; mutationIds = List.of(slot, doctor);
            if (configError != null) { throw configError; }
            if (error != null) { throw error; }
        }


        private Integer prestazioneId;
        private LocalDate data;
        private LocalDateTime adesso;
        private int searchCalls;

        @Override
        public List<Disponibilita> findPrenotabili(Integer medico, Integer prestazione,
                                                 LocalDate data, LocalDateTime adesso) throws SQLException {
            searchCalls++;
            this.medicoId = medico;
            this.prestazioneId = prestazione;
            this.data = data;
            this.adesso = adesso;
            if (error != null) { throw error; }
            return results;
        }

        private List<Disponibilita> results = List.of();
        private Optional<Disponibilita> result = Optional.empty();
        private Integer medicoId;
        private Integer disponibilitaId;
        private int readCalls;
        private SQLException error;
        private IllegalArgumentException overlapError;
        private int atomicCalls;
        private int configCalls;
        private List<Integer> configIds;
        private final Disponibilita configured = new Disponibilita();
        private RuntimeException configError;

        @Override
        public Disponibilita configuraAtomically(Integer id, Integer prestazione, Integer stanza) throws SQLException {
            configCalls++;
            configIds = List.of(id, prestazione, stanza);
            if (configError != null) { throw configError; }
            if (error != null) { throw error; }
            return configured;
        }
        private final List<SaveCall> saveCalls = new ArrayList<>();
        private final List<Disponibilita> saved = new ArrayList<>();

        FakeDisponibilitaRepository() {
            // Tutti i metodi usati dal Service sono sostituiti: nessun accesso JDBC.
            super(null);
        }

        @Override
        public List<Disponibilita> findByMedico(Integer idMedico) throws SQLException {
            readCalls++;
            medicoId = idMedico;
            if (error != null) { throw error; }
            return results;
        }

        @Override
        public Optional<Disponibilita> findById(Integer idDisponibilita) throws SQLException {
            readCalls++;
            disponibilitaId = idDisponibilita;
            if (error != null) { throw error; }
            return result;
        }

        @Override
        public Disponibilita save(Integer idMedico, LocalDateTime start, LocalDateTime end) {
            throw new AssertionError("Il Service non deve chiamare save singolo.");
        }

        @Override
        public List<Disponibilita> saveAllAtomically(Integer idMedico, List<Disponibilita> slots)
                throws SQLException {
            atomicCalls++;
            if (error != null) { throw error; }
            if (overlapError != null) { throw overlapError; }
            for (Disponibilita slot : slots) {
                saveCalls.add(new SaveCall(idMedico, slot.getDataOraInizio(), slot.getDataOraFine()));
                slot.setIdDisponibilita(saveCalls.size());
                saved.add(slot);
            }
            return saved;
        }
    }
}
