package it.medbook.service;

import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.Prenotazione;
import it.medbook.repository.PrenotazioneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.params.provider.EnumSource;
import it.medbook.exception.OperazioneNonAutorizzataException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import it.medbook.dto.AppuntamentoPazienteResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import it.medbook.dto.AppuntamentoAgendaResponse;
import java.util.List;
import it.medbook.dto.RichiestaPrenotazioneResponse;

import static org.junit.jupiter.api.Assertions.*;

class PrenotazioneServiceTest {
    private final FakeRepository repository = new FakeRepository();
    private final PrenotazioneService service = new PrenotazioneService(repository);

    @ParameterizedTest
    @CsvSource(value = {"null,3", "0,3", "-1,3", "7,null", "7,0", "7,-1"}, nullValues = "null")
    void rejectsInvalidIdsWithoutCallingRepository(Integer paziente, Integer disponibilita) {
        assertThrows(IllegalArgumentException.class, () -> service.richiediAppuntamento(paziente, disponibilita));
        assertEquals(0, repository.calls);
    }

    @Test
    void delegatesExactlyOnceAndReturnsResult() throws SQLException {
        assertSame(repository.result, service.richiediAppuntamento(7, 3));
        assertEquals(7, repository.paziente);
        assertEquals(3, repository.disponibilita);
        assertEquals(1, repository.calls);
    }

    @Test
    void propagatesMissingResource() {
        repository.error = new RisorsaNonTrovataException("Risorsa assente.");
        assertSame(repository.error, assertThrows(RisorsaNonTrovataException.class,
                () -> service.richiediAppuntamento(7, 3)));
        assertEquals(1, repository.calls);
    }

    @Test
    void propagatesConflict() {
        repository.error = new ConflittoPrenotazioneException("Slot occupato.");
        assertSame(repository.error, assertThrows(ConflittoPrenotazioneException.class,
                () -> service.richiediAppuntamento(7, 3)));
        assertEquals(1, repository.calls);
    }

