package it.medbook.service;

import it.medbook.repository.RefertoRepository;
import it.medbook.referto.RefertoStorage;
import it.medbook.exception.RisorsaNonTrovataException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.sql.SQLException;
import java.time.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConsultazioneRefertoServiceTest {
    static class Fake extends RefertoRepository {
        int calls,booking,patient;Exception failure;
        Fake(){super(null,Clock.systemUTC());}
        @Override public Documento findForPatient(int b,int p)throws SQLException {calls++;booking=b;patient=p;if(failure instanceof SQLException e)throw e;if(failure instanceof RuntimeException e)throw e;return new Documento(12,b,"fiction.pdf",LocalDateTime.of(2030,1,1,10,0),"private-key");}
    }
    @Test void metadataSingleDelegationAndNoInternalKey()throws Exception {var fake=new Fake();var service=new RefertoService(fake,null,null);var dto=service.metadata(197,7);assertEquals(1,fake.calls);assertEquals(197,fake.booking);assertEquals(7,fake.patient);assertEquals(12,dto.idReferto());assertEquals("fiction.pdf",dto.nomeFile());}
    @ParameterizedTest @NullSource @ValueSource(ints={0,-1})void invalidIds(Integer id){var fake=new Fake();var service=new RefertoService(fake,null,null);assertThrows(IllegalArgumentException.class,()->service.metadata(id,7));assertThrows(IllegalArgumentException.class,()->service.download(197,id));assertEquals(0,fake.calls);}
    @Test void missingAndSqlErrorBeforeStorage() {var fake=new Fake();var service=new RefertoService(fake,null,null);fake.failure=new RisorsaNonTrovataException("Referto non trovato.");assertThrows(RisorsaNonTrovataException.class,()->service.download(197,7));fake.failure=new SQLException("fixture");assertThrows(SQLException.class,()->service.metadata(197,7));}
}
