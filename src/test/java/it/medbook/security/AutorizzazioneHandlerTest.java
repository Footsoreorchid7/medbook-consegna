package it.medbook.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.*;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutorizzazioneHandlerTest {
    private static final String ORIGIN="http://localhost:8080";
    private final MutableClock clock=new MutableClock();
    private final SessionStore store=new SessionStore(clock,1000);
    private final AuthConfig config=new AuthConfig("local-http",ORIGIN);
    private final ObjectMapper mapper=new ObjectMapper();
    private final FakeUtenteRepository repository=new FakeUtenteRepository();
    private AutorizzazioneHandler handler;
    private SessionStore.Session session;
    private Queue<Observation> calls;
    private record Observation(String path,String principal,String body){}
    private record Endpoint(String method,String uri,String body,Set<String> allowed){}
    private static Set<String> allowed(String... values){return Set.of(values);}
    private static List<Endpoint> endpoints(){return List.of(
            new Endpoint("POST","/api/pazienti/30/collegamento","{\"codiceFiscale\":\"TSTTST90A01A000Q\",\"dataNascita\":\"1990-01-01\"}",allowed("desk")),
            new Endpoint("GET","/api/pazienti","",allowed("desk")),
            new Endpoint("PUT","/api/pazienti/30/password","{\"nuovaPassword\":\"Password paziente\"}",allowed("desk")),
            new Endpoint("PUT","/api/medici/30/password","{\"nuovaPassword\":\" Password sintetica \"}",allowed("admin","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/30/referto","",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/30/referto/documento","",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("POST","/api/prenotazioni/30/referto","binary-pdf",allowed("medico","entrambi","admin-profili")),
            new Endpoint("POST","/api/prenotazioni/30/registra-visita","{}",allowed("medico","entrambi","admin-profili")),
            new Endpoint("POST","/api/prenotazioni/30/pagamento-online","{\"importoAtteso\":80.00}",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/30/pagamenti","",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("POST","/api/prenotazioni/30/rimborso","{\"motivazione\":\"Visita non erogata\"}",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/rimborsi/paziente","",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/rimborsi/richieste","",allowed("desk")),
            new Endpoint("GET","/api/rimborsi/desk","",allowed("desk")),
            new Endpoint("POST","/api/rimborsi/30/approva","{}",allowed("desk")),
            new Endpoint("POST","/api/rimborsi/30/respingi","{}",allowed("desk")),
            new Endpoint("GET","/api/stanze","",allowed("admin","admin-profili","desk")),
            new Endpoint("POST","/api/stanze","{}",allowed("admin","admin-profili")),
            new Endpoint("PUT","/api/stanze/30","{}",allowed("admin","admin-profili")),
            new Endpoint("GET","/api/prestazioni","",allowed("public")),
            new Endpoint("POST","/api/prestazioni","{}",allowed("admin","admin-profili")),
            new Endpoint("PUT","/api/prestazioni/30","{}",allowed("admin","admin-profili")),
            new Endpoint("GET","/api/disponibilita/prenotabili?medicoId=99","",allowed("public")),
            new Endpoint("GET","/api/disponibilita?medicoId=7","",allowed("medico","desk","entrambi","admin-profili")),
            new Endpoint("POST","/api/disponibilita","{\"idMedico\":7}",allowed("medico","entrambi","admin-profili")),
            new Endpoint("PUT","/api/disponibilita/30/orario","{\"idMedico\":7}",allowed("medico","entrambi","admin-profili")),
            new Endpoint("DELETE","/api/disponibilita/30?medicoId=7","",allowed("medico","entrambi","admin-profili")),
            new Endpoint("PUT","/api/disponibilita/30/configurazione","{\"idPrestazione\":99,\"idStanza\":88}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni","{\"idPaziente\":7,\"idDisponibilita\":99}",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/paziente?idPaziente=7","",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/agenda?medicoId=7","",allowed("medico","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/richieste?medicoId=99","",allowed("desk")),
            new Endpoint("GET","/api/prenotazioni/richieste?stato=RIFIUTATA","",allowed("desk")),
            new Endpoint("GET","/api/prenotazioni/richieste?stato=TUTTE","",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/verifica-mancata-erogazione","{\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\",\"esito\":\"NON_EROGATA\"}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/pagamento-sede","{\"idOperatore\":7,\"modalita\":\"DESK_POS\",\"importoAtteso\":80}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/conferma","{\"idOperatore\":7}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/rifiuta","{\"idOperatore\":7}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/annulla","{\"idOperatore\":7,\"statoAtteso\":\"CONFERMATA\"}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/30/annulla-paziente","{\"idPaziente\":7,\"statoAtteso\":\"CONFERMATA\"}",allowed("paziente","entrambi","admin-profili")),
            new Endpoint("POST","/api/prenotazioni/30/sostituisci-medico","{\"idOperatore\":7,\"idMedicoSostituto\":99,\"idMedicoIndisponibile\":88}",allowed("desk")),
            new Endpoint("POST","/api/prenotazioni/segnala-indisponibilita","{\"medicoId\":7,\"idPrenotazioni\":[99,88]}",allowed("medico","entrambi","admin-profili")),
            new Endpoint("GET","/api/prenotazioni/segnalazioni-indisponibilita","",allowed("desk")),
            new Endpoint("GET","/api/prenotazioni/desk?data=2030-01-15&medicoId=99","",allowed("desk"))
    );}
    static Stream<Arguments> roleMatrix(){return endpoints().stream().flatMap(e -> Stream.of("paziente","medico","desk","admin","entrambi","admin-profili").map(r -> Arguments.of(e,r)));}
    static Stream<Arguments> anonymousMatrix(){return endpoints().stream().map(Arguments::of);}
    static Stream<Arguments> mutations(){return endpoints().stream().filter(e -> !e.method.equals("GET")).map(Arguments::of);}
    @ParameterizedTest @ValueSource(strings={"/api/prenotazioni/30/referto", "/api/prenotazioni/30/referto/documento"})
    void reportReadExpiredRevokedAndOrigin(String path) throws Exception {
        clock.advance(Duration.ofMinutes(30));
        var request=exchange("GET",path,"",session);handler.handle(request);assertEquals(401,request.status);
        session=store.create(7, 0L);store.revoke(session.id());request=exchange("GET",path,"",session);handler.handle(request);assertEquals(401,request.status);
        session=store.create(7, 0L);request=exchange("GET",path,"",session);request.requestHeaders.remove("X-CSRF-Token");
        handler.handle(request);assertEquals(200,request.status);
        request=exchange("GET",path,"",session);request.requestHeaders.set("Origin","https://untrusted.invalid");handler.handle(request);assertEquals(403,request.status);
    }
    @BeforeAll void service(){
        handler=new AutorizzazioneHandler(exchange -> {
            String principal=exchange.getPrincipal()==null?null:exchange.getPrincipal().getUsername();
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            calls.add(new Observation(exchange.getRequestURI().toString(),principal,body));
            exchange.sendResponseHeaders(200,0);
        },new AutenticazioneService(repository,new PasswordHasher(),store),store,config,mapper);
    }
    @BeforeEach void reset(){
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","Test",true,true,null);
        repository.identities.clear(); repository.failure=null; repository.idFailure=null;
        repository.idCalls=0; calls=new ConcurrentLinkedQueue<>(); session=store.create(7, 0L);
    }
    private void role(String role){
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","Test",
                role.equals("paziente") || role.equals("entrambi") || role.equals("admin-profili"),
                role.equals("medico") || role.equals("entrambi") || role.equals("admin-profili"),
                role.equals("desk")?RuoloGestionale.OPERATORE_DESK:role.startsWith("admin")?RuoloGestionale.AMMINISTRATORE:null);
    }
    private TestHttpExchange exchange(String method,String path,String body,SessionStore.Session current){
        var result=new TestHttpExchange(method,path,body);
        if(current!=null){result.requestHeaders.set("Cookie",config.cookieName()+"="+current.id()); result.requestHeaders.set("X-CSRF-Token",current.csrfToken());}
        if(!method.equals("GET")){result.requestHeaders.set("Origin",ORIGIN);} return result;
    }
    @ParameterizedTest @MethodSource("roleMatrix") void exactPermissionsForEveryEndpoint(Endpoint endpoint,String profile) throws Exception {
        role(profile); var request=exchange(endpoint.method,endpoint.uri,endpoint.body,session); handler.handle(request);
        boolean permitted=endpoint.allowed.contains("public") || endpoint.allowed.contains(profile);
        assertEquals(permitted?200:403,request.status); assertEquals(permitted?1:0,calls.size()); assertTrue(request.closed);
        if(permitted && !endpoint.allowed.contains("public")) { assertEquals("7",calls.peek().principal); }
        if(endpoint.allowed.contains("public")) { assertEquals(0,repository.idCalls); }
    }
    @ParameterizedTest @MethodSource("anonymousMatrix") void publicOr401ForEveryEndpoint(Endpoint endpoint) throws Exception {
        var request=exchange(endpoint.method,endpoint.uri,endpoint.body,null); handler.handle(request);
        boolean isPublic=endpoint.allowed.contains("public"); assertEquals(isPublic?200:401,request.status);
        assertEquals(isPublic?1:0,calls.size()); assertEquals(0,repository.idCalls);
    }
    @ParameterizedTest @MethodSource("mutations") void everyMutationRequiresCsrf(Endpoint endpoint) throws Exception {
        role(endpoint.allowed.contains("admin")?"admin":endpoint.allowed.contains("desk")?"desk":endpoint.allowed.contains("paziente")?"paziente":"medico");
        var request=exchange(endpoint.method,endpoint.uri,endpoint.body,session);request.requestHeaders.remove("X-CSRF-Token");
        handler.handle(request);assertEquals(403,request.status);assertTrue(calls.isEmpty());
    }
    @ParameterizedTest @MethodSource("mutations") void everyMutationRequiresOrigin(Endpoint endpoint) throws Exception {
        role(endpoint.allowed.contains("admin")?"admin":endpoint.allowed.contains("desk")?"desk":endpoint.allowed.contains("paziente")?"paziente":"medico");
        var request=exchange(endpoint.method,endpoint.uri,endpoint.body,session);request.requestHeaders.remove("Origin");
        handler.handle(request);assertEquals(403,request.status);assertTrue(calls.isEmpty());
    }
    @Test void mismatchedDatabaseIdentityFailsClosed() throws Exception {
        repository.identity=new UtenteAutenticatoResponse(8,"Wrong","Fixture",true,true,RuoloGestionale.OPERATORE_DESK);
        var request=exchange("GET","/api/prenotazioni/richieste","",session);handler.handle(request);
        assertEquals(500,request.status);assertTrue(calls.isEmpty());
    }
    @Test void patientCannotClaimAnotherPatientInBodyOrQuery() throws Exception {
        role("paziente");
        for(var request:List.of(exchange("POST","/api/prenotazioni","{\"idPaziente\":8,\"idDisponibilita\":99}",session),
                exchange("GET","/api/prenotazioni/paziente?idPaziente=8","",session),
                exchange("POST","/api/prenotazioni/99/annulla-paziente","{\"idPaziente\":8}",session))){handler.handle(request);assertEquals(403,request.status);}
        assertTrue(calls.isEmpty());
    }
    @Test void doctorCannotClaimAnotherDoctorAcrossAllActorRoutes() throws Exception {
        role("medico");
        for(var request:List.of(exchange("GET","/api/disponibilita?medicoId=8","",session),
                exchange("GET","/api/prenotazioni/agenda?medicoId=8","",session),
                exchange("POST","/api/disponibilita","{\"idMedico\":8}",session),
                exchange("PUT","/api/disponibilita/99/orario","{\"idMedico\":8}",session),
                exchange("DELETE","/api/disponibilita/99?medicoId=8","",session),
                exchange("POST","/api/prenotazioni/segnala-indisponibilita","{\"medicoId\":8,\"idPrenotazioni\":[99]}",session))){handler.handle(request);assertEquals(403,request.status);}
        assertTrue(calls.isEmpty());
    }
    @Test void deskActorCannotBeSpoofedOnAnyDeskMutation() throws Exception {
        role("desk");
        for(String action:List.of("conferma","rifiuta","annulla","sostituisci-medico","pagamento-sede","verifica-mancata-erogazione")){
            var request=exchange("POST","/api/prenotazioni/99/"+action,"{\"idOperatore\":8}",session);
            handler.handle(request);assertEquals(403,request.status);
        } assertTrue(calls.isEmpty());
    }
    @Test void deskCanConsultDifferentDoctorAndFiltersAreNotActors() throws Exception {
        role("desk");
        for(String path:List.of("/api/disponibilita?medicoId=99","/api/prenotazioni/richieste?medicoId=88&prestazioneId=77")){
            var request=exchange("GET",path,"",session); handler.handle(request); assertEquals(200,request.status);
        } assertEquals(2,calls.size());
    }
    @Test void substituteAndUnavailableDoctorAreResourcesNotActors() throws Exception {
        role("desk"); String body="{\"idOperatore\":7,\"idMedicoSostituto\":99,\"idMedicoIndisponibile\":88}";
        var request=exchange("POST","/api/prenotazioni/30/sostituisci-medico",body,session);handler.handle(request);
        assertEquals(200,request.status);assertEquals(body,calls.peek().body);
    }
    @Test void userWithoutProfilesDoesNotBecomePatientOrDoctorByNullRole() throws Exception {
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","Test",false,false,null);
        for(String path:List.of("/api/prenotazioni/paziente?idPaziente=7","/api/prenotazioni/agenda?medicoId=7")){
            var request=exchange("GET",path,"",session);handler.handle(request);assertEquals(403,request.status);
        }assertTrue(calls.isEmpty());
    }
    @Test void profilesAndRoleAreReloadedEveryRequest() throws Exception {
        role("desk");var first=exchange("GET","/api/prenotazioni/richieste","",session);handler.handle(first);assertEquals(200,first.status);
        role("admin");var second=exchange("GET","/api/prenotazioni/richieste","",session);handler.handle(second);assertEquals(403,second.status);
        assertEquals(2,repository.idCalls);assertEquals(1,calls.size());
    }
    @Test void expiredAndRevokedSessionsAre401WithoutClinicalCalls() throws Exception {
        clock.advance(Duration.ofMinutes(30)); var expired=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(expired);
        assertEquals(401,expired.status);session=store.create(7, 0L);store.revoke(session.id());
        var revoked=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(revoked);assertEquals(401,revoked.status);
        assertTrue(calls.isEmpty());assertEquals(0,repository.idCalls);
    }
    @Test void missingDatabaseIdentityRevokesSession() throws Exception {
        repository.identity=null;var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(request);
        assertEquals(401,request.status);assertTrue(store.find(session.id()).isEmpty());assertTrue(calls.isEmpty());
    }
    @Test void csrfAndOriginAreRequiredForEveryMutation() throws Exception {
        role("desk");
        for(String token:new String[]{null,"x".repeat(43),session.id()}){
            var request=exchange("PUT","/api/disponibilita/99/configurazione","{}",session);
            if(token==null){request.requestHeaders.remove("X-CSRF-Token");}else{request.requestHeaders.set("X-CSRF-Token",token);}
            handler.handle(request);assertEquals(403,request.status);
        }
        for(String origin:new String[]{null,"null","https://evil.example.invalid"}){
            var request=exchange("PUT","/api/disponibilita/99/configurazione","{}",session);
            if(origin==null){request.requestHeaders.remove("Origin");}else{request.requestHeaders.set("Origin",origin);}
            handler.handle(request);assertEquals(403,request.status);
        } assertTrue(calls.isEmpty());
    }
    @Test void csrfTokenFromAnotherSessionDoesNotAuthorize() throws Exception {
        role("desk");var request=exchange("POST","/api/prenotazioni/99/conferma","{\"idOperatore\":7}",session);
        request.requestHeaders.set("X-CSRF-Token",store.create(7, 0L).csrfToken());handler.handle(request);
        assertEquals(403,request.status);assertTrue(calls.isEmpty());
    }
    @Test void duplicateOriginAndCsrfHeadersFailClosed() throws Exception {
        role("desk");
        for(String header:List.of("Origin","X-CSRF-Token")){
            var request=exchange("POST","/api/prenotazioni/99/conferma","{\"idOperatore\":7}",session);
            request.requestHeaders.add(header,request.requestHeaders.getFirst(header));handler.handle(request);assertEquals(403,request.status);
        }assertTrue(calls.isEmpty());
    }
    @Test void failedCsrfCannotExtendSession() throws Exception {
        role("desk");clock.advance(Duration.ofMinutes(29));var request=exchange("POST","/api/prenotazioni/99/conferma","{\"idOperatore\":7}",session);
        request.requestHeaders.remove("X-CSRF-Token");handler.handle(request);clock.advance(Duration.ofMinutes(1));assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void successfulReadExtendsIdleButCannotExtendAbsoluteLifetime() throws Exception {
        role("medico");
        for(int i=0;i<16;i++){
            clock.advance(Duration.ofMinutes(29));var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(request);assertEquals(200,request.status);
        }
        clock.advance(Duration.ofMinutes(16));var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(request);assertEquals(401,request.status);
    }
    @Test void protectedGetRejectsForeignOriginWhenPresent() throws Exception {
        var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);request.requestHeaders.set("Origin","https://evil.example.invalid");
        handler.handle(request);assertEquals(403,request.status);assertTrue(calls.isEmpty());
    }
    @Test void publicReadsIgnoreBadCookieAndDoNotQueryIdentity() throws Exception {
        for(String path:List.of("/api/prestazioni","/api/disponibilita/prenotabili")){
            var request=exchange("GET",path,"",null);request.requestHeaders.set("Cookie",config.cookieName()+"=invalid");
            request.requestHeaders.set("Origin","https://other.example.invalid");handler.handle(request);assertEquals(200,request.status);
        }assertEquals(0,repository.idCalls);
    }
    @Test void actorJsonRejectsMalformedUnknownTypesAndDecodedDuplicates() throws Exception {
        role("paziente");
        for(String body:List.of("{","null","[]","{}","{\"idPaziente\":null}","{\"idPaziente\":\"7\"}",
                "{\"idPaziente\":7.0}","{\"idPaziente\":0}","{\"idPaziente\":-1}","{\"idPaziente\":2147483648}",
                "{\"idPaziente\":7} {}","{\"idPaziente\":7,\"idPaziente\":8}","{\"idPaziente\":7,\"\\u0069dPaziente\":8}")){
            var request=exchange("POST","/api/prenotazioni",body,session);handler.handle(request);assertEquals(400,request.status,body);
        }assertTrue(calls.isEmpty());
    }
    @Test void actorQueryRejectsMissingMalformedAndDecodedDuplicateKeys() throws Exception {
        for(String query:List.of("","?","?medicoId=","?medicoId=x","?medicoId=0","?medicoId=-1","?medicoId=2147483648",
                "?medicoId=7&medicoId=8","?medicoId=7&%6DedicoId=8","?medicoId=7&broken")){
            var request=exchange("GET","/api/prenotazioni/agenda"+query,"",session);handler.handle(request);assertEquals(400,request.status,query);
        }assertTrue(calls.isEmpty());
    }
    @Test void oversizedBodyIsRejectedWithoutTruncatingOrDelegating() throws Exception {
        var request=exchange("POST","/api/prenotazioni","x".repeat(1024*1024+1),session);handler.handle(request);assertEquals(400,request.status);assertTrue(calls.isEmpty());
    }
    @Test void duplicateCookiesFailBeforeDatabase() throws Exception {
        var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);request.requestHeaders.add("Cookie",config.cookieName()+"="+session.id());
        handler.handle(request);assertEquals(400,request.status);assertEquals(0,repository.idCalls);
    }
    @Test void unknownSubpathsAndEncodedPublicPathsAreNeverDelegated() throws Exception {
        for(String path:List.of("/api/prestazioni/extra","/api/prestazioni/extra/sub","/api/prestazioni/","/api/prestazioni/../prenotazioni/richieste",
                "/api/%70restazioni","/api/disponibilita/prenotabili/extra","/api/prenotazioni/richieste/extra","/api/prenotazioni/99/conferma/extra")){
            var request=exchange("GET",path,"",session);handler.handle(request);assertEquals(404,request.status,path);
        }assertTrue(calls.isEmpty());assertEquals(0,repository.idCalls);
    }
    @Test void unsupportedMethodsReturn405AndExactAllowWithoutDelegation() throws Exception {
        for(String path:List.of("/api/prestazioni","/api/disponibilita","/api/prenotazioni/99/conferma","/api/disponibilita/99/orario")){
            var request=exchange("PATCH",path,"",null);handler.handle(request);assertEquals(405,request.status);
            assertEquals(path.equals("/api/disponibilita")?"GET, POST":path.contains("orario")?"PUT":path.contains("conferma")?"POST":"GET, POST",request.responseHeaders.getFirst("Allow"));
        }assertTrue(calls.isEmpty());
    }
    @Test void deskBookingListIsExactGetWithFilterNotBoundToIdentity() throws Exception {
        role("desk");
        for(String method:List.of("POST","PUT","DELETE","PATCH","HEAD")){
            var request=exchange(method,"/api/prenotazioni/desk?data=2030-01-15","",session);handler.handle(request);
            assertEquals(405,request.status,method);assertEquals("GET",request.responseHeaders.getFirst("Allow"));
        }
        for(String path:List.of("/api/prenotazioni/desk/","/api/prenotazioni/desk/extra","/api/prenotazioni/%64esk","/api/prenotazioni/Desk")){
            var request=exchange("GET",path+"?data=2030-01-15","",session);handler.handle(request);assertEquals(404,request.status,path);
        }
        assertTrue(calls.isEmpty());
        var request=exchange("GET","/api/prenotazioni/desk?medicoId=99&dataDa=2030-01-01&dataA=2030-01-31","",session);handler.handle(request);
        assertEquals(200,request.status);assertEquals("7",calls.peek().principal);
        assertEquals("/api/prenotazioni/desk?medicoId=99&dataDa=2030-01-01&dataA=2030-01-31",calls.peek().path);
    }
    @Test void sqlErrorIsGenericAndNeverDelegates() throws Exception {
        repository.failure=new SQLException("SELECT password_hash; dettaglio sintetico");
        var request=exchange("GET","/api/prenotazioni/agenda?medicoId=7","",session);handler.handle(request);
        assertEquals(500,request.status);assertEquals("{\"error\":\"Errore interno del server.\"}",request.body());assertTrue(calls.isEmpty());
    }
    @Test void deniedResponsesAreNoStoreJsonAndDoNotExposeSessionSecrets() throws Exception {
        role("admin");var request=exchange("GET","/api/prenotazioni/richieste","",session);handler.handle(request);
        assertEquals(403,request.status);assertEquals("no-store",request.responseHeaders.getFirst("Cache-Control"));
        assertEquals("application/json; charset=UTF-8",request.responseHeaders.getFirst("Content-Type"));
        assertFalse(request.body().contains(session.id()));assertFalse(request.body().contains(session.csrfToken()));assertFalse(request.body().contains("password"));
    }
    @Test void concurrentPatientAndDoctorRequestsCannotMixPrincipalsOrBodies() throws Exception {
        repository.identities.put(7,new UtenteAutenticatoResponse(7,"Patient","Test",true,false,null));
        repository.identities.put(8,new UtenteAutenticatoResponse(8,"Doctor","Test",false,true,null));var second=store.create(8, 0L);
        try(var executor=Executors.newFixedThreadPool(6)){
            List<Callable<Void>> jobs=new ArrayList<>();
            for(int i=0;i<40;i++){boolean patient=i%2==0;jobs.add(() -> {
                var request=patient?exchange("POST","/api/prenotazioni","{\"idPaziente\":7,\"idDisponibilita\":99}",session)
                        :exchange("POST","/api/disponibilita","{\"idMedico\":8}",second);
                handler.handle(request);assertEquals(200,request.status);return null;
            });}
            for(var result:executor.invokeAll(jobs)){result.get();}
        }
        assertEquals(40,calls.size());for(var observation:calls){
            if(observation.path.equals("/api/prenotazioni")){assertEquals("7",observation.principal);assertEquals(7,mapper.readTree(observation.body).get("idPaziente").intValue());}
            else{assertEquals("8",observation.principal);assertEquals(8,mapper.readTree(observation.body).get("idMedico").intValue());}
        }
    }
    @ParameterizedTest @ValueSource(strings={"pagamento-online","pagamenti"})
    void onlinePaymentRoutesRejectExpiredRevokedAndUnknownSessions(String action) throws Exception {
        String method=action.equals("pagamenti")?"GET":"POST";
        String path="/api/prenotazioni/30/"+action;
        clock.advance(Duration.ofMinutes(30));handler.handle(exchange(method,path,"{\"importoAtteso\":80}",session));
        var expired=exchange(method,path,"{}",session);handler.handle(expired);assertEquals(401,expired.status);
        session=store.create(7, 0L);store.revoke(session.id());var revoked=exchange(method,path,"{}",session);handler.handle(revoked);assertEquals(401,revoked.status);
        var unknown=exchange(method,path,"{}",null);unknown.requestHeaders.set("Cookie",config.cookieName()+"="+"x".repeat(43));handler.handle(unknown);assertEquals(401,unknown.status);
        assertTrue(calls.isEmpty());assertEquals(0,repository.idCalls);
    }
    @Test void onlinePaymentRejectsWrongOriginAndCsrfOfAnotherSession() throws Exception {
        role("paziente");String path="/api/prenotazioni/30/pagamento-online";
        var other=store.create(7, 0L);
        for(String token:List.of("x".repeat(43),other.csrfToken())) {
            var request=exchange("POST",path,"{\"importoAtteso\":80}",session);request.requestHeaders.set("X-CSRF-Token",token);
            handler.handle(request);assertEquals(403,request.status);
        }
        var request=exchange("POST",path,"{\"importoAtteso\":80}",session);request.requestHeaders.set("Origin","https://evil.example.invalid");
        handler.handle(request);assertEquals(403,request.status);assertTrue(calls.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"/api/prenotazioni/30/pagamento-online/","/api/prenotazioni/30/pagamenti/extra","/api/prenotazioni/30/pagamento%2Donline","/api/prenotazioni/30/pagamenti%2Fextra"})
    void onlinePaymentSecurityRejectsSubpathsAndEncodedAliases(String path) throws Exception {
        var request=exchange("POST",path,"{}",session);handler.handle(request);assertEquals(404,request.status);assertTrue(calls.isEmpty());
    }

    @Test void visitRequiresLiveSessionAndExactRoute() throws Exception {
        role("medico");String path="/api/prenotazioni/30/registra-visita";
        clock.advance(Duration.ofMinutes(30));var expired=exchange("POST",path,"{}",session);handler.handle(expired);assertEquals(401,expired.status);
        session=store.create(7, 0L);store.revoke(session.id());var revoked=exchange("POST",path,"{}",session);handler.handle(revoked);assertEquals(401,revoked.status);
        session=store.create(7, 0L);
        for(String suffix:List.of("/","/extra")) {var x=exchange("POST",path+suffix,"{}",session);handler.handle(x);assertEquals(404,x.status);}
        for(String alias:List.of("/api/prenotazioni/%33%30/registra-visita","/api/prenotazioni/30/registra%2Dvisita")){var x=exchange("POST",alias,"{}",session);handler.handle(x);assertEquals(404,x.status);}
        var method=exchange("GET",path,"",session);handler.handle(method);assertEquals(405,method.status);assertEquals("POST",method.responseHeaders.getFirst("Allow"));assertTrue(calls.isEmpty());
    }
    @Test void visitRejectsForeignCsrfAndOriginWithoutDelegation() throws Exception {
        role("medico");String path="/api/prenotazioni/30/registra-visita";
        var csrf=exchange("POST",path,"{}",session);csrf.requestHeaders.set("X-CSRF-Token",store.create(7, 0L).csrfToken());handler.handle(csrf);assertEquals(403,csrf.status);
        var origin=exchange("POST",path,"{}",session);origin.requestHeaders.set("Origin","https://evil.example.invalid");handler.handle(origin);assertEquals(403,origin.status);assertTrue(calls.isEmpty());
    }

    @Test void administratorRoleIsReloadedAndBodyIsNotParsedBeforeAuthorization() throws Exception {
        role("admin");var first=exchange("POST","/api/prestazioni","malformed",session);
        handler.handle(first);assertEquals(200,first.status);assertEquals("malformed",calls.peek().body);
        role("desk");var second=exchange("POST","/api/prestazioni","malformed",session);
        handler.handle(second);assertEquals(403,second.status);assertEquals(1,calls.size());assertEquals(2,repository.idCalls);
        store.revoke(session.id());var third=exchange("PUT","/api/prestazioni/30","{}",session);
        handler.handle(third);assertEquals(401,third.status);assertEquals(1,calls.size());
    }
    @Test void administratorRoutesRejectEncodedAliasesAndExtraPaths() throws Exception {
        role("admin");for(String path:List.of("/api/%70restazioni","/api/prestazioni/%33","/api/prestazioni/3/","/api/prestazioni/3/extra","/api/prestazioni/..")) {
            var request=exchange("PUT",path,"{}",session);handler.handle(request);assertEquals(404,request.status,path);
        }assertTrue(calls.isEmpty());
    }

    @Test void catalogResourceRoutingPrecedesSecurityOnlyForRecognizedPaths() throws Exception {
        for(String path:List.of("/api/prestazioni/extra","/api/prestazioni/0","/api/prestazioni/-1","/api/prestazioni/01","/api/prestazioni/2147483648","/api/prestazioni/99999999999999999999","/api/prestazioni/%37","/api/prestazioni/7/extra","/api/prestazioni/7/")) {
            for(String method:List.of("GET","PUT")) {var e=exchange(method,path,"{}",null);handler.handle(e);assertEquals(404,e.status,path);assertNull(e.responseHeaders.getFirst("Allow"));}
        }
        for(String path:List.of("/api/prestazioni/7","/api/prestazioni/2147483647")) {
            var get=exchange("GET",path,"",null);handler.handle(get);assertEquals(405,get.status);assertEquals("PUT",get.responseHeaders.getFirst("Allow"));
            var put=exchange("PUT",path,"{}",null);handler.handle(put);assertEquals(401,put.status);
        }assertTrue(calls.isEmpty());assertEquals(0,repository.idCalls);
    }

}
