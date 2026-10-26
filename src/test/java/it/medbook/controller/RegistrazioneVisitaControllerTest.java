package it.medbook.controller;

import com.fasterxml.jackson.databind.*;
import it.medbook.config.*;
import it.medbook.dto.RegistrazioneVisitaResponse;
import it.medbook.exception.*;
import it.medbook.model.StatoPrenotazione;
import it.medbook.repository.PrenotazioneRepository;
import it.medbook.service.PrenotazioneService;
import it.medbook.support.AuthenticatedTestExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneVisitaControllerTest {
    static class Fake extends PrenotazioneService {
        int calls;Throwable error;
        Fake(){super(new PrenotazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())));}
        @Override public RegistrazioneVisitaResponse registraVisita(Integer booking,Integer doctor)throws SQLException {
            calls++;assertEquals(3,booking);assertEquals(7,doctor);
            if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;
            var result=new RegistrazioneVisitaResponse();result.setIdPrenotazione(booking);result.setStato(StatoPrenotazione.EFFETTUATA);return result;
        }
    }
    final Fake service=new Fake();final ObjectMapper mapper=new ObjectMapper();
    final PrenotazioneController controller=new PrenotazioneController(service,mapper);
    static final String PATH="/api/prenotazioni/3/registra-visita";
    AuthenticatedTestExchange invoke(String method,String path,String body,Integer actor)throws Exception{
        var x=new AuthenticatedTestExchange(method,path,body,actor);controller.handle(x);
        assertTrue(x.exchange.closed);assertEquals("application/json; charset=UTF-8",x.exchange.responseHeaders.getFirst("Content-Type"));return x;
    }
    @Test void principalIsOnlyIdentityAndResponseHasTwoFields()throws Exception{
        var x=invoke("POST",PATH,"{}",7);assertEquals(200,x.exchange.status);
        var json=mapper.readTree(x.exchange.body());assertEquals(2,json.size());
        assertEquals(3,json.get("idPrenotazione").intValue());assertEquals("EFFETTUATA",json.get("stato").textValue());assertEquals(1,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={""," ","null","[]","{","{} {}","{\"idMedico\":7}","{\"stato\":\"EFFETTUATA\"}","{\"a\":1,\"a\":1}"})
    void invalidJson(String body)throws Exception{assertEquals(400,invoke("POST",PATH,body,7).exchange.status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"0","-1","x","2147483648","+3","3.0"})
    void invalidId(String id)throws Exception{assertEquals(400,invoke("POST","/api/prenotazioni/"+id+"/registra-visita","{}",7).exchange.status);assertEquals(0,service.calls);}
    @Test void exactBodyLimitAndQuery()throws Exception{
        assertEquals(200,invoke("POST",PATH,"{}"+" ".repeat(8190),7).exchange.status);
        assertEquals(400,invoke("POST",PATH,"{}"+" ".repeat(8191),7).exchange.status);
        for(String query:new String[]{"?","?x=1"})assertEquals(400,invoke("POST",PATH+query,"{}",7).exchange.status);
        assertEquals(1,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"GET","PUT","DELETE","PATCH","HEAD"})
    void wrongMethod(String method)throws Exception{var x=invoke(method,PATH,"{}",7);assertEquals(405,x.exchange.status);assertEquals("POST",x.exchange.responseHeaders.getFirst("Allow"));assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"/api/prenotazioni/3/registra-visita/","/api/prenotazioni/3/registra-visita/x","/api/prenotazioni/%33/registra-visita","/api/prenotazioni/3/registra%2Dvisita"})
    void noAliases(String path)throws Exception{assertEquals(404,invoke("POST",path,"{}",7).exchange.status);assertEquals(0,service.calls);}
    @Test void sessionRequired()throws Exception{assertEquals(401,invoke("POST",PATH,"{}",null).exchange.status);assertEquals(0,service.calls);}
    @Test void errorsDoNotLeakSql()throws Exception{
        Throwable[] errors={new RisorsaNonTrovataException("private"),new ConflittoPrenotazioneException("conflict"),new IllegalArgumentException("bad"),new SQLException("SECRET SQL"),new IllegalStateException("SECRET")};
        int[] codes={404,409,400,500,500};for(int i=0;i<codes.length;i++){service.error=errors[i];var x=invoke("POST",PATH,"{}",7);assertEquals(codes[i],x.exchange.status);assertFalse(x.exchange.body().contains("SECRET"));assertFalse(x.exchange.body().contains("private"));}
    }
    @Test void serializationFailureIs500AfterOneCall()throws Exception{
        ObjectMapper broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value)throws com.fasterxml.jackson.core.JsonProcessingException{throw new com.fasterxml.jackson.core.JsonProcessingException("SECRET"){};}};
        var x=new AuthenticatedTestExchange("POST",PATH,"{}",7);new PrenotazioneController(service,broken).handle(x);
        assertEquals(500,x.exchange.status);assertTrue(x.exchange.closed);assertEquals(1,service.calls);assertFalse(x.exchange.body().contains("SECRET"));
    }
}
