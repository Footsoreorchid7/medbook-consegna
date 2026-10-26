package it.medbook.service;
import it.medbook.config.*;
import it.medbook.repository.PagamentoRepository;
import it.medbook.dto.PagamentoResponse;
import it.medbook.model.ModalitaPagamento;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
class PagamentoServiceTest {
    static class Fake extends PagamentoRepository {
        int calls; SQLException error; final PagamentoResponse result=new PagamentoResponse();
        Fake(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        @Override public PagamentoResponse registraInSedeAtomically(Integer p,Integer o,ModalitaPagamento m,BigDecimal a)throws SQLException{
            calls++; assertEquals(3,p);assertEquals(7,o);assertEquals(ModalitaPagamento.DESK_POS,m);assertEquals(0,new BigDecimal("80").compareTo(a));
            if(error!=null)throw error;return result;
        }
    }
    final Fake repo=new Fake();final PagamentoService service=new PagamentoService(repo);
    @Test void delegatesOnce(){assertSame(repo.result,assertDoesNotThrow(()->service.registraPagamentoInSede(3,7,ModalitaPagamento.DESK_POS,new BigDecimal("80.00"))));assertEquals(1,repo.calls);}
    @Test void propagatesSql(){repo.error=new SQLException("synthetic");assertSame(repo.error,assertThrows(SQLException.class,()->service.registraPagamentoInSede(3,7,ModalitaPagamento.DESK_POS,new BigDecimal("80"))));}
    @Test void rejectsIdsModesAndMissingAmount(){for(Integer id:new Integer[]{null,0,-1}){
        assertThrows(IllegalArgumentException.class,()->service.registraPagamentoInSede(id,7,ModalitaPagamento.DESK_POS,BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,()->service.registraPagamentoInSede(3,id,ModalitaPagamento.DESK_POS,BigDecimal.ONE));}
        for(ModalitaPagamento mode:new ModalitaPagamento[]{null,ModalitaPagamento.ONLINE})assertThrows(IllegalArgumentException.class,()->service.registraPagamentoInSede(3,7,mode,BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,()->service.registraPagamentoInSede(3,7,ModalitaPagamento.DESK_POS,null));assertEquals(0,repo.calls);
    }
    @ParameterizedTest @ValueSource(strings={"-1","80.001","100000000","1e100"}) void rejectsAmounts(String value){assertThrows(IllegalArgumentException.class,()->service.registraPagamentoInSede(3,7,ModalitaPagamento.DESK_POS,new BigDecimal(value)));assertEquals(0,repo.calls);}
}