    @Test
    void propagatesSqlException() {
        repository.sqlError = new SQLException("Errore simulato.");
        assertSame(repository.sqlError, assertThrows(SQLException.class,
                () -> service.richiediAppuntamento(7, 3)));
        assertEquals(1, repository.calls);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,null,null,null", "7,null,null,null", "null,11,null,null",
            "null,null,2030-01-15,null", "null,null,null,2030-01-20",
            "7,11,2030-01-15,2030-01-20", "null,null,2030-01-15,2030-01-15"}, nullValues = "null")
    void uc10DelegatesFiltersExactlyOnce(Integer medico, Integer prestazione, LocalDate da, LocalDate a)
            throws SQLException {
        repository.richieste = List.of(new RichiestaPrenotazioneResponse());
        assertSame(repository.richieste, service.getRichieste(medico, prestazione, da, a));
        assertEquals(medico, repository.medico);
        assertEquals(prestazione, repository.prestazione);
        assertEquals(da, repository.da);
        assertEquals(a, repository.a);
        assertEquals(1, repository.searchCalls);
        assertEquals(0, repository.calls);
    }

    @Test
    void uc10RejectsNonPositiveIdsAndInvertedRangeBeforeDelegation() {
        for (int id : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> service.getRichieste(id, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> service.getRichieste(null, id, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> service.getRichieste(null, null,
                LocalDate.of(2030, 1, 20), LocalDate.of(2030, 1, 15)));
        assertEquals(0, repository.searchCalls);
    }

    @Test
    void uc10ReturnsEmptyResults() throws SQLException {
        assertTrue(service.getRichieste(null, null, null, null).isEmpty());
        assertEquals(1, repository.searchCalls);
    }

    @Test
    void uc10PropagatesSqlError() {
        repository.sqlError = new SQLException("Errore SQL.");
        assertSame(repository.sqlError, assertThrows(SQLException.class,
                () -> service.getRichieste(null, null, null, null)));
    }

    @ParameterizedTest
    @CsvSource(value = {"null,7", "0,7", "-1,7", "3,null", "3,0", "3,-1"}, nullValues = "null")
    void uc11RejectsInvalidIdsWithoutRepositoryCall(Integer prenotazione, Integer operatore) {
        assertThrows(IllegalArgumentException.class, () -> service.confermaAppuntamento(prenotazione, operatore));
        assertEquals(0, repository.confirmCalls);
    }

    @Test
    void uc11DelegatesOnceAndReturnsResult() throws SQLException {
        assertSame(repository.confirmed, service.confermaAppuntamento(3, 7));
        assertEquals(List.of(3, 7), repository.confirmIds);
        assertEquals(1, repository.confirmCalls);
    }

    @Test
    void uc11PropagatesApplicationAndSqlErrors() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Assente."),
                new ConflittoPrenotazioneException("Conflitto."), new OperazioneNonAutorizzataException("Ruolo."))) {
            repository.error = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.confermaAppuntamento(3, 7)));
        }
        repository.error = null;
        repository.sqlError = new SQLException("SQL.");
        assertSame(repository.sqlError, assertThrows(SQLException.class, () -> service.confermaAppuntamento(3, 7)));
    }

    @ParameterizedTest
    @CsvSource(value = {"null,7", "0,7", "-1,7", "3,null", "3,0", "3,-1"}, nullValues = "null")
    void uc12RejectsInvalidIdsWithoutRepositoryCall(Integer prenotazione, Integer operatore) {
        assertThrows(IllegalArgumentException.class, () -> service.rifiutaAppuntamento(prenotazione, operatore));
        assertEquals(0, repository.rejectCalls);
    }

    @Test
    void uc12DelegatesOnceAndReturnsResult() throws SQLException {
        assertSame(repository.rejected, service.rifiutaAppuntamento(3, 7));
        assertEquals(List.of(3, 7), repository.rejectIds);
        assertEquals(1, repository.rejectCalls);
    }

    @Test
    void uc12PropagatesApplicationAndSqlErrors() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Assente."),
                new ConflittoPrenotazioneException("Conflitto."), new OperazioneNonAutorizzataException("Ruolo."))) {
            repository.error = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.rifiutaAppuntamento(3, 7)));
        }
        repository.error = null;
        repository.sqlError = new SQLException("SQL.");
        assertSame(repository.sqlError, assertThrows(SQLException.class, () -> service.rifiutaAppuntamento(3, 7)));
    }

    @ParameterizedTest
    @CsvSource(value = {"null,7", "0,7", "-1,7", "3,null", "3,0", "3,-1"}, nullValues = "null")
    void uc13RejectsInvalidIdsWithoutRepositoryCall(Integer prenotazione, Integer operatore) {
        assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamento(prenotazione, operatore, StatoPrenotazione.RICHIESTA));
        assertEquals(0, repository.cancelCalls);
    }

    @Test
    void uc13DelegatesOnceAndReturnsResult() throws SQLException {
        assertSame(repository.cancelled, service.annullaAppuntamento(3, 7, StatoPrenotazione.RICHIESTA));
        assertEquals(List.of(3, 7), repository.cancelIds);
        assertEquals(1, repository.cancelCalls);
    }

    @Test
    void uc13PropagatesApplicationAndSqlErrors() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Assente."),
                new ConflittoPrenotazioneException("Conflitto."), new OperazioneNonAutorizzataException("Ruolo."))) {
            repository.error = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.annullaAppuntamento(3, 7, StatoPrenotazione.RICHIESTA)));
        }
        repository.error = null;
        repository.sqlError = new SQLException("SQL.");
        assertSame(repository.sqlError, assertThrows(SQLException.class, () -> service.annullaAppuntamento(3, 7, StatoPrenotazione.RICHIESTA)));
    }

    @Test
    void uc13RejectsNullAndDisallowedExpectedStates() {
        assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamento(3, 7, null));
        for (StatoPrenotazione state : StatoPrenotazione.values()) {
            if (state != StatoPrenotazione.RICHIESTA && state != StatoPrenotazione.CONFERMATA) {
                assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamento(3, 7, state));
            }
        }
        assertEquals(0, repository.cancelCalls);
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void uc13DelegatesExpectedState(StatoPrenotazione expected) throws SQLException {
        service.annullaAppuntamento(3, 7, expected);
        assertEquals(expected, repository.expected);
        assertEquals(1, repository.cancelCalls);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,7", "0,7", "-1,7", "3,null", "3,0", "3,-1"}, nullValues = "null")
    void uc07RejectsInvalidIdsWithoutRepositoryCall(Integer prenotazione, Integer operatore) {
        assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamentoPaziente(prenotazione, operatore, StatoPrenotazione.RICHIESTA));
        assertEquals(0, repository.patientCancelCalls);
    }

    @Test
    void uc07DelegatesOnceAndReturnsResult() throws SQLException {
        assertSame(repository.patientCancelled, service.annullaAppuntamentoPaziente(3, 7, StatoPrenotazione.RICHIESTA));
        assertEquals(List.of(3, 7), repository.patientCancelIds);
        assertEquals(1, repository.patientCancelCalls);
    }

    @Test
    void uc07PropagatesApplicationAndSqlErrors() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Assente."),
                new ConflittoPrenotazioneException("Conflitto."), new IllegalArgumentException("Input."))) {
            repository.error = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.annullaAppuntamentoPaziente(3, 7, StatoPrenotazione.RICHIESTA)));
        }
        repository.error = null;
        repository.sqlError = new SQLException("SQL.");
        assertSame(repository.sqlError, assertThrows(SQLException.class, () -> service.annullaAppuntamentoPaziente(3, 7, StatoPrenotazione.RICHIESTA)));
    }

    @Test
    void uc07RejectsNullAndDisallowedExpectedStates() {
        assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamentoPaziente(3, 7, null));
        for (StatoPrenotazione state : StatoPrenotazione.values()) {
            if (state != StatoPrenotazione.RICHIESTA && state != StatoPrenotazione.CONFERMATA) {
                assertThrows(IllegalArgumentException.class, () -> service.annullaAppuntamentoPaziente(3, 7, state));
            }
        }
        assertEquals(0, repository.patientCancelCalls);
    }

    @ParameterizedTest
    @EnumSource(value = StatoPrenotazione.class, names = {"RICHIESTA", "CONFERMATA"})
    void uc07DelegatesExpectedState(StatoPrenotazione expected) throws SQLException {
        service.annullaAppuntamentoPaziente(3, 7, expected);
        assertEquals(expected, repository.patientExpected);
        assertEquals(1, repository.patientCancelCalls);
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "0", "-1"}, nullValues = "null")
    void uc18RejectsInvalidMedico(Integer id) {
        assertThrows(IllegalArgumentException.class, () -> service.getAgenda(id, null, null, null));
        assertEquals(0, repository.agendaCalls);
    }

    @Test
    void uc18DefaultsToTodayInInjectedClockZone() throws SQLException {
        Clock clock = Clock.fixed(Instant.parse("2030-01-14T23:30:00Z"), ZoneId.of("Europe/Rome"));
        PrenotazioneService controlled = new PrenotazioneService(repository, clock);
        repository.agenda = List.of(new AppuntamentoAgendaResponse());
        assertSame(repository.agenda, controlled.getAgenda(7, null, null, null));
        assertEquals(LocalDate.of(2030, 1, 15), repository.agendaDa);
        assertEquals(repository.agendaDa, repository.agendaA);
        assertEquals(7, repository.agendaMedico);
        assertEquals(1, repository.agendaCalls);
    }

    @ParameterizedTest
    @CsvSource({"1", "31"})
    void uc18AcceptsInclusivePeriodsAndSingleDay(int days) throws SQLException {
        LocalDate day = LocalDate.of(2030, 1, 1);
        assertSame(repository.agenda, service.getAgenda(7, null, day, day.plusDays(days - 1)));
        assertEquals(day, repository.agendaDa);
        assertEquals(day.plusDays(days - 1), repository.agendaA);
        assertEquals(1, repository.agendaCalls);
    }

    @Test
    void uc18SingleDateNormalizesToBothBounds() throws SQLException {
        LocalDate day = LocalDate.of(2030, 1, 15);
        assertTrue(service.getAgenda(7, day, null, null).isEmpty());
        assertEquals(day, repository.agendaDa);
        assertEquals(day, repository.agendaA);
        assertEquals(1, repository.agendaCalls);
    }

    @Test
    void uc18RejectsIncompatibleIncompleteInvertedAndOverlongPeriods() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        for (LocalDate[] dates : new LocalDate[][] {
                {day, day, null}, {day, null, day}, {day, day, day},
                {null, day, null}, {null, null, day}, {null, day.plusDays(1), day},
                {null, day, day.plusDays(31)}, {LocalDate.of(9999, 12, 31), null, null},
                {LocalDate.of(999, 1, 1), null, null}}) {
            assertThrows(IllegalArgumentException.class, () -> service.getAgenda(7, dates[0], dates[1], dates[2]));
        }
        assertEquals(0, repository.agendaCalls);
    }

    @Test
    void uc18PropagatesMissingMedicoAndSqlErrors() {
        repository.error = new RisorsaNonTrovataException("Medico assente.");
        assertSame(repository.error, assertThrows(RisorsaNonTrovataException.class,
                () -> service.getAgenda(7, LocalDate.of(2030, 1, 15), null, null)));
        repository.error = null;
        repository.sqlError = new SQLException("Dati incoerenti.");
        assertSame(repository.sqlError, assertThrows(SQLException.class,
                () -> service.getAgenda(7, LocalDate.of(2030, 1, 15), null, null)));
        assertEquals(2, repository.agendaCalls);
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "0", "-1"}, nullValues = "null")
    void uc06RejectsInvalidPatientBeforeRepository(Integer id) {
        assertThrows(IllegalArgumentException.class, () -> service.getAppuntamentiPaziente(id, null, null, null, null));
        assertEquals(0, repository.patientListCalls);
    }

    @Test
    void uc06UsesExactlyOneClockReadingWithoutDefaultDateAndDelegatesOnce() throws SQLException {
        AtomicInteger reads = new AtomicInteger();
        Instant instant = Instant.parse("2030-01-14T23:30:00Z");
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("Europe/Rome"); }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
            @Override public Instant instant() { reads.incrementAndGet(); return instant; }
        };
        PrenotazioneService controlled = new PrenotazioneService(repository, clock);
        repository.patientList = List.of(new AppuntamentoPazienteResponse());
        assertSame(repository.patientList, controlled.getAppuntamentiPaziente(197, null, null, null, null));
        assertEquals(1, reads.get()); assertEquals(1, repository.patientListCalls);
        assertEquals(197, repository.patientListId);
        assertNull(repository.patientListState); assertNull(repository.patientListDa); assertNull(repository.patientListA);
        assertEquals(LocalDateTime.of(2030, 1, 15, 0, 30), repository.patientListNow);
    }

    @ParameterizedTest
    @EnumSource(StatoPrenotazione.class)
    void uc06AcceptsAllSevenStatesAndNormalizesSingleDate(StatoPrenotazione state) throws SQLException {
        LocalDate day = LocalDate.of(2030, 1, 15);
        assertTrue(service.getAppuntamentiPaziente(197, state, day, null, null).isEmpty());
        assertEquals(state, repository.patientListState);
        assertEquals(day, repository.patientListDa); assertEquals(day, repository.patientListA);
        assertEquals(1, repository.patientListCalls);
    }

    @Test
    void uc06AllowsHistoryLongerThan31Days() throws SQLException {
        LocalDate start = LocalDate.of(2020, 1, 1), end = LocalDate.of(2030, 12, 31);
        service.getAppuntamentiPaziente(197, null, null, start, end);
        assertEquals(start, repository.patientListDa); assertEquals(end, repository.patientListA);
        assertEquals(1, repository.patientListCalls);
    }

    @Test
    void uc06RejectsIncompatibleIncompleteInvertedAndExtremeDates() {
        LocalDate day = LocalDate.of(2030, 1, 1);
        for (LocalDate[] dates : new LocalDate[][] {
                {day, day, null}, {day, null, day}, {day, day, day}, {null, day, null}, {null, null, day},
                {null, day.plusDays(1), day}, {LocalDate.MAX, null, null}, {LocalDate.MIN, null, null},
                {LocalDate.of(9999, 12, 31), null, null}, {LocalDate.of(999, 1, 1), null, null}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getAppuntamentiPaziente(197, null, dates[0], dates[1], dates[2]));
        }
        assertEquals(0, repository.patientListCalls);
    }

    @Test
    void uc06PropagatesMissingPatientAndSqlErrors() {
        repository.error = new RisorsaNonTrovataException("Paziente assente.");
        assertSame(repository.error, assertThrows(RisorsaNonTrovataException.class,
                () -> service.getAppuntamentiPaziente(197, null, null, null, null)));
        repository.error = null; repository.sqlError = new SQLException("Query.");
        assertSame(repository.sqlError, assertThrows(SQLException.class,
                () -> service.getAppuntamentiPaziente(197, null, null, null, null)));
        assertEquals(2, repository.patientListCalls);
    }

    @Test
    void uc20DelegatesOnceAndReturnsSafeResult() throws SQLException {
        assertSame(repository.report, service.segnalaIndisponibilita(7, List.of(9, 3)));
        assertEquals(1, repository.reportCalls);
        assertEquals(7, repository.reportDoctor);
        assertEquals(List.of(9, 3), repository.reportIds);
        assertSame(repository.pending, service.getSegnalazioniPendenti());
        assertEquals(1, repository.pendingCalls);
    }

    @Test
    void uc20InvalidSelectionNeverCallsRepository() {
        for (Integer id : java.util.Arrays.asList(null, 0, -1)) {
            assertThrows(IllegalArgumentException.class, () -> service.segnalaIndisponibilita(id, List.of(3)));
        }
        java.util.List<java.util.List<Integer>> selections = java.util.Arrays.asList(
                null, List.of(), List.of(3, 3), List.of(0), List.of(-1), java.util.Arrays.asList(3, null));
        for (List<Integer> ids : selections) {
            assertThrows(IllegalArgumentException.class, () -> service.segnalaIndisponibilita(7, ids));
        }
        assertEquals(0, repository.reportCalls);
    }

    @Test
    void uc20PropagatesErrorsWithoutRetry() {
        for (RuntimeException error : List.of(new RisorsaNonTrovataException("Medico"),
                new ConflittoPrenotazioneException("Conflitto"))) {
            repository.error = error;
            assertSame(error, assertThrows(error.getClass(), () -> service.segnalaIndisponibilita(7, List.of(3))));
        }
        repository.error = null;
        repository.sqlError = new SQLException("SQL riservato");
        assertSame(repository.sqlError, assertThrows(SQLException.class, () -> service.segnalaIndisponibilita(7, List.of(3))));
        assertSame(repository.sqlError, assertThrows(SQLException.class, service::getSegnalazioniPendenti));
        assertEquals(3, repository.reportCalls);
    }

    @Test
    void uc17ValidatesDelegatesOnceAndPropagatesErrors() throws SQLException {
        var fake = new PrenotazioneRepository(null) {
            int calls;
            @Override public it.medbook.dto.SostituzioneMedicoResponse sostituisciMedicoAtomically(Integer id,
                    Integer operator, Integer substitute, StatoPrenotazione state, Integer unavailable) throws SQLException {
                calls++; assertEquals(1,id); assertEquals(2,operator); assertEquals(3,substitute); assertEquals(4,unavailable);
                if (calls==2) { throw new SQLException("SQL"); }
                if (calls==3) { throw new ConflittoPrenotazioneException("Conflitto"); }
                return new it.medbook.dto.SostituzioneMedicoResponse();
            }
        };
        var subject = new PrenotazioneService(fake);
        for (Integer invalid : java.util.Arrays.asList(null,0,-1)) {
            assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(invalid,2,3,StatoPrenotazione.RICHIESTA,4));
            assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(1,invalid,3,StatoPrenotazione.RICHIESTA,4));
            assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(1,2,invalid,StatoPrenotazione.RICHIESTA,4));
            assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(1,2,3,StatoPrenotazione.RICHIESTA,invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(1,2,3,null,4));
        assertThrows(IllegalArgumentException.class, () -> subject.sostituisciMedico(1,2,3,StatoPrenotazione.ANNULLATA,4));
        assertEquals(0,fake.calls);
        assertNotNull(subject.sostituisciMedico(1,2,3,StatoPrenotazione.RICHIESTA,4)); assertEquals(1,fake.calls);
        assertThrows(SQLException.class, () -> subject.sostituisciMedico(1,2,3,StatoPrenotazione.CONFERMATA,4));
        assertThrows(ConflittoPrenotazioneException.class, () -> subject.sostituisciMedico(1,2,3,StatoPrenotazione.CONFERMATA,4));
        assertEquals(3,fake.calls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RICHIESTA","CONFERMATA","RIFIUTATA","ANNULLATA","EFFETTUATA","PAZIENTE_ASSENTE","NON_EROGATA","TUTTE"})
    void consultationOnlyDelegatesStateAndKeepsFilters(String state) throws SQLException {
        service.getRichieste(7,11,LocalDate.of(2030,1,15),LocalDate.of(2030,1,20),state);
        assertEquals("TUTTE".equals(state)?null:StatoPrenotazione.valueOf(state),repository.statoConsultazione);
        assertEquals(7,repository.medico); assertEquals(11,repository.prestazione);
        assertEquals(1,repository.searchCalls); assertEquals(0,repository.calls);
    }
    @Test void invalidConsultationNeverDelegates() {
        for(String state:new String[]{null,"","confermata","UNKNOWN"})assertThrows(IllegalArgumentException.class,()->service.getRichieste(null,null,null,null,state));
        assertThrows(IllegalArgumentException.class,()->service.getRichieste(0,null,null,null,"TUTTE"));
        assertThrows(IllegalArgumentException.class,()->service.getRichieste(null,null,LocalDate.of(2030,1,20),LocalDate.of(2030,1,15),"RIFIUTATA"));
        assertEquals(0,repository.searchCalls);
    }

    private static class FakeRepository extends PrenotazioneRepository {
        private int patientListCalls;
        private Integer patientListId;
        private StatoPrenotazione patientListState;
        private LocalDate patientListDa;
        private LocalDate patientListA;
        private LocalDateTime patientListNow;
        private List<AppuntamentoPazienteResponse> patientList = List.of();

        @Override
        public List<AppuntamentoPazienteResponse> findByPaziente(Integer patient, StatoPrenotazione state,
                LocalDate da, LocalDate a, LocalDateTime now) throws SQLException {
            patientListCalls++; patientListId = patient; patientListState = state;
            patientListDa = da; patientListA = a; patientListNow = now;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return patientList;
        }


        private int agendaCalls;
        private Integer agendaMedico;
        private LocalDate agendaDa;
        private LocalDate agendaA;
        private List<AppuntamentoAgendaResponse> agenda = List.of();

        @Override
        public List<AppuntamentoAgendaResponse> findAgenda(Integer medico, LocalDate da, LocalDate a) throws SQLException {
            agendaCalls++;
            agendaMedico = medico;
            agendaDa = da;
            agendaA = a;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return agenda;
        }


        private int cancelCalls;
        private List<Integer> cancelIds;
        private StatoPrenotazione expected;
        private final AnnullamentoPrenotazioneResponse cancelled = new AnnullamentoPrenotazioneResponse();

        @Override
        public AnnullamentoPrenotazioneResponse annullaAtomically(Integer booking, Integer operator, StatoPrenotazione expected)
                throws SQLException {
            cancelCalls++;
            cancelIds = List.of(booking, operator);
            this.expected = expected;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return cancelled;
        }

        private int patientCancelCalls;
        private List<Integer> patientCancelIds;
        private StatoPrenotazione patientExpected;
        private final AnnullamentoPazienteResponse patientCancelled = new AnnullamentoPazienteResponse();

        @Override
        public AnnullamentoPazienteResponse annullaPazienteAtomically(Integer booking, Integer operator, StatoPrenotazione expected)
                throws SQLException {
            patientCancelCalls++;
            patientCancelIds = List.of(booking, operator);
            this.patientExpected = expected;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return patientCancelled;
        }

        private int confirmCalls;
        private List<Integer> confirmIds;
        private final ConfermaPrenotazioneResponse confirmed = new ConfermaPrenotazioneResponse();

        @Override
        public ConfermaPrenotazioneResponse confermaAtomically(Integer prenotazione, Integer operatore)
                throws SQLException {
            confirmCalls++;
            confirmIds = List.of(prenotazione, operatore);
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return confirmed;
        }

        private int rejectCalls;
        private List<Integer> rejectIds;
        private final RifiutoPrenotazioneResponse rejected = new RifiutoPrenotazioneResponse();

        @Override
        public RifiutoPrenotazioneResponse rifiutaAtomically(Integer prenotazione, Integer operatore)
                throws SQLException {
            rejectCalls++;
            rejectIds = List.of(prenotazione, operatore);
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return rejected;
        }

        private List<RichiestaPrenotazioneResponse> richieste = List.of();
        private Integer medico;
        private StatoPrenotazione statoConsultazione;
        @Override public List<RichiestaPrenotazioneResponse> findRichieste(Integer medico,Integer prestazione,LocalDate da,LocalDate a,StatoPrenotazione stato) throws SQLException {
            statoConsultazione=stato;
            return findRichieste(medico,prestazione,da,a);
        }
        private Integer prestazione;
        private LocalDate da;
        private LocalDate a;
        private int searchCalls;

        @Override
        public List<RichiestaPrenotazioneResponse> findRichieste(Integer medico, Integer prestazione,
                                                              LocalDate da, LocalDate a) throws SQLException {
            searchCalls++;
            this.medico = medico;
            this.prestazione = prestazione;
            this.da = da;
            this.a = a;
            if (sqlError != null) { throw sqlError; }
            return richieste;
        }

        private int calls;
        private Integer paziente;
        private Integer disponibilita;
        private final Prenotazione result = new Prenotazione();
        private RuntimeException error;
        private SQLException sqlError;

        private int reportCalls;
        private int pendingCalls;
        private Integer reportDoctor;
        private List<Integer> reportIds;
        private final it.medbook.dto.SegnalazioneIndisponibilitaResponse report = new it.medbook.dto.SegnalazioneIndisponibilitaResponse();
        private final List<it.medbook.dto.SegnalazionePendenteResponse> pending = List.of();

        @Override
        public it.medbook.dto.SegnalazioneIndisponibilitaResponse segnalaIndisponibilitaAtomically(
                Integer doctor, List<Integer> ids) throws SQLException {
            reportCalls++; reportDoctor = doctor; reportIds = ids;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return report;
        }

        @Override
        public List<it.medbook.dto.SegnalazionePendenteResponse> findSegnalazioniPendenti() throws SQLException {
            pendingCalls++;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return pending;
        }

        FakeRepository() { super(null); }

        @Override
        public Prenotazione creaRichiestaAtomically(Integer paziente, Integer disponibilita) throws SQLException {
            calls++;
            this.paziente = paziente;
            this.disponibilita = disponibilita;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return result;
        }
    }
}
