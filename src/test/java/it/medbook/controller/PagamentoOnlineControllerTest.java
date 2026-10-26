package it.medbook.controller;

import it.medbook.service.*;
import it.medbook.dto.*;
import it.medbook.model.*;
import it.medbook.config.*;
import it.medbook.repository.*;
import it.medbook.exception.*;
import it.medbook.support.AuthenticatedTestExchange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PagamentoOnlineControllerTest {
    static class Fake extends PagamentoService {
        int calls;Throwable error; boolean failed,empty;
        Fake(){super(new PagamentoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())));}
        PagamentoPubblicoResponse response(Integer p,Integer actor)throws SQLException {
            calls++;assertEquals(3,p);assertEquals(7,actor);
            if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;
            var r=new PagamentoPubblicoResponse();r.setIdPagamento(42);r.setIdPrenotazione(p);
            r.setModalita(ModalitaPagamento.ONLINE);r.setStato(failed?StatoPagamento.FALLITO:StatoPagamento.PAGATO);
            r.setImporto(new BigDecimal("80.00"));r.setCreatoIl(LocalDateTime.of(2030,1,1,8,0));
            r.setPagatoIl(failed?null:r.getCreatoIl());return r;
        }
        @Override public PagamentoPubblicoResponse pagaOnline(Integer p,Integer actor,BigDecimal amount)throws SQLException {
            PagamentoRepository.validaOnline(p,actor,amount);assertEquals(0,new BigDecimal("80").compareTo(amount));return response(p,actor);
        }
        @Override public List<PagamentoPubblicoResponse> getPagamenti(Integer p,Integer actor)throws SQLException {
            var r=response(p,actor);return empty?List.of():List.of(r);
        }
    }
    final Fake service=new Fake();final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final PrenotazioneController controller=new PrenotazioneController(null,mapper,service);
    static final String POST="/api/prenotazioni/3/pagamento-online",GET="/api/prenotazioni/3/pagamenti",BODY="{\"importoAtteso\":80.00}";
    AuthenticatedTestExchange invoke(String method,String path,String body,Integer actor)throws Exception {
        var x=new AuthenticatedTestExchange(method,path,body,actor);controller.handle(x);
        assertTrue(x.exchange.closed);assertEquals("application/json; charset=UTF-8",x.exchange.responseHeaders.getFirst("Content-Type"));return x;
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void terminalResultCreatedWithSevenSafeFields(boolean failure)throws Exception {
        service.failed=failure;var x=invoke("POST",POST,BODY,7);assertEquals(201,x.exchange.status);
        var j=mapper.readTree(x.exchange.body());assertEquals(7,j.size());assertEquals(42,j.get("idPagamento").intValue());
        assertEquals("ONLINE",j.get("modalita").textValue());assertEquals(failure?"FALLITO":"PAGATO",j.get("stato").textValue());
        assertEquals("2030-01-01T08:00:00",j.get("creatoIl").textValue());
        if(failure)assertTrue(j.get("pagatoIl").isNull());else assertEquals(j.get("creatoIl"),j.get("pagatoIl"));
        for(String secret:List.of("providerPaymentId","registratoDa","idOperatore","passwordHash","email"))assertFalse(j.has(secret));
    }
    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "{}", "{\"importoAtteso\":null}","{\"importoAtteso\":\"80\"}",
        "{\"importoAtteso\":true}","{\"importoAtteso\":0}","{\"importoAtteso\":-1}","{\"importoAtteso\":80.001}","{\"importoAtteso\":80.000}",
        "{\"importoAtteso\":100000000}","{\"importoAtteso\":80,\"stato\":\"PAGATO\"}","{\"importoAtteso\":80,\"idPaziente\":7}",
        "{\"importoAtteso\":80,\"providerPaymentId\":\"SIM-x\"}","{\"importoAtteso\":80,\"modalita\":\"ONLINE\"}",
        "{\"importoAtteso\":80,\"esito\":\"PAGATO\"}","{\"importoAtteso\":80,\"importoAtteso\":80}","{\"importoAtteso\":80} {}"})
    void rejectsInvalidJsonBeforeService(String body)throws Exception {
        assertEquals(400,invoke("POST",POST,body,7).exchange.status);assertEquals(0,service.calls);
    }
    @Test void bodyLimitIs8192BytesInclusive()throws Exception {
        String prefix=BODY+" ".repeat(8192-BODY.length());assertEquals(201,invoke("POST",POST,prefix,7).exchange.status);
        assertEquals(400,invoke("POST",POST,prefix+" ",7).exchange.status);assertEquals(1,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","2147483648","x","%33","+3"}) void invalidIds(String id)throws Exception {
        assertEquals(400,invoke("POST","/api/prenotazioni/"+id+"/pagamento-online",BODY,7).exchange.status);assertEquals(0,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={POST+"/",POST+"/extra","/api/prenotazioni/3/pagamento%2Donline"}) void exactRoutes(String path)throws Exception {
        assertEquals(404,invoke("POST",path,BODY,7).exchange.status);assertEquals(0,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={POST,GET}) void queriesRejected(String path)throws Exception {
        assertEquals(400,invoke(path.equals(POST)?"POST":"GET",path+"?esito=PAGATO",BODY,7).exchange.status);assertEquals(0,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"GET","PUT","DELETE","HEAD"}) void postMethodWhitelist(String method)throws Exception {
        var x=invoke(method,POST,BODY,7);assertEquals(405,x.exchange.status);assertEquals("POST",x.exchange.responseHeaders.getFirst("Allow"));assertEquals(0,service.calls);
    }
    @Test void getMethodAndEmptyAndList()throws Exception {
        var x=invoke("POST",GET,"",7);assertEquals(405,x.exchange.status);assertEquals("GET",x.exchange.responseHeaders.getFirst("Allow"));
        service.empty=true;assertEquals("[]",invoke("GET",GET,"",7).exchange.body());
        service.empty=false;x=invoke("GET",GET,"",7);assertEquals(200,x.exchange.status);assertEquals(42,mapper.readTree(x.exchange.body()).get(0).get("idPagamento").intValue());
    }
    @ParameterizedTest @ValueSource(strings={POST,GET}) void missingPrincipal(String path)throws Exception {
        assertEquals(401,invoke(path.equals(POST)?"POST":"GET",path,BODY,null).exchange.status);assertEquals(0,service.calls);
    }
    @Test void businessAndSqlErrors()throws Exception {
        for(Throwable e:List.of(new RisorsaNonTrovataException("missing"),new ConflittoPrenotazioneException("conflict"),new SQLException("secret SQL"),new IllegalStateException("gateway secret"))) {
            service.error=e;var x=invoke("POST",POST,BODY,7);assertEquals(e instanceof RisorsaNonTrovataException?404:e instanceof ConflittoPrenotazioneException?409:500,x.exchange.status);
            assertFalse(x.exchange.body().contains("secret"));
        }
    }
    @Test void serializationErrorIs500Not400()throws Exception {
        var broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value)throws JsonProcessingException {throw new com.fasterxml.jackson.core.JsonGenerationException("secret serialization",(com.fasterxml.jackson.core.JsonGenerator)null);}};
        var x=new AuthenticatedTestExchange("POST",POST,BODY,7);new PrenotazioneController(null,broken,service).handle(x);
        assertEquals(500,x.exchange.status);assertFalse(x.exchange.body().contains("secret"));assertTrue(x.exchange.closed);
    }
}
