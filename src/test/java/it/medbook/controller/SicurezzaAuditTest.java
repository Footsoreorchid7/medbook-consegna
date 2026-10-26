package it.medbook.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.model.*;
import it.medbook.repository.*;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Audit riproducibile: errori di output e percorsi ostili, senza database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SicurezzaAuditTest {
    private final ObjectMapper normal=new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthConfig config=new AuthConfig("local-http","http://localhost:8080");
    private final MutableClock clock=new MutableClock();
    private final SessionStore sessions=new SessionStore(clock,1000);
    private final FakeUtenteRepository users=new FakeUtenteRepository();
    private final ConnectionFactory noDatabase=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
        @Override public Connection getConnection(){throw new AssertionError("Il percorso ostile non deve accedere al database.");}
    };
    private AutenticazioneService auth;
    private String hash;
    @BeforeAll void setup(){
        users.identity=new UtenteAutenticatoResponse(7,"Fixture","Audit",true,true,RuoloGestionale.OPERATORE_DESK);
        var hasher=new PasswordHasher();hash=hasher.hash("Password-sintetica-audit".toCharArray());
        users.account=new UtenteRepository.Credenziali(users.identity,hash);
        auth=new AutenticazioneService(users,hasher,sessions);
    }
    private static final class FailingOutputMapper extends ObjectMapper {
        FailingOutputMapper(){registerModule(new JavaTimeModule());}
        private void check(Object value) throws JsonProcessingException {
            if(!(value instanceof Map)){throw JsonMappingException.fromUnexpectedIOE(new IOException("Dettaglio interno riservato"));}
        }
        @Override public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {check(value);return super.writeValueAsBytes(value);}
        @Override public String writeValueAsString(Object value) throws JsonProcessingException {check(value);return super.writeValueAsString(value);}
    }
    private TestHttpExchange request(String method,String path,String body){
        var result=new TestHttpExchange(method,path,body);var session=sessions.create(7, 0L);
        result.requestHeaders.set("Cookie",config.cookieName()+"="+session.id());
        result.requestHeaders.set("X-CSRF-Token",session.csrfToken());
        if(!method.equals("GET")){result.requestHeaders.set("Origin",config.origin());}return result;
    }
    private AutorizzazioneHandler guard(HttpHandler delegate,ObjectMapper mapper){
        return new AutorizzazioneHandler(delegate,auth,sessions,config,mapper);
    }
    private void internalError(TestHttpExchange result){
        assertEquals(500,result.status);assertEquals("{\"error\":\"Errore interno del server.\"}",result.body());
        assertFalse(result.body().contains("Dettaglio"));assertFalse(result.body().contains(hash));assertTrue(result.closed);
    }
    @Test void meSerializationFailureIs500Not400() throws Exception {
        var mapper=new FailingOutputMapper();var result=request("GET","/api/auth/me","");
        new AutenticazioneController(auth,sessions,new LoginThrottle(clock,100,10),config,mapper).handle(result);
        internalError(result);assertNull(result.responseHeaders.getFirst("X-CSRF-Token"));
    }
    @Test void loginSerializationFailureRevokesNewSessionAndDoesNotPublishSecrets() throws Exception {
        int before=sessions.size();var result=new TestHttpExchange("POST","/api/auth/login",
                "{\"email\":\"audit@example.invalid\",\"password\":\"Password-sintetica-audit\"}");
        result.requestHeaders.set("Origin",config.origin());
        new AutenticazioneController(auth,sessions,new LoginThrottle(clock,100,10),config,new FailingOutputMapper()).handle(result);
        internalError(result);assertEquals(before,sessions.size());
        assertNull(result.responseHeaders.getFirst("Set-Cookie"));assertNull(result.responseHeaders.getFirst("X-CSRF-Token"));
    }
    @Test void publicCatalogSerializationFailureIs500Not400() throws Exception {
        var mapper=new FailingOutputMapper();
        var service=new PrestazioneService(new PrestazioneRepository(noDatabase)){
            @Override public List<Prestazione> getAllPrestazioni(){return List.of(new Prestazione());}
        };
        var result=request("GET","/api/prestazioni","");guard(new PrestazioneController(service,mapper),mapper).handle(result);internalError(result);
    }
    private Disponibilita slot(){
        var user=new Utente();user.setIdUtente(7);var doctor=new Medico();doctor.setUtente(user);
        var result=new Disponibilita();result.setIdDisponibilita(9);result.setMedico(doctor);
        result.setDataOraInizio(LocalDateTime.of(2030,1,1,9,0));result.setDataOraFine(result.getDataOraInizio().plusHours(1));return result;
    }
    @Test void timeUpdateOutputFailureDoesNotBecomeClientErrorOrRepeatOperation() throws Exception {
        var mapper=new FailingOutputMapper();var calls=new AtomicInteger();
        var service=new DisponibilitaService(new DisponibilitaRepository(noDatabase)){
            @Override public Disponibilita modificaOrario(Integer id,Integer medico,LocalDateTime start,LocalDateTime end){calls.incrementAndGet();return slot();}
        };
        var result=request("PUT","/api/disponibilita/9/orario","{\"idMedico\":7,\"dataOraInizio\":\"2030-01-01T09:00:00\",\"dataOraFine\":\"2030-01-01T10:00:00\"}");
        guard(new DisponibilitaController(service,mapper),mapper).handle(result);internalError(result);assertEquals(1,calls.get());
    }
    @Test void reportOutputFailureDoesNotBecomeClientErrorOrRepeatOperation() throws Exception {
        var mapper=new FailingOutputMapper();var calls=new AtomicInteger();
        var service=new PrenotazioneService(new PrenotazioneRepository(noDatabase)){
            @Override public SegnalazioneIndisponibilitaResponse segnalaIndisponibilita(Integer medico,List<Integer> ids){
                calls.incrementAndGet();var result=new SegnalazioneIndisponibilitaResponse();result.setIdPrenotazioni(ids);result.setIndisponibilitaSegnalata(true);return result;
            }
        };
        var result=request("POST","/api/prenotazioni/segnala-indisponibilita","{\"medicoId\":7,\"idPrenotazioni\":[9]}");
        guard(new PrenotazioneController(service,mapper),mapper).handle(result);internalError(result);assertEquals(1,calls.get());
    }
    @Test void delegateJsonOutputExceptionIsInternalError() throws Exception {
        var result=request("GET","/api/prestazioni","");
        guard(exchange -> {throw JsonMappingException.fromUnexpectedIOE(new IOException("Dettaglio interno riservato"));},normal).handle(result);
        internalError(result);
    }
    private ObjectMapper allOutputFails(){
        return new ObjectMapper(){
            @Override public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                throw JsonMappingException.fromUnexpectedIOE(new IOException("Dettaglio interno riservato"));
            }
        };
    }
    @Test void securityErrorSerializationFailureStillReturnsGeneric500() throws Exception {
        var result=new TestHttpExchange("GET","/api/prenotazioni/richieste","");
        guard(exchange -> fail("Nessuna delega anonima"),allOutputFails()).handle(result);internalError(result);
    }
    @Test void logoutOutputFailureDoesNotUndoRevocation() throws Exception {
        var current=sessions.create(7, 0L);var result=new TestHttpExchange("POST","/api/auth/logout","");
        result.requestHeaders.set("Cookie",config.cookieName()+"="+current.id());
        result.requestHeaders.set("Origin",config.origin());result.requestHeaders.set("X-CSRF-Token",current.csrfToken());
        new AutenticazioneController(auth,sessions,new LoginThrottle(clock,100,10),config,allOutputFails()).handle(result);
        internalError(result);assertTrue(sessions.find(current.id()).isEmpty());
    }
    @Test void realHttpServerReturns500WhenPublicResponseSerializationFails() throws Exception {
        var mapper=new FailingOutputMapper();
        var service=new PrestazioneService(new PrestazioneRepository(noDatabase)){
            @Override public List<Prestazione> getAllPrestazioni(){return List.of(new Prestazione());}
        };
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/prestazioni",guard(new PrestazioneController(service,mapper),mapper));server.start();
        try(var client=HttpClient.newHttpClient()){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/prestazioni"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(500,response.statusCode());assertEquals("{\"error\":\"Errore interno del server.\"}",response.body());
        }finally{server.stop(0);}
    }
    @Test void publicAvailabilityOnlyExposesApprovedFields() throws Exception {
        var value=slot();value.getMedico().getUtente().setNome("Medico");value.getMedico().getUtente().setCognome("Fixture");
        value.getMedico().getUtente().setEmail("non-pubblica@example.invalid");value.getMedico().getUtente().setPasswordHash("hash-sintetico-non-pubblico");
        var prestazione=new Prestazione();prestazione.setIdPrestazione(3);prestazione.setNome("Visita");prestazione.setDurata(60);
        prestazione.setPrezzo(new java.math.BigDecimal("70.00"));value.setPrestazione(prestazione);
        var stanza=new Stanza();stanza.setIdStanza(4);stanza.setNome("Riservata");value.setStanza(stanza);
        var service=new DisponibilitaService(new DisponibilitaRepository(noDatabase)){
            @Override public List<Disponibilita> getDisponibilitaPrenotabili(Integer medico,Integer prestazione,java.time.LocalDate data){return List.of(value);}
        };
        var result=new TestHttpExchange("GET","/api/disponibilita/prenotabili","");
        int before=users.idCalls;guard(new DisponibilitaController(service,normal),normal).handle(result);assertEquals(before,users.idCalls);
        assertEquals(200,result.status);var json=normal.readTree(result.body()).get(0);var fields=new HashSet<String>();json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("idDisponibilita","dataOraInizio","dataOraFine","idMedico","nomeMedico","cognomeMedico",
                "idPrestazione","nomePrestazione","durataPrestazione","prezzoPrestazione"),fields);
        for(String secret:List.of("passwordHash","email","utente","stanza","hash-sintetico-non-pubblico","non-pubblica@example.invalid")){assertFalse(result.body().contains(secret));}
    }
    @Test void realHttpServerRejectsEncodedPathsAndSubpaths() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var controller=new PrenotazioneController(new PrenotazioneService(new PrenotazioneRepository(noDatabase)),normal);
        server.createContext("/api/prenotazioni",guard(controller,normal));
        server.createContext("/api/prestazioni",guard(new PrestazioneController(new PrestazioneService(new PrestazioneRepository(noDatabase)),normal),normal));
        var authController=new AutenticazioneController(auth,sessions,new LoginThrottle(clock,100,10),config,normal);
        server.createContext("/api/auth",authController);server.start();
        try(var client=HttpClient.newHttpClient()){
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            for(String path:List.of("/api/prestazioni/","/api/%70restazioni","/api/prestazioni%2fextra",
                    "/api/prenotazioni/richieste/","/api/prenotazioni/%72ichieste","/api/prenotazioni/7/conferma/extra",
                    "/api/auth/me/","/api/auth/%6de","/api/auth/me%2fextra")){
                var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(404,response.statusCode(),path);
            }
            for(String method:List.of("HEAD","OPTIONS","TRACE","PATCH","get")){
                var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/prenotazioni/richieste"))
                        .timeout(Duration.ofSeconds(5)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(405,response.statusCode(),method);assertEquals("GET",response.headers().firstValue("Allow").orElseThrow());
            }
        }finally{server.stop(0);}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void revocationOrExpiryDuringIdentityLookupCannotAuthorize(boolean expire) throws Exception {
        var localClock=new MutableClock();var store=new SessionStore(localClock,10);var current=store.create(7, 0L);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var repository=new UtenteRepository(noDatabase){
            /* La lettura d'identità passa da findSessione: la sospensione avviene lì. */
            @Override public Optional<StatoSessione> findSessione(Integer id){
                entered.countDown();
                try{assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return Optional.of(new StatoSessione(users.identity,0));
            }
        };
        var handler=new AutorizzazioneHandler(exchange -> calls.incrementAndGet(),
                new AutenticazioneService(repository,new PasswordHasher(),store),store,config,normal);
        var result=new TestHttpExchange("GET","/api/prenotazioni/paziente?idPaziente=7","");
        result.requestHeaders.set("Cookie",config.cookieName()+"="+current.id());
        try(var executor=Executors.newSingleThreadExecutor()){
            var future=executor.submit(() -> {handler.handle(result);return null;});
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                if(expire){localClock.advance(Duration.ofMinutes(30));}else{store.revoke(current.id());}
            }finally{release.countDown();}
            future.get(5,TimeUnit.SECONDS);assertEquals(401,result.status);assertEquals(0,calls.get());
            assertTrue(store.find(current.id()).isEmpty());assertFalse(store.touch(current));
        }
    }
    @ParameterizedTest @ValueSource(strings={
        "/api/prestazioni/", "/api/prestazioni/extra", "/api/prestazioni%2fextra", "/api/%70restazioni",
        "/api/disponibilita/", "/api/disponibilita//", "/api/disponibilita/prenotabili/extra",
        "/api/disponibilita/%31/orario", "/api/disponibilita/1%2f2/orario", "/api/disponibilita/../orario",
        "/api/disponibilita/1/orario/", "/api/disponibilita/1/%6frario", "/api/disponibilita/%2e%2e/configurazione",
        "/api/prenotazioni/", "/api/prenotazioni/richieste/extra", "/api/prenotazioni/%72ichieste",
        "/api/prenotazioni/1/conferma/", "/api/prenotazioni/%31/conferma", "/api/prenotazioni/1%2f2/conferma",
        "/api/prenotazioni//conferma", "/api/prenotazioni/1/%63onferma", "/api/prenotazioni/1/annulla%2dpaziente",
        "/api/prenotazioni/1/sostituisci-medico/extra", "/api/prenotazioni/segnala-indisponibilita/extra"
    }) void hostilePathsNeverReachDatabase(String path) throws Exception {
        HttpHandler controller;String method;
        if(path.startsWith("/api/disponibilita")){controller=new DisponibilitaController(new DisponibilitaService(new DisponibilitaRepository(noDatabase)),normal);method=path.endsWith("orario")||path.endsWith("configurazione")?"PUT":"GET";}
        else if(path.startsWith("/api/prenotazioni")){controller=new PrenotazioneController(new PrenotazioneService(new PrenotazioneRepository(noDatabase)),normal);method=path.contains("conferma")?"POST":"GET";}
        else{controller=new PrestazioneController(new PrestazioneService(new PrestazioneRepository(noDatabase)),normal);method="GET";}
        var result=request(method,path,"{\"idOperatore\":7,\"idMedico\":7}");guard(controller,normal).handle(result);
        assertTrue(Set.of(400,404,405).contains(result.status),path+": "+result.status);assertTrue(result.closed);
    }
}
