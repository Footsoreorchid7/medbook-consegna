package it.medbook.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import it.medbook.config.*;
import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.repository.RegistrazioneRepository;
import it.medbook.security.*;
import it.medbook.service.RegistrazioneService;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.sql.SQLException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneControllerTest {
    private static final String ORIGIN="http://localhost:8080";
    private static final String JSON="{\"nome\":\"Fixture\",\"cognome\":\"Sintetico\",\"email\":\"fixture@example.invalid\",\"password\":\"Solo-test\",\"codiceFiscale\":\"TSTTST90A01A000Q\",\"dataNascita\":\"1990-01-01\"}";
    private final Fake service=new Fake();private final ObjectMapper mapper=new ObjectMapper();
    // I test di parsing fanno più richieste; il limite reale viene verificato separatamente.
    private RegistrazioneController controller=new RegistrazioneController(service,new LoginThrottle(new MutableClock(),10,100),new AuthConfig("local-http",ORIGIN),mapper);
    private TestHttpExchange request(String body){var result=new TestHttpExchange("POST","/api/auth/register",body);result.requestHeaders.set("Origin",ORIGIN);return result;}
    @Test void registrationIsPublicSafeAndDoesNotAutoLogin() throws Exception {
        var result=request(JSON);controller.handle(result);assertEquals(201,result.status);assertEquals(1,service.calls);assertTrue(result.closed);
        assertEquals(Set.of("message"),mapper.readTree(result.body()).properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals("TSTTST90A01A000Q",service.request.getCodiceFiscale());assertEquals("Fixture",service.request.getNome());
        assertNull(result.responseHeaders.getFirst("Set-Cookie"));assertNull(result.responseHeaders.getFirst("X-CSRF-Token"));
        for(String sensitive:List.of("TSTTST","password","Solo-test","fixture@example.invalid","idUtente")){assertFalse(result.body().contains(sensitive));}
        for(char c:service.request.getPassword()){assertEquals('\0',c);}
        assertEquals("no-store",result.responseHeaders.getFirst("Cache-Control"));assertEquals("application/json; charset=UTF-8",result.responseHeaders.getFirst("Content-Type"));
    }
    @Test void allRequiredFieldsAreStrictlyRequired() throws Exception {
        for(String field:List.of("nome","cognome","email","password","codiceFiscale","dataNascita")){
            var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(JSON);json.remove(field);var result=request(json.toString());controller.handle(result);assertEquals(400,result.status,field);
            json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(JSON);json.put(field,123);result=request(json.toString());controller.handle(result);assertEquals(400,result.status,field);
        }assertEquals(0,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"","null","[]","{}","{","{} {}"}) void malformedJsonIs400(String body) throws Exception {var result=request(body);controller.handle(result);assertEquals(400,result.status);assertEquals(0,service.calls);}
    @Test void rejectsUnknownFieldsPrivilegesDuplicatesAndTrailingContent() throws Exception {
        for(String body:List.of(JSON+" {}",JSON.replace("}",",\"ruoloGestionale\":\"AMMINISTRATORE\"}"),JSON.replace("}",",\"medico\":true}"),
            JSON.replace("}",",\"email\":\"other@example.invalid\"}"),JSON.replace("}",",\"\\u0065mail\":\"other@example.invalid\"}"),JSON.replace("1990-01-01","1990-02-30"))){
            var result=request(body);controller.handle(result);assertEquals(400,result.status);
        }assertEquals(0,service.calls);
    }
    @Test void acceptsOptionalPhoneOnlyAsStringOrNull() throws Exception {
        for(String phone:List.of("null","\"+39 000\"")){var result=request(JSON.replace("}",",\"telefono\":"+phone+"}"));controller.handle(result);assertEquals(201,result.status);}
        var result=request(JSON.replace("}",",\"telefono\":123}"));controller.handle(result);assertEquals(400,result.status);assertEquals(2,service.calls);
    }
    @Test void enforcesOriginAndJsonContentTypeBeforeService() throws Exception {
        for(String origin:new String[]{null,"null","https://foreign.example.invalid",ORIGIN+"/"}){
            var result=request(JSON);if(origin==null){result.requestHeaders.remove("Origin");}else{result.requestHeaders.set("Origin",origin);}
            controller.handle(result);assertEquals(403,result.status);
        }
        var duplicate=request(JSON);duplicate.requestHeaders.add("Origin",ORIGIN);controller.handle(duplicate);assertEquals(403,duplicate.status);
        for(String type:List.of("text/plain","application/x-www-form-urlencoded")){var result=request(JSON);result.requestHeaders.set("Content-Type",type);controller.handle(result);assertEquals(400,result.status);}
        assertEquals(0,service.calls);
    }
    @Test void enforcesBodyLimit(){var result=request("x".repeat(8193));assertDoesNotThrow(()->controller.handle(result));assertEquals(400,result.status);assertEquals(0,service.calls);}
    @Test void exactRoutingRejectsSubpathsEncodedPathsQueriesAndMethods() throws Exception {
        for(String path:List.of("/api/auth/register/","/api/auth/register/extra","/api/auth/%72egister","/api/auth/register%2fextra")){
            var result=new TestHttpExchange("POST",path,JSON);result.requestHeaders.set("Origin",ORIGIN);controller.handle(result);assertEquals(404,result.status);
        }
        var query=new TestHttpExchange("POST","/api/auth/register?codiceFiscale=NON-AMMESSO",JSON);controller.handle(query);assertEquals(400,query.status);
        for(String method:List.of("GET","PUT","DELETE","HEAD","OPTIONS")){var result=new TestHttpExchange(method,"/api/auth/register",JSON);controller.handle(result);assertEquals(405,result.status);assertEquals("POST",result.responseHeaders.getFirst("Allow"));}
        assertEquals(0,service.calls);
    }
    @Test void throttlingDoesNotUseForwardedHeadersOrLoginCounter() throws Exception {
        controller=new RegistrazioneController(service,new LoginThrottle(new MutableClock(),10,1),new AuthConfig("local-http",ORIGIN),mapper);
        var first=request(JSON);controller.handle(first);assertEquals(201,first.status);
        var second=request(JSON);second.requestHeaders.set("X-Forwarded-For","192.0.2.1");controller.handle(second);assertEquals(429,second.status);assertEquals("300",second.responseHeaders.getFirst("Retry-After"));assertEquals(1,service.calls);
    }
    @Test void mapsValidationConflictAndSqlErrorsSafely() throws Exception {
        service.failure=new IllegalArgumentException("Non ripetere dati sensibili");var result=request(JSON);controller.handle(result);assertEquals(400,result.status);
        service.failure=new RegistrazioneDuplicataException(new SQLException("email/codice fiscale riservato"));result=request(JSON);controller.handle(result);assertEquals(409,result.status);
        service.sql=new SQLException("Query SQL riservata");service.failure=null;result=request(JSON);controller.handle(result);assertEquals(500,result.status);
        assertEquals("{\"error\":\"Errore interno del server.\"}",result.body());
    }
    @Test void serializationFailureReturnsGeneric500WithoutRetry() throws Exception {
        ObjectMapper broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {throw JsonMappingException.fromUnexpectedIOE(new IOException("Riservato"));}};
        controller=new RegistrazioneController(service,new LoginThrottle(new MutableClock(),10,10),new AuthConfig("local-http",ORIGIN),broken);
        var result=request(JSON);controller.handle(result);assertEquals(500,result.status);assertEquals("{\"error\":\"Errore interno del server.\"}",result.body());assertEquals(1,service.calls);
    }
    @Test void realHttpServerRoutesRegisterSeparatelyAndPreservesUc02() throws Exception {
        var clock=new MutableClock();var config=new AuthConfig("local-http",ORIGIN);var sessions=new SessionStore(clock,10);
        var auth=new AutenticazioneService(new FakeUtenteRepository(),new PasswordHasher(),sessions);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/auth",new AutenticazioneController(auth,sessions,new LoginThrottle(clock,10,1),config,mapper));
        server.createContext("/api/auth/register",new RegistrazioneController(service,new LoginThrottle(clock,10,1),config,mapper));server.start();
        try(var client=HttpClient.newHttpClient()){
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            for(String path:List.of("/api/auth/register/","/api/auth/register/extra","/api/auth/registerX","/api/auth/%72egister")){
                var result=http(client,base,path,JSON);assertEquals(404,result.statusCode(),path);
            }
            var registered=http(client,base,"/api/auth/register",JSON);assertEquals(201,registered.statusCode());assertTrue(registered.headers().allValues("Set-Cookie").isEmpty());
            assertEquals(429,http(client,base,"/api/auth/register",JSON).statusCode());
            assertEquals(401,http(client,base,"/api/auth/login","{\"email\":\"missing@example.invalid\",\"password\":\"Solo-test\"}").statusCode());
            var me=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/me")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,me.statusCode());assertEquals(0,sessions.size());assertEquals(1,service.calls);
        }finally{server.stop(0);}
    }
    private HttpResponse<String> http(HttpClient client,String base,String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).header("Origin",ORIGIN)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static class Fake extends RegistrazioneService {
        int calls;RegistrazionePazienteRequest request;RuntimeException failure;SQLException sql;
        Fake(){super(new RegistrazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig())),new PasswordHasher());}
        @Override public void registra(RegistrazionePazienteRequest input) throws SQLException {calls++;request=input;if(failure!=null){throw failure;}if(sql!=null){throw sql;}}
    }
}
