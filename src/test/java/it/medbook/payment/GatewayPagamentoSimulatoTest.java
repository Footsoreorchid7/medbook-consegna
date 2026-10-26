package it.medbook.payment;

import it.medbook.model.StatoPagamento;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayPagamentoSimulatoTest {
    @Test void defaultIsSuccessfulSimulation() {
        assertEquals(StatoPagamento.PAGATO,new GatewayPagamentoSimulato().paga("SIM-test",BigDecimal.TEN));
    }
    @Test void failureIsDeterministicAndImmutable() {
        var gateway=new GatewayPagamentoSimulato(StatoPagamento.FALLITO);
        for(int i=0;i<10;i++)assertEquals(StatoPagamento.FALLITO,gateway.paga("SIM-"+i,BigDecimal.TEN));
    }
    @Test void rejectsPendingAndNull() {
        assertThrows(IllegalArgumentException.class,()->new GatewayPagamentoSimulato(StatoPagamento.IN_ATTESA));
        assertThrows(IllegalArgumentException.class,()->new GatewayPagamentoSimulato(null));
    }
}
