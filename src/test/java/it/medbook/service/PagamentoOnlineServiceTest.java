package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.PagamentoPubblicoResponse;
import it.medbook.repository.PagamentoRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PagamentoOnlineServiceTest {
    static class Fake extends PagamentoRepository {
        int writes,reads; SQLException error;
        final PagamentoPubblicoResponse response=new PagamentoPubblicoResponse();
        final List<PagamentoPubblicoResponse> list=List.of(response);
        Fake(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        @Override public PagamentoPubblicoResponse registraOnlineAtomically(Integer p,Integer patient,BigDecimal amount)throws SQLException {
            writes++;assertEquals(3,p);assertEquals(7,patient);assertEquals(0,new BigDecimal("80").compareTo(amount));
            if(error!=null)throw error;return response;
        }
        @Override public List<PagamentoPubblicoResponse> findByPrenotazionePaziente(Integer p,Integer patient)throws SQLException {
            reads++;assertEquals(3,p);assertEquals(7,patient);if(error!=null)throw error;return list;
        }
    }
    final Fake repository=new Fake();final PagamentoService service=new PagamentoService(repository);
    @Test void delegatesWriteAndReadOnce()throws Exception {
        assertSame(repository.response,service.pagaOnline(3,7,new BigDecimal("80.00")));
        assertSame(repository.list,service.getPagamenti(3,7));assertEquals(1,repository.writes);assertEquals(1,repository.reads);
    }
    @Test void propagatesSqlWithoutRetry() {
        repository.error=new SQLException("synthetic");
        assertSame(repository.error,assertThrows(SQLException.class,()->service.pagaOnline(3,7,new BigDecimal("80"))));
        assertSame(repository.error,assertThrows(SQLException.class,()->service.getPagamenti(3,7)));
        assertEquals(1,repository.writes);assertEquals(1,repository.reads);
    }
    @Test void rejectsMissingAndNonPositiveIdsAndAmount() {
        for(Integer id:new Integer[]{null,0,-1}) {
            assertThrows(IllegalArgumentException.class,()->service.pagaOnline(id,7,BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,()->service.pagaOnline(3,id,BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class,()->service.getPagamenti(id,7));
            assertThrows(IllegalArgumentException.class,()->service.getPagamenti(3,id));
        }
        assertThrows(IllegalArgumentException.class,()->service.pagaOnline(3,7,null));
        assertEquals(0,repository.writes);assertEquals(0,repository.reads);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","80.001","80.000","100000000","1e100"})
    void rejectsInvalidAmount(String value) {
        assertThrows(IllegalArgumentException.class,()->service.pagaOnline(3,7,new BigDecimal(value)));
        assertEquals(0,repository.writes);
    }
}
