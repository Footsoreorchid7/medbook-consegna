package it.medbook.service;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.PrenotazioneDeskResponse;
import it.medbook.repository.PrenotazioneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** validazione del periodo e del filtro prima della lettura desk. */
class PrenotazioniDeskServiceTest {
    static class FakeRepository extends PrenotazioneRepository {
        int calls; Integer medico; LocalDate da, a; SQLException error;
        final List<PrenotazioneDeskResponse> result = List.of(new PrenotazioneDeskResponse());
        FakeRepository() { super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())); }
        @Override public List<PrenotazioneDeskResponse> findPrenotazioniDesk(Integer medico, LocalDate da, LocalDate a)
                throws SQLException {
            calls++; this.medico = medico; this.da = da; this.a = a;
            if (error != null) { throw error; }
            return result;
        }
    }

    final FakeRepository repository = new FakeRepository();
    final PrenotazioneService service = new PrenotazioneService(repository);
    static final LocalDate DAY = LocalDate.of(2030, 1, 1);

    @ParameterizedTest
    @CsvSource({"1", "2", "31"})
    void acceptsInclusivePeriodsUpTo31Days(int days) throws SQLException {
        assertSame(repository.result, service.getPrenotazioniDesk(null, null, DAY, DAY.plusDays(days - 1)));
        assertEquals(DAY, repository.da); assertEquals(DAY.plusDays(days - 1), repository.a);
        assertNull(repository.medico); assertEquals(1, repository.calls);
    }

    @Test
    void singleDateNormalizesToBothBoundsAndPassesMedico() throws SQLException {
        service.getPrenotazioniDesk(7, DAY, null, null);
        assertEquals(DAY, repository.da); assertEquals(DAY, repository.a); assertEquals(7, repository.medico);
    }

    @Test
    void rejectsMissingIncompatibleIncompleteInvertedOverlongAndUnrepresentablePeriods() {
        LocalDate[][] invalid = {
                {null, null, null}, {DAY, DAY, null}, {DAY, null, DAY}, {DAY, DAY, DAY},
                {null, DAY, null}, {null, null, DAY}, {null, DAY.plusDays(1), DAY},
                {null, DAY, DAY.plusDays(31)}, {LocalDate.of(9999, 12, 31), null, null},
                {LocalDate.of(999, 12, 31), null, null}, {null, LocalDate.of(999, 12, 31), LocalDate.of(1000, 1, 1)},
                {null, LocalDate.of(9999, 12, 30), LocalDate.of(9999, 12, 31)}};
        for (LocalDate[] dates : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.getPrenotazioniDesk(null, dates[0], dates[1], dates[2]));
        }
        assertEquals(0, repository.calls);
    }

    @ParameterizedTest
    @CsvSource({"0", "-1"})
    void rejectsInvalidMedico(int medico) {
        assertThrows(IllegalArgumentException.class, () -> service.getPrenotazioniDesk(medico, DAY, null, null));
        assertEquals(0, repository.calls);
    }

    @Test
    void propagatesSqlErrors() {
        repository.error = new SQLException("Dati incoerenti nell'elenco desk.");
        assertSame(repository.error, assertThrows(SQLException.class, () -> service.getPrenotazioniDesk(null, DAY, null, null)));
    }
}
