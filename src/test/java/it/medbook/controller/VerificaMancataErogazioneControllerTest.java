package it.medbook.controller;
import it.medbook.service.*;
import it.medbook.dto.*;
import it.medbook.model.*;
import it.medbook.config.*;
import it.medbook.repository.*;
import it.medbook.exception.*;
import it.medbook.support.TestHttpExchange;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
class VerificaMancataErogazioneControllerTest {
    static class Fake extends PrenotazioneService {
        int calls;Throwable error;StatoPrenotazione expectedOutcome=StatoPrenotazione.NON_EROGATA;
        Fake(){super(new PrenotazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())));}
        @Override public VerificaMancataErogazioneResponse verificaMancataErogazione(Integer p,Integer o,StatoPrenotazione expected,StatoPrenotazione outcome)throws SQLException {
            calls++;if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;
            assertEquals(3,p);assertEquals(7,o);assertEquals(StatoPrenotazione.CONFERMATA,expected);assertEquals(expectedOutcome,outcome);
            var r=new VerificaMancataErogazioneResponse();r.setIdPrenotazione(p);r.setIdOperatore(o);r.setStato(outcome);r.setDataOraGestione(LocalDateTime.of(2030,1,1,12,0));return r;
        }
    }
    final Fake service=new Fake();final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final PrenotazioneController controller=new PrenotazioneController(service,mapper);
    static final String BODY="{\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\",\"esito\":\"NON_EROGATA\"}";
    TestHttpExchange invoke(String method,String path,String body)throws Exception{var x=new TestHttpExchange(method,path,body);controller.handle(x);assertTrue(x.closed);assertEquals("application/json; charset=UTF-8",x.responseHeaders.getFirst("Content-Type"));return x;}
    @Test void createdSafeDto()throws Exception{var x=invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",BODY);assertEquals(200,x.status);var j=mapper.readTree(x.body());assertEquals(4,j.size());assertEquals(3,j.get("idPrenotazione").intValue());assertEquals(7,j.get("idOperatore").intValue());assertEquals("NON_EROGATA",j.get("stato").textValue());assertEquals("2030-01-01T12:00:00",j.get("dataOraGestione").textValue());for(String forbidden:new String[]{"passwordHash","email","paziente","rimborsoDovuto","importo"})assertFalse(j.has(forbidden));}
    @Test void patientAbsenceReturnsSafeDtoAndPendingFlagConflict() throws Exception {
        service.expectedOutcome=StatoPrenotazione.PAZIENTE_ASSENTE;
        String body=BODY.replace("NON_EROGATA","PAZIENTE_ASSENTE");
        var success=invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",body);
        assertEquals(200,success.status);var json=mapper.readTree(success.body());
        assertEquals(4,json.size());assertEquals("PAZIENTE_ASSENTE",json.get("stato").textValue());
        assertEquals(3,json.get("idPrenotazione").intValue());assertEquals(7,json.get("idOperatore").intValue());
        assertEquals("2030-01-01T12:00:00",json.get("dataOraGestione").textValue());assertEquals(1,service.calls);
        service.error=new ConflittoPrenotazioneException("Indisponibilità ancora da gestire.");
        assertEquals(409,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",body).status);
        assertEquals(2,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"", "{}","null","[]","{",
        "{\"idOperatore\":7,\"statoAtteso\":\"RICHIESTA\",\"esito\":\"NON_EROGATA\"}",
        "{\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\",\"esito\":\"EFFETTUATA\"}",
        "{\"idOperatore\":7,\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\",\"esito\":\"NON_EROGATA\"}",
        "{\"idOperatore\":\"7\",\"statoAtteso\":\"CONFERMATA\",\"esito\":\"NON_EROGATA\"}",
        "{\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\",\"esito\":null}"})void invalidBody(String body)throws Exception{assertEquals(400,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",body).status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"indisponibilita_segnalata","timestamp","importo","statoPagamento","autorizzazioneRimborso"})
    void rejectsExtraFields(String field)throws Exception{String body=BODY.substring(0,BODY.length()-1)+",\""+field+"\":true}";assertEquals(400,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",body).status);assertEquals(0,service.calls);}
    @Test void trailingOversizeAndQuery()throws Exception{for(String b:new String[]{BODY+" {}"," ".repeat(8193)})assertEquals(400,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",b).status);assertEquals(400,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione?x=1",BODY).status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","2147483648"})void invalidPathId(String id)throws Exception{assertEquals(400,invoke("POST","/api/prenotazioni/"+id+"/verifica-mancata-erogazione",BODY).status);assertEquals(0,service.calls);}
    @Test void wrongMethodAndSuffix()throws Exception{var x=invoke("GET","/api/prenotazioni/3/verifica-mancata-erogazione","");assertEquals(405,x.status);assertEquals("POST",x.responseHeaders.getFirst("Allow"));assertEquals(404,invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione/",BODY).status);assertEquals(0,service.calls);}
    @Test void errorsAndNoTechnicalLeak()throws Exception{Throwable[] errors={new RisorsaNonTrovataException("missing"),new OperazioneNonAutorizzataException("role"),new ConflittoPrenotazioneException("conflict"),new SQLException("SECRET synthetic SQL")};int[] statuses={404,403,409,500};for(int i=0;i<errors.length;i++){service.error=errors[i];var x=invoke("POST","/api/prenotazioni/3/verifica-mancata-erogazione",BODY);assertEquals(statuses[i],x.status);assertFalse(x.body().contains("SECRET"));}}
    @Test void realHttpRouteWorksWithoutPersistentServer() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/prenotazioni",controller);server.start();
        try {
            var uri=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/prenotazioni/3/verifica-mancata-erogazione");
            var request=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(BODY)).build();
            var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals("NON_EROGATA",mapper.readTree(response.body()).get("stato").textValue());
        } finally { server.stop(0); }
    }
}
