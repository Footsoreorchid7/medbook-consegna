package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.VerificaMancataErogazioneResponse;
import it.medbook.exception.*;
import it.medbook.model.StatoPrenotazione;
import it.medbook.repository.PrenotazioneRepository;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class VerificaMancataErogazioneServiceTest {
    static class Fake extends PrenotazioneRepository {
        StatoPrenotazione expectedOutcome = StatoPrenotazione.NON_EROGATA;
        int calls; RuntimeException conflict; SQLException error;
        final VerificaMancataErogazioneResponse result = new VerificaMancataErogazioneResponse();
        Fake() { super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())); }
        @Override public VerificaMancataErogazioneResponse verificaMancataErogazioneAtomically(Integer p,
                Integer o, StatoPrenotazione expected, StatoPrenotazione outcome) throws SQLException {
            calls++; assertEquals(3,p); assertEquals(7,o); assertEquals(StatoPrenotazione.CONFERMATA,expected);
            assertEquals(expectedOutcome,outcome);
            if (error != null) { throw error; } if (conflict != null) { throw conflict; } return result;
        }
    }
    final Fake repo = new Fake(); final PrenotazioneService service = new PrenotazioneService(repo);
    @Test void delegatesExactlyOnce() throws Exception {
        assertSame(repo.result, service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA));
        assertEquals(1,repo.calls);
    }
    @Test void delegatesPatientAbsenceExactlyOnce() throws Exception {
        repo.expectedOutcome = StatoPrenotazione.PAZIENTE_ASSENTE;
        assertSame(repo.result, service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,StatoPrenotazione.PAZIENTE_ASSENTE));
        assertEquals(1,repo.calls);
    }
    @Test void rejectsInvalidIdsWithoutDelegating() {
        for (Integer id : new Integer[]{null,0,-1}) {
            assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(id,7,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA));
            assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(3,id,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA));
        } assertEquals(0,repo.calls);
    }
    @Test void rejectsInvalidExpectedStateAndOutcome() {
        for (StatoPrenotazione state : StatoPrenotazione.values()) {
            if (state != StatoPrenotazione.CONFERMATA) assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(3,7,state,StatoPrenotazione.NON_EROGATA));
            if (state != StatoPrenotazione.NON_EROGATA && state != StatoPrenotazione.PAZIENTE_ASSENTE) assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,state));
        }
        assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(3,7,null,StatoPrenotazione.NON_EROGATA));
        assertThrows(IllegalArgumentException.class,()->service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,null));
        assertEquals(0,repo.calls);
    }
    @Test void propagatesOriginalErrors() {
        repo.error = new SQLException("synthetic");
        assertSame(repo.error,assertThrows(SQLException.class,()->service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA)));
        repo.error = null;
        for (RuntimeException error : new RuntimeException[]{new RisorsaNonTrovataException("missing"),new OperazioneNonAutorizzataException("role"),new ConflittoPrenotazioneException("conflict")}) {
            repo.conflict = error;
            assertSame(error,assertThrows(error.getClass(),()->service.verificaMancataErogazione(3,7,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA)));
        }
        assertEquals(4,repo.calls);
    }
}
