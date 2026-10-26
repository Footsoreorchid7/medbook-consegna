package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.RegistrazioneVisitaResponse;
import it.medbook.exception.*;
import it.medbook.repository.PrenotazioneRepository;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneVisitaServiceTest {
    static class Fake extends PrenotazioneRepository {
        int calls; Throwable error;
        final RegistrazioneVisitaResponse result=new RegistrazioneVisitaResponse();
        Fake(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        @Override public RegistrazioneVisitaResponse registraVisitaAtomically(Integer p,Integer m)throws SQLException {
            calls++;assertEquals(3,p);assertEquals(7,m);
            if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;
            return result;
        }
    }
    final Fake repo=new Fake();final PrenotazioneService service=new PrenotazioneService(repo);
    @Test void delegatesExactlyOnce()throws Exception {
        assertSame(repo.result,service.registraVisita(3,7));assertEquals(1,repo.calls);
    }
    @Test void invalidIdsNeverReachRepository(){
        for(Integer id:new Integer[]{null,0,-1}){
            assertThrows(IllegalArgumentException.class,()->service.registraVisita(id,7));
            assertThrows(IllegalArgumentException.class,()->service.registraVisita(3,id));
        }assertEquals(0,repo.calls);
    }
    @Test void propagatesOriginalErrors(){
        for(Throwable e:new Throwable[]{new SQLException("synthetic"),new RisorsaNonTrovataException("missing"),new ConflittoPrenotazioneException("conflict")}){
            repo.error=e;assertSame(e,assertThrows(e.getClass(),()->service.registraVisita(3,7)));
        }assertEquals(3,repo.calls);
    }
}
