package it.medbook.controller;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.config.*;
import it.medbook.repository.*;
import it.medbook.service.*;
import it.medbook.dto.*;
import it.medbook.exception.*;
import it.medbook.model.StatoRimborso;
import it.medbook.support.AuthenticatedTestExchange;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RimborsoControllerTest {
    static class Fake extends RimborsoService {
        int calls;Throwable error;String motive;String filtro;Integer actor;boolean approve;
        Fake(){super(new RimborsoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())));}
        void call()throws SQLException{calls++;if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;}
        @Override public RimborsoResponse richiedi(Integer b,Integer a,String m)throws SQLException{RimborsoService.validaMotivazione(m);call();actor=a;motive=m;assertEquals(3,b);var r=new RimborsoResponse();r.setIdRimborso(9);r.setIdPagamento(8);r.setIdPrenotazione(b);r.setStato(StatoRimborso.RICHIESTO);r.setImporto(new BigDecimal("80.00"));r.setMotivazione(m);r.setRichiestoIl(LocalDateTime.of(2030,1,1,12,0));return r;}
        @Override public List<RimborsoResponse> getRimborsiPaziente(Integer a)throws SQLException{call();actor=a;return List.of();}
        @Override public List<RichiestaRimborsoDeskResponse> getRichieste()throws SQLException{call();return List.of();}
        @Override public List<RichiestaRimborsoDeskResponse> getStoricoDesk(Integer a,String stato)throws SQLException {
            if(stato!=null&&!java.util.Set.of("RICHIESTO","APPROVATO","RESPINTO","TUTTI").contains(stato))throw new IllegalArgumentException();
            call();actor=a;filtro=stato;return List.of();
        }
        DecisioneRimborsoResponse decide(Integer r,Integer a,boolean p)throws SQLException{call();actor=a;approve=p;var d=new DecisioneRimborsoResponse();d.setIdRimborso(r);d.setStato(p?StatoRimborso.APPROVATO:StatoRimborso.RESPINTO);d.setIdOperatore(a);d.setVerificatoIl(LocalDateTime.of(2030,1,1,12,0));return d;}
        @Override public DecisioneRimborsoResponse approva(Integer r,Integer a)throws SQLException{return decide(r,a,true);}
        @Override public DecisioneRimborsoResponse respingi(Integer r,Integer a)throws SQLException{return decide(r,a,false);}
    }
    Fake fake=new Fake();ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    RimborsoController controller=new RimborsoController(fake,mapper);
    PrenotazioneController bookingController=new PrenotazioneController(new PrenotazioneService(new PrenotazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()))),mapper,null,fake);
    AuthenticatedTestExchange request(String method,String path,String body,Integer actor)throws Exception{var e=new AuthenticatedTestExchange(method,path,body,actor);if(path.startsWith("/api/prenotazioni"))bookingController.handle(e);else controller.handle(e);assertTrue(e.exchange.closed);assertEquals("application/json; charset=UTF-8",e.getResponseHeaders().getFirst("Content-Type"));return e;}
    @Test void safeCreationDtoAndIdentityFromPrincipal()throws Exception{var e=request("POST","/api/prenotazioni/3/rimborso","{\"motivazione\":\"  Visita 😀 non erogata  \"}",7);assertEquals(201,e.exchange.status);var j=mapper.readTree(e.exchange.body());assertEquals(8,j.size());assertEquals(9,j.get("idRimborso").intValue());assertEquals("RICHIESTO",j.get("stato").textValue());assertTrue(j.get("verificatoIl").isNull());assertEquals("2030-01-01T12:00:00",j.get("richiestoIl").textValue());assertEquals("  Visita 😀 non erogata  ",fake.motive);assertEquals(7,fake.actor);for(String forbidden:List.of("passwordHash","email","idPaziente","providerRefundId","rimborsatoIl"))assertFalse(j.has(forbidden));}
    @ParameterizedTest @ValueSource(strings={"","{","null","[]","{}","{\"motivazione\":null}","{\"motivazione\":7}","{\"motivazione\":\" \"}","{\"motivazione\":\"x\",\"idPaziente\":99}","{\"motivazione\":\"x\",\"motivazione\":\"y\"}","{\"motivazione\":\"x\"} {}","{\"motivazione\":\"x\\u0000\"}"})void rejectsInvalidCreation(String b)throws Exception{assertEquals(400,request("POST","/api/prenotazioni/3/rimborso",b,7).exchange.status);assertEquals(0,fake.calls);}
    @Test void byteLimitAndUnicodeLimit()throws Exception{assertEquals(400,request("POST","/api/prenotazioni/3/rimborso"," ".repeat(8193),7).exchange.status);assertEquals(400,request("POST","/api/prenotazioni/3/rimborso",mapper.writeValueAsString(java.util.Map.of("motivazione","😀".repeat(501))),7).exchange.status);assertEquals(201,request("POST","/api/prenotazioni/3/rimborso",mapper.writeValueAsString(java.util.Map.of("motivazione","😀".repeat(500))),7).exchange.status);}
    @ParameterizedTest @ValueSource(strings={"approva","respingi"})void decisionUsesEmptyBodyAndPrincipal(String action)throws Exception{var e=request("POST","/api/rimborsi/9/"+action,"{}",7);assertEquals(200,e.exchange.status);var j=mapper.readTree(e.exchange.body());assertEquals(4,j.size());assertEquals(7,j.get("idOperatore").intValue());assertEquals(action.equals("approva")?"APPROVATO":"RESPINTO",j.get("stato").textValue());assertEquals(action.equals("approva"),fake.approve);}
    @ParameterizedTest @ValueSource(strings={"","null","[]","{","{\"idOperatore\":7}","{\"x\":1,\"x\":2}","{} {}"})void decisionRejectsOtherBodies(String b)throws Exception{assertEquals(400,request("POST","/api/rimborsi/9/approva",b,7).exchange.status);assertEquals(0,fake.calls);}
    @Test void listsEmptyAndRejectQueries()throws Exception{for(String p:List.of("/api/rimborsi/paziente","/api/rimborsi/richieste")){var e=request("GET",p,"",7);assertEquals(200,e.exchange.status);assertEquals("[]",e.exchange.body());assertEquals(400,request("GET",p+"?idPaziente=7","",7).exchange.status);}assertEquals(2,fake.calls);}
    @Test void missingSession()throws Exception{assertEquals(401,request("POST","/api/prenotazioni/3/rimborso","{\"motivazione\":\"x\"}",null).exchange.status);assertEquals(401,request("GET","/api/rimborsi/paziente","",null).exchange.status);assertEquals(0,fake.calls);}
    @Test void wrongMethodsAndExactRoutes()throws Exception{for(String p:List.of("/api/prenotazioni/3/rimborso","/api/rimborsi/9/approva","/api/rimborsi/9/respingi")){var e=request("GET",p,"",7);assertEquals(405,e.exchange.status);assertEquals("POST",e.getResponseHeaders().getFirst("Allow"));assertEquals(404,request("POST",p+"/","{}",7).exchange.status);}var e=request("POST","/api/rimborsi/paziente","{}",7);assertEquals(405,e.exchange.status);assertEquals("GET",e.getResponseHeaders().getFirst("Allow"));assertEquals(0,fake.calls);}
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","2147483648","%33"})void invalidIds(String id)throws Exception{assertEquals(400,request("POST","/api/rimborsi/"+id+"/approva","{}",7).exchange.status);assertEquals(0,fake.calls);}
    @Test void errorMapping()throws Exception{Throwable[] errors={new SQLException("SECRET"),new RisorsaNonTrovataException("missing"),new OperazioneNonAutorizzataException("role"),new ConflittoPrenotazioneException("conflict")};int[] status={500,404,403,409};for(int i=0;i<errors.length;i++){fake.error=errors[i];var e=request("POST","/api/rimborsi/9/approva","{}",7);assertEquals(status[i],e.exchange.status);assertFalse(e.exchange.body().contains("SECRET"));}}
    @Test void serializationErrorIs500()throws Exception{ObjectMapper broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value)throws com.fasterxml.jackson.core.JsonProcessingException{throw new com.fasterxml.jackson.core.JsonProcessingException("SECRET"){};}};controller=new RimborsoController(fake,broken);var e=request("GET","/api/rimborsi/paziente","",7);assertEquals(500,e.exchange.status);assertFalse(e.exchange.body().contains("SECRET"));}
    @Test void bodyLimitIsInclusiveAndPreservesPermittedWhitespace()throws Exception {
        String json="{\"motivazione\":\"x\"}";String atLimit=json+" ".repeat(8192-json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(201,request("POST","/api/prenotazioni/3/rimborso",atLimit,7).exchange.status);
        assertEquals(400,request("POST","/api/prenotazioni/3/rimborso",atLimit+" ",7).exchange.status);assertEquals(1,fake.calls);
    }
    @Test void allDecisionQueriesAndActorFieldsAreRejected()throws Exception {
        for(String action:List.of("approva","respingi")) {
            assertEquals(400,request("POST","/api/rimborsi/9/"+action+"?idOperatore=7","{}",7).exchange.status);
            assertEquals(400,request("POST","/api/rimborsi/9/"+action,"{\"idOperatore\":7}",7).exchange.status);
        }
        assertEquals(0,fake.calls);
    }

    @Test void storicoUsesPrincipalAndValidatedFilters()throws Exception {
        for(String filtro:List.of("RICHIESTO","APPROVATO","RESPINTO","TUTTI")) {
            assertEquals(200,request("GET","/api/rimborsi/desk?stato="+filtro,"",7).exchange.status);
            assertEquals(filtro,fake.filtro);assertEquals(7,fake.actor);
        }
        assertEquals(200,request("GET","/api/rimborsi/desk","",7).exchange.status);
        assertEquals(401,request("GET","/api/rimborsi/desk","",null).exchange.status);
        assertEquals(405,request("POST","/api/rimborsi/desk","{}",7).exchange.status);
        assertEquals(404,request("GET","/api/rimborsi/desk/","",7).exchange.status);
    }
    @ParameterizedTest @ValueSource(strings={"stato=", "stato=NO", "stato=approvato", "idOperatore=7", "stato=TUTTI&stato=APPROVATO", "stato=TUTTI&%73tato=APPROVATO", "stato=%FF", "stato=TUTTI&x=1"})
    void storicoRejectsAmbiguousQueries(String query)throws Exception {
        assertEquals(400,request("GET","/api/rimborsi/desk?"+query,"",7).exchange.status);assertEquals(0,fake.calls);
    }

}
