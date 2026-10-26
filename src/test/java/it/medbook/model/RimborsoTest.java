package it.medbook.model;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RimborsoTest {
    @Test void decisionsAreExclusiveAndPreserveOriginalData(){for(boolean approve:new boolean[]{true,false}){var r=new Rimborso();r.setStato(StatoRimborso.RICHIESTO);r.setMotivazione("Originale paziente");r.setImporto(new BigDecimal("80.00"));Pagamento p=new Pagamento();p.setStato(StatoPagamento.PAGATO);r.setPagamento(p);if(approve)r.approva();else r.respingi();assertEquals(approve?StatoRimborso.APPROVATO:StatoRimborso.RESPINTO,r.getStato());assertNotNull(r.getVerificatoIl());assertThrows(IllegalStateException.class,r::approva);assertThrows(IllegalStateException.class,r::respingi);assertEquals("Originale paziente",r.getMotivazione());assertEquals(new BigDecimal("80.00"),r.getImporto());assertEquals(StatoPagamento.PAGATO,p.getStato());assertNull(r.getProviderRefundId());assertNull(r.getRimborsatoIl());}}
    @ParameterizedTest @EnumSource(value=StatoRimborso.class,names={"RICHIESTO"},mode=EnumSource.Mode.EXCLUDE)void otherStatesCannotBeDecided(StatoRimborso state){var r=new Rimborso();r.setStato(state);assertThrows(IllegalStateException.class,r::approva);assertThrows(IllegalStateException.class,r::respingi);assertEquals(state,r.getStato());assertNull(r.getVerificatoIl());}
}
