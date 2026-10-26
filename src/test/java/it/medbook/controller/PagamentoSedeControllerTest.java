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
class PagamentoSedeControllerTest {
    static class Fake extends PagamentoService {
        int calls;Throwable error;
        Fake(){super(new PagamentoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())));}
        @Override public PagamentoResponse registraPagamentoInSede(Integer p,Integer o,ModalitaPagamento m,BigDecimal a)throws SQLException{
            calls++;if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;
            assertEquals(3,p);assertEquals(7,o);assertEquals(ModalitaPagamento.DESK_POS,m);assertEquals(0,new BigDecimal("80.00").compareTo(a));
            var r=new PagamentoResponse();r.setIdPagamento(42);r.setIdPrenotazione(p);r.setIdOperatore(o);r.setModalita(m);r.setStato(StatoPagamento.PAGATO);r.setImporto(a);r.setPagatoIl(LocalDateTime.of(2026,10,10,10,30));return r;
        }
    }
    final Fake service=new Fake();final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final PrenotazioneController controller=new PrenotazioneController(null,mapper,service);
    static final String BODY="{\"idOperatore\":7,\"modalita\":\"DESK_POS\",\"importoAtteso\":80.00}";
    TestHttpExchange invoke(String method,String path,String body)throws Exception{var x=new TestHttpExchange(method,path,body);controller.handle(x);assertTrue(x.closed);assertEquals("application/json; charset=UTF-8",x.responseHeaders.getFirst("Content-Type"));return x;}
    @Test void createdSafeDto()throws Exception{var x=invoke("POST","/api/prenotazioni/3/pagamento-sede",BODY);assertEquals(201,x.status);var j=mapper.readTree(x.body());assertEquals(42,j.get("idPagamento").intValue());assertEquals("PAGATO",j.get("stato").textValue());assertEquals("2026-10-10T10:30:00",j.get("pagatoIl").textValue());for(String forbidden:new String[]{"passwordHash","email","paziente","registratoDa"})assertFalse(j.has(forbidden));}
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{","{\"idOperatore\":7,\"modalita\":\"ONLINE\",\"importoAtteso\":80}","{\"idOperatore\":7,\"modalita\":\"DESK_POS\",\"importoAtteso\":\"80\"}","{\"idOperatore\":7,\"idOperatore\":7,\"modalita\":\"DESK_POS\",\"importoAtteso\":80}","{\"idOperatore\":7,\"modalita\":\"DESK_POS\",\"importoAtteso\":80,\"stato\":\"PAGATO\"}"})void invalidBody(String body)throws Exception{assertEquals(400,invoke("POST","/api/prenotazioni/3/pagamento-sede",body).status);assertEquals(0,service.calls);}
    @Test void trailingOversizeAndQuery()throws Exception{for(String b:new String[]{BODY+" {}"," ".repeat(8193)})assertEquals(400,invoke("POST","/api/prenotazioni/3/pagamento-sede",b).status);assertEquals(400,invoke("POST","/api/prenotazioni/3/pagamento-sede?x=1",BODY).status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","2147483648"})void invalidPathId(String id)throws Exception{assertEquals(400,invoke("POST","/api/prenotazioni/"+id+"/pagamento-sede",BODY).status);assertEquals(0,service.calls);}
    @Test void wrongMethodAndSuffix()throws Exception{var x=invoke("GET","/api/prenotazioni/3/pagamento-sede","");assertEquals(405,x.status);assertEquals("POST",x.responseHeaders.getFirst("Allow"));assertEquals(404,invoke("POST","/api/prenotazioni/3/pagamento-sede/",BODY).status);assertEquals(0,service.calls);}
    @Test void errorsAndNoTechnicalLeak()throws Exception{Throwable[] errors={new RisorsaNonTrovataException("missing"),new OperazioneNonAutorizzataException("role"),new ConflittoPrenotazioneException("conflict"),new SQLException("SECRET synthetic SQL")};int[] statuses={404,403,409,500};for(int i=0;i<errors.length;i++){service.error=errors[i];var x=invoke("POST","/api/prenotazioni/3/pagamento-sede",BODY);assertEquals(statuses[i],x.status);assertFalse(x.body().contains("SECRET"));}}
    @Test void realHttpRouteWorksWithoutPersistentServer() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/prenotazioni",controller);server.start();
        try {
            var uri=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/prenotazioni/3/pagamento-sede");
            var request=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(BODY)).build();
            var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(201,response.statusCode());assertEquals("PAGATO",mapper.readTree(response.body()).get("stato").textValue());
        } finally { server.stop(0); }
    }
}
