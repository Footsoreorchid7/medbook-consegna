package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import it.medbook.config.AuthConfig;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AutenticazioneControllerTest {
    private static final String ORIGIN="http://localhost:8080";
    private static final String LOGIN="{\"email\":\"solo-test@example.invalid\",\"password\":\"Solo-test-controller\"}";
    private static final PasswordHasher HASHER=new PasswordHasher();
    private static String hash;
    private final ObjectMapper mapper=new ObjectMapper();
    private final MutableClock clock=new MutableClock();
    private final FakeUtenteRepository repository=new FakeUtenteRepository();
    private final SessionStore store=new SessionStore(clock,100);
    private final LoginThrottle throttle=new LoginThrottle(clock,100,10);
    private final AuthConfig config=new AuthConfig("local-http",ORIGIN);
    private AutenticazioneController controller;
    @BeforeAll static void hash() { hash=HASHER.hash("Solo-test-controller".toCharArray()); }
    @BeforeEach void prepare() {
        repository.identity=new UtenteAutenticatoResponse(7,"Nome","Sintetico",true,true,RuoloGestionale.AMMINISTRATORE);
        repository.account=new UtenteRepository.Credenziali(repository.identity,hash);
        controller=new AutenticazioneController(new AutenticazioneService(repository,HASHER,store),store,throttle,config,mapper);
    }
    private Exchange request(String method,String path,String body) {
        Exchange exchange=new Exchange(method,path,body);
        if(method.equals("POST")) { exchange.requestHeaders.set("Origin",ORIGIN); }
        exchange.requestHeaders.set("Content-Type","application/json"); return exchange;
    }
    private Exchange login() throws IOException {
        Exchange exchange=request("POST","/api/auth/login",LOGIN); controller.handle(exchange);
        assertEquals(200,exchange.status); return exchange;
    }
    private void sessionHeaders(Exchange exchange,SessionStore.Session session) {
        exchange.requestHeaders.set("Cookie",config.cookieName()+"="+session.id());
        exchange.requestHeaders.set("X-CSRF-Token",session.csrfToken());
    }
    @Test void loginReturnsSafeIdentityCookieAndCsrfHeader() throws Exception {
        Exchange exchange=login(); var json=mapper.readTree(exchange.body());
        assertEquals(7,json.get("idUtente").asInt()); assertTrue(json.get("paziente").asBoolean());
        assertTrue(json.get("medico").asBoolean()); assertEquals("AMMINISTRATORE",json.get("ruoloGestionale").asText());
        assertFalse(exchange.body().contains("password")); assertFalse(exchange.body().contains("email"));
        assertFalse(exchange.body().contains(hash)); assertFalse(exchange.body().contains("session"));
        String cookie=exchange.responseHeaders.getFirst("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/")); assertFalse(cookie.contains("Domain="));
        assertEquals(43,exchange.responseHeaders.getFirst("X-CSRF-Token").length());
        assertFalse(exchange.body().contains(exchange.responseHeaders.getFirst("X-CSRF-Token")));
        assertFalse(exchange.body().contains(cookie.split(";",2)[0].split("=",2)[1]));
    }
    @Test void wrongPasswordAndMissingEmailHaveSameResponse() throws Exception {
        Exchange wrong=request("POST","/api/auth/login",LOGIN.replace("Solo-test-controller","Errata"));
        controller.handle(wrong); repository.account=null;
        Exchange missing=request("POST","/api/auth/login",LOGIN); controller.handle(missing);
        assertEquals(401,wrong.status); assertEquals(wrong.status,missing.status); assertEquals(wrong.body(),missing.body());
        assertEquals(wrong.responseHeaders,missing.responseHeaders); assertEquals(0,store.size());
    }
    @Test void unsupportedHashIsRejectedGenerically() throws Exception {
        repository.account=new UtenteRepository.Credenziali(repository.identity,"hash-fittizio");
        Exchange exchange=request("POST","/api/auth/login",LOGIN); controller.handle(exchange);
        assertEquals(401,exchange.status); assertEquals("{\"error\":\"Credenziali non valide.\"}",exchange.body());
    }
    @Test void malformedDuplicateUnknownAndTrailingJsonNeverReachRepository() throws Exception {
        for(String body : List.of("", "null", "[]", "{", "{}", "{\"email\":1,\"password\":\"x\"}",
                "{\"email\":\"x\",\"password\":null}", LOGIN.replace("}",",\"extra\":1}"),
                LOGIN.replace("}",",\"email\":\"second\"}"), LOGIN+" {}")) {
            Exchange exchange=request("POST","/api/auth/login",body); controller.handle(exchange);
            assertEquals(400,exchange.status,body); assertTrue(exchange.closed); assertTrue(exchange.inputClosed);
        }
        assertEquals(0,repository.emailCalls);
    }
    @Test void decodedDuplicateJsonPropertyIsRejected() throws Exception {
        String body=LOGIN.replace("}",",\"\\u0065mail\":\"second\"}");
        Exchange exchange=request("POST","/api/auth/login",body); controller.handle(exchange);
        assertEquals(400,exchange.status); assertEquals(0,repository.emailCalls);
    }
    @Test void oversizedBodyIsRejectedBeforeService() throws Exception {
        Exchange exchange=request("POST","/api/auth/login","x".repeat(8193)); controller.handle(exchange);
        assertEquals(400,exchange.status); assertEquals(0,repository.emailCalls);
    }
    @Test void invalidEmailAndPasswordLengthsAreRejectedBeforeRepository() throws Exception {
        for(String body : List.of(LOGIN.replace("solo-test@example.invalid"," "),LOGIN.replace("Solo-test-controller",""),
                LOGIN.replace("Solo-test-controller","x".repeat(1025)))) {
            Exchange exchange=request("POST","/api/auth/login",body); controller.handle(exchange); assertEquals(400,exchange.status);
        }
        assertEquals(0,repository.emailCalls);
    }
    @Test void loginRequiresJsonContentType() throws Exception {
        for(String type : List.of("text/plain","application/x-www-form-urlencoded")) {
            Exchange exchange=request("POST","/api/auth/login",LOGIN); exchange.requestHeaders.set("Content-Type",type);
            controller.handle(exchange); assertEquals(400,exchange.status);
        }
        assertEquals(0,repository.emailCalls);
    }
    @Test void loginRejectsMissingForeignNullAndDuplicateOriginsBeforeService() throws Exception {
        for(String value : new String[]{null,"https://foreign.example.invalid","null",ORIGIN+"/"}) {
            Exchange exchange=request("POST","/api/auth/login",LOGIN);
            if(value==null) { exchange.requestHeaders.remove("Origin"); } else { exchange.requestHeaders.set("Origin",value); }
            controller.handle(exchange); assertEquals(403,exchange.status);
        }
        Exchange duplicate=request("POST","/api/auth/login",LOGIN); duplicate.requestHeaders.add("Origin",ORIGIN);
        controller.handle(duplicate); assertEquals(403,duplicate.status); assertEquals(0,repository.emailCalls);
    }
    @Test void spoofedForwardedHeadersDoNotBypassOrigin() throws Exception {
        Exchange exchange=request("POST","/api/auth/login",LOGIN); exchange.requestHeaders.set("Origin","https://evil.example.invalid");
        exchange.requestHeaders.set("X-Forwarded-Proto","https"); exchange.requestHeaders.set("X-Forwarded-For","127.0.0.1");
        controller.handle(exchange); assertEquals(403,exchange.status); assertEquals(0,repository.emailCalls);
    }
    @Test void meRequiresSession() throws Exception {
        Exchange exchange=request("GET","/api/auth/me",""); controller.handle(exchange);
        assertEquals(401,exchange.status); assertEquals(0,repository.idCalls);
    }
    @Test void meReturnsCurrentDatabaseIdentityAndOverlappingProfiles() throws Exception {
        var session=store.create(7, 0L);
        repository.identity=new UtenteAutenticatoResponse(7,"Nome","Sintetico",false,true,RuoloGestionale.OPERATORE_DESK);
        Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,session); controller.handle(exchange);
        var json=mapper.readTree(exchange.body()); assertEquals(200,exchange.status); assertFalse(json.get("paziente").asBoolean());
        assertTrue(json.get("medico").asBoolean()); assertEquals("OPERATORE_DESK",json.get("ruoloGestionale").asText());
        assertEquals(1,repository.idCalls); assertFalse(exchange.body().contains("password"));
        assertEquals(session.csrfToken(),exchange.responseHeaders.getFirst("X-CSRF-Token"));
    }
    @Test void mePreservesNullRoleWithoutInferringPatient() throws Exception {
        repository.identity=new UtenteAutenticatoResponse(7,"Nome","Sintetico",false,false,null);
        Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,store.create(7, 0L)); controller.handle(exchange);
        var json=mapper.readTree(exchange.body()); assertTrue(json.get("ruoloGestionale").isNull());
        assertFalse(json.get("paziente").asBoolean()); assertFalse(json.get("medico").asBoolean());
    }
    @Test void meRejectsExpiredAndRevokedSessions() throws Exception {
        var session=store.create(7, 0L); clock.advance(Duration.ofMinutes(30));
        Exchange expired=request("GET","/api/auth/me",""); sessionHeaders(expired,session); controller.handle(expired); assertEquals(401,expired.status);
        var next=store.create(7, 0L); store.revoke(next.id());
        Exchange revoked=request("GET","/api/auth/me",""); sessionHeaders(revoked,next); controller.handle(revoked); assertEquals(401,revoked.status);
    }
    @Test void deletedDatabaseUserRevokesSession() throws Exception {
        var session=store.create(7, 0L); repository.identity=null;
        Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,session); controller.handle(exchange);
        assertEquals(401,exchange.status); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void logoutRequiresCsrfAndDoesNotRevokeOnFailure() throws Exception {
        var session=store.create(7, 0L);
        for(String token : new String[]{null,"x".repeat(43),session.id()}) {
            Exchange exchange=request("POST","/api/auth/logout",""); sessionHeaders(exchange,session);
            if(token==null) { exchange.requestHeaders.remove("X-CSRF-Token"); } else { exchange.requestHeaders.set("X-CSRF-Token",token); }
            controller.handle(exchange); assertEquals(403,exchange.status); assertTrue(store.find(session.id()).isPresent());
        }
    }
    @Test void duplicateCsrfHeadersAreRejected() throws Exception {
        var session=store.create(7, 0L); Exchange exchange=request("POST","/api/auth/logout",""); sessionHeaders(exchange,session);
        exchange.requestHeaders.add("X-CSRF-Token",session.csrfToken()); controller.handle(exchange);
        assertEquals(403,exchange.status); assertTrue(store.find(session.id()).isPresent());
    }
    @Test void foreignOriginDoesNotRevokeSessionEvenWithCorrectCsrf() throws Exception {
        var session=store.create(7, 0L); Exchange exchange=request("POST","/api/auth/logout",""); sessionHeaders(exchange,session);
        exchange.requestHeaders.set("Origin","https://evil.example.invalid"); controller.handle(exchange);
        assertEquals(403,exchange.status); assertTrue(store.find(session.id()).isPresent());
    }
    @Test void successfulLogoutRevokesImmediatelyAndExpiresCookie() throws Exception {
        var session=store.create(7, 0L); Exchange exchange=request("POST","/api/auth/logout",""); sessionHeaders(exchange,session);
        controller.handle(exchange); assertEquals(200,exchange.status); assertTrue(store.find(session.id()).isEmpty());
        assertTrue(exchange.responseHeaders.getFirst("Set-Cookie").contains("Max-Age=0"));
        Exchange next=request("GET","/api/auth/me",""); sessionHeaders(next,session); controller.handle(next); assertEquals(401,next.status);
    }
    @Test void logoutWithoutSessionReturns401() throws Exception {
        Exchange exchange=request("POST","/api/auth/logout",""); controller.handle(exchange); assertEquals(401,exchange.status);
    }
    @Test void logoutRejectsUnexpectedBodyWithoutRevocation() throws Exception {
        var session=store.create(7, 0L); Exchange exchange=request("POST","/api/auth/logout","{}"); sessionHeaders(exchange,session);
        controller.handle(exchange); assertEquals(400,exchange.status); assertTrue(store.find(session.id()).isPresent());
    }
    @Test void failedCsrfDoesNotExtendIdleTimeout() throws Exception {
        var session=store.create(7, 0L); clock.advance(Duration.ofMinutes(29));
        Exchange exchange=request("POST","/api/auth/logout",""); sessionHeaders(exchange,session); exchange.requestHeaders.remove("X-CSRF-Token");
        controller.handle(exchange); assertEquals(403,exchange.status); clock.advance(Duration.ofMinutes(1)); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void existingSessionLoginRequiresCsrfAndRotatesSession() throws Exception {
        var previous=store.create(7, 0L);
        Exchange failed=request("POST","/api/auth/login",LOGIN); sessionHeaders(failed,previous); failed.requestHeaders.remove("X-CSRF-Token");
        controller.handle(failed); assertEquals(403,failed.status); assertEquals(0,repository.emailCalls);
        Exchange success=request("POST","/api/auth/login",LOGIN); sessionHeaders(success,previous); controller.handle(success);
        assertEquals(200,success.status); assertTrue(store.find(previous.id()).isEmpty()); assertEquals(1,store.size());
        assertFalse(success.responseHeaders.getFirst("Set-Cookie").contains(previous.id()));
    }
    @Test void exactPathsAndNoQueriesAreRequired() throws Exception {
        for(String path : List.of("/api/auth", "/api/auth/login/", "/api/auth/login/extra", "/api/auth/%6cogin")) {
            Exchange exchange=request("POST",path,LOGIN); controller.handle(exchange); assertEquals(404,exchange.status,path);
        }
        for(String path : List.of("/api/auth/login?x=1","/api/auth/me?","/api/auth/logout?x=1")) {
            Exchange exchange=request(path.contains("/me")?"GET":"POST",path,LOGIN); controller.handle(exchange); assertEquals(400,exchange.status);
        }
        assertEquals(0,repository.emailCalls); assertEquals(0,repository.idCalls);
    }
    @Test void wrongMethodsHaveExactAllowAndNeverReachService() throws Exception {
        for(String path : List.of("/api/auth/login","/api/auth/me","/api/auth/logout")) {
            for(String method : List.of("PUT","DELETE","OPTIONS","HEAD",path.endsWith("/me")?"POST":"GET")) {
                Exchange exchange=request(method,path,LOGIN); controller.handle(exchange); assertEquals(405,exchange.status);
                assertEquals(path.endsWith("/me")?"GET":"POST",exchange.responseHeaders.getFirst("Allow")); assertTrue(exchange.closed);
            }
        }
        assertEquals(0,repository.emailCalls); assertEquals(0,repository.idCalls);
    }
    @Test void duplicateSessionCookiesAreRejected() throws Exception {
        var session=store.create(7, 0L); Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,session);
        exchange.requestHeaders.add("Cookie",config.cookieName()+"="+session.id()); controller.handle(exchange);
        assertEquals(400,exchange.status); assertEquals(0,repository.idCalls);
    }
    @Test void invalidOpaqueCookieIsNotAuthentication() throws Exception {
        Exchange exchange=request("GET","/api/auth/me",""); exchange.requestHeaders.set("Cookie",config.cookieName()+"=7");
        controller.handle(exchange); assertEquals(401,exchange.status); assertEquals(0,repository.idCalls);
    }
    @Test void throttleStopsBeforeDatabaseAndDoesNotTrustForwardedFor() throws Exception {
        for(int i=0;i<10;i++) { assertTrue(throttle.allow("127.0.0.1")); }
        Exchange exchange=request("POST","/api/auth/login",LOGIN); exchange.requestHeaders.set("X-Forwarded-For","192.0.2.1");
        controller.handle(exchange); assertEquals(429,exchange.status); assertEquals(0,repository.emailCalls);
        assertEquals("300",exchange.responseHeaders.getFirst("Retry-After"));
    }
    @Test void fullSessionStoreReturns503WithoutOverwritingActiveSession() throws Exception {
        var bounded=new SessionStore(clock,1); var first=bounded.create(7, 0L);
        var handler=new AutenticazioneController(new AutenticazioneService(repository,HASHER,bounded),bounded,throttle,config,mapper);
        Exchange exchange=request("POST","/api/auth/login",LOGIN); handler.handle(exchange);
        assertEquals(503,exchange.status); assertTrue(bounded.find(first.id()).isPresent()); assertNull(exchange.responseHeaders.getFirst("Set-Cookie"));
    }
    @Test void sqlFailureIsGenericAndDoesNotSetCookie() throws Exception {
        repository.failure=new SQLException("SELECT password_hash; dettaglio tecnico sintetico");
        Exchange exchange=request("POST","/api/auth/login",LOGIN); controller.handle(exchange);
        assertEquals(500,exchange.status); assertEquals("{\"error\":\"Errore interno del server.\"}",exchange.body());
        assertNull(exchange.responseHeaders.getFirst("Set-Cookie")); assertFalse(exchange.body().contains("password_hash"));
    }
    @Test void failedIdentityReloadRevokesNewSession() throws Exception {
        repository.identity=null; Exchange exchange=request("POST","/api/auth/login",LOGIN); controller.handle(exchange);
        assertEquals(401,exchange.status); assertEquals(0,store.size()); assertNull(exchange.responseHeaders.getFirst("Set-Cookie"));
    }
    @Test void identitySqlFailureAfterLoginRevokesNewSessionAndReturnsGeneric500() throws Exception {
        repository.idFailure=new SQLException("Dettaglio database sintetico");
        Exchange exchange=request("POST","/api/auth/login",LOGIN); controller.handle(exchange);
        assertEquals(500,exchange.status); assertEquals(0,store.size());
        assertNull(exchange.responseHeaders.getFirst("Set-Cookie")); assertFalse(exchange.body().contains("Dettaglio"));
    }
    @Test void meSqlFailureDoesNotRefreshOrRevokeExistingSession() throws Exception {
        var session=store.create(7, 0L); clock.advance(Duration.ofMinutes(29));
        repository.idFailure=new SQLException("Dettaglio sintetico");
        Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,session); controller.handle(exchange);
        assertEquals(500,exchange.status); assertTrue(store.find(session.id()).isPresent());
        assertNull(exchange.responseHeaders.getFirst("X-CSRF-Token"));
        clock.advance(Duration.ofMinutes(1)); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void responsesAreJsonNoStoreAndResourcesAreClosed() throws Exception {
        Exchange exchange=login(); assertEquals("application/json; charset=UTF-8",exchange.responseHeaders.getFirst("Content-Type"));
        assertEquals("no-store",exchange.responseHeaders.getFirst("Cache-Control")); assertTrue(exchange.closed);
        assertTrue(exchange.inputClosed); assertTrue(exchange.outputClosed); assertEquals(exchange.output.size(),exchange.length);
    }
    @Test void concurrentRequestsDoNotMixIdentities() throws Exception {
        var first=store.create(7, 0L); var second=store.create(8, 0L);
        repository.identities.put(7,new UtenteAutenticatoResponse(7,"Paziente","Test",true,false,null));
        repository.identities.put(8,new UtenteAutenticatoResponse(8,"Desk","Test",false,false,RuoloGestionale.OPERATORE_DESK));
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<20;i++) { final var current=i%2==0?first:second; jobs.add(() -> {
                Exchange exchange=request("GET","/api/auth/me",""); sessionHeaders(exchange,current); controller.handle(exchange);
                assertEquals(200,exchange.status);
                var json=mapper.readTree(exchange.body()); assertEquals(current.idUtente(),json.get("idUtente").asInt());
                assertEquals(current.idUtente()==7,json.get("paziente").asBoolean());
                assertEquals(current.csrfToken(),exchange.responseHeaders.getFirst("X-CSRF-Token"));
                return exchange.status;
            }); }
            for(var result : executor.invokeAll(jobs)) { assertEquals(200,result.get()); }
        }
        assertEquals(2,store.size());
    }

    private static final class Exchange extends HttpExchange {
        final String method; final URI uri;
        final Headers requestHeaders=new Headers(),responseHeaders=new Headers();
        final ByteArrayOutputStream output=new ByteArrayOutputStream() {
            @Override public void close() throws IOException { outputClosed=true; super.close(); }
        };
        final InputStream input;
        boolean closed,inputClosed,outputClosed; int status; long length;
        Exchange(String method,String uri,String body) {
            this.method=method; this.uri=URI.create(uri);
            input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException { inputClosed=true; super.close(); }
            };
        }
        String body() { return output.toString(StandardCharsets.UTF_8); }
        @Override public Headers getRequestHeaders(){return requestHeaders;}
        @Override public Headers getResponseHeaders(){return responseHeaders;}
        @Override public URI getRequestURI(){return uri;}
        @Override public String getRequestMethod(){return method;}
        @Override public HttpContext getHttpContext(){return null;}
        @Override public void close(){closed=true;}
        @Override public InputStream getRequestBody(){return input;}
        @Override public OutputStream getResponseBody(){return output;}
        @Override public void sendResponseHeaders(int status,long length){this.status=status;this.length=length;}
        @Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",12345);}
        @Override public int getResponseCode(){return status;}
        @Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",8080);}
        @Override public String getProtocol(){return "HTTP/1.1";}
        @Override public Object getAttribute(String name){return null;}
        @Override public void setAttribute(String name,Object value){}
        @Override public void setStreams(InputStream input,OutputStream output){throw new UnsupportedOperationException();}
        @Override public HttpPrincipal getPrincipal(){return null;}
    }
}
