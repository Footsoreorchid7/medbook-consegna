package it.medbook.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.service.*;
import it.medbook.security.*;
import it.medbook.dto.*;
import it.medbook.model.*;
import it.medbook.support.*;
import it.medbook.config.AuthConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class UtenteControllerTest {
    final UtenteServiceTest.Repo repo=new UtenteServiceTest.Repo();
    final ObjectMapper mapper=new ObjectMapper();
    final UtenteController controller=new UtenteController(new UtenteService(repo,new PasswordHasher()),mapper);
    static final String BODY="{\"nome\":\"Nome\",\"cognome\":\"Cognome\",\"email\":\"e@example.invalid\",\"password\":\"test-only-password\",\"ruoloGestionale\":\"OPERATORE_DESK\"}";
    TestHttpExchange call(String method,String path,String body)throws Exception {
        var e=new AuthenticatedTestExchange(method,path,body,7);controller.handle(e);assertTrue(e.exchange.closed);return e.exchange;
    }
    @Test void dtoSuccessAndEmptyRead()throws Exception {
        assertEquals("[]",call("GET","/api/utenti","").body());var post=call("POST","/api/utenti",BODY);assertEquals(201,post.status);
        var json=mapper.readTree(post.body());assertEquals(8,json.size());assertEquals("ATTIVO",json.get("statoAccesso").asText());assertEquals(9,json.get("idUtente").intValue());assertFalse(post.body().contains("password"));assertFalse(post.body().contains("test-only"));
        for(String role:new String[]{"AMMINISTRATORE","OPERATORE_DESK"})assertEquals(200,call("PUT","/api/utenti/9/ruolo","{\"ruoloGestionale\":\""+role+"\"}").status);
    }
    @ParameterizedTest @ValueSource(strings={"","null","[]","{}","{","{\"ruoloGestionale\":null}","{\"ruoloGestionale\":7}","{\"ruoloGestionale\":\"MEDICO\"}","{\"ruoloGestionale\":\"AMMINISTRATORE\",\"idUtente\":7}","{\"ruoloGestionale\":\"AMMINISTRATORE\",\"ruoloGestionale\":\"OPERATORE_DESK\"}","{\"ruoloGestionale\":\"AMMINISTRATORE\"} {}"})
    void strictRoleJson(String body)throws Exception{assertEquals(400,call("PUT","/api/utenti/9/ruolo",body).status);assertEquals(0,repo.calls);}
    @Test void postStrictnessUnicodeAndByteBoundary()throws Exception {
        for(String body:new String[]{BODY.replace("\"Nome\"","7"),BODY.replace("\"Nome\"","null"),BODY.replace("\"Nome\"","\"\\uD800\""),BODY.replace("\"Cognome\"","\"\\uDC00\""),BODY.replace("\"test-only-password\"","\"test-only-1\""),BODY.replace("\"test-only-password\"","\"\\uD800\""),BODY.substring(0,BODY.length()-1)+",\"extra\":1}",BODY+" {}"})assertEquals(400,call("POST","/api/utenti",body).status);
        assertEquals(0,repo.calls);
        String exact=BODY+" ".repeat(8192-BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(201,call("POST","/api/utenti",exact).status);assertEquals(400,call("POST","/api/utenti",exact+" ").status);assertEquals(1,repo.calls);
        var e=new AuthenticatedTestExchange("POST","/api/utenti","",7);e.setStreams(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),e.exchange.output);controller.handle(e);assertEquals(400,e.exchange.status);
    }
    @Test void routingContentTypeAndErrors()throws Exception {
        for(String path:new String[]{"/api/utenti/0/ruolo","/api/utenti/01/ruolo","/api/utenti/2147483648/ruolo","/api/utenti/%39/ruolo","/api/utenti/9/ruolo/","/api/utenti/extra"})assertEquals(404,call("PUT",path,"{}").status);
        var e=call("GET","/api/utenti/9/ruolo","");assertEquals(405,e.status);assertEquals("PUT",e.responseHeaders.getFirst("Allow"));
        e=call("DELETE","/api/utenti","");assertEquals(405,e.status);assertEquals("GET, POST",e.responseHeaders.getFirst("Allow"));assertEquals(400,call("GET","/api/utenti?","").status);
        var type=new AuthenticatedTestExchange("POST","/api/utenti",BODY,7);type.getRequestHeaders().set("Content-Type","text/plain");controller.handle(type);assertEquals(415,type.exchange.status);
        for(var error:java.util.List.of(new SQLException("secret"),new it.medbook.exception.RisorsaNonTrovataException("Utente non trovato."),new it.medbook.exception.OperazioneNonAutorizzataException("forbidden"),new it.medbook.repository.UtenteRepository.ConflittoAccountException("conflict"))) {
            repo.failure=error;int status=error instanceof SQLException?500:error instanceof it.medbook.exception.RisorsaNonTrovataException?404:error instanceof it.medbook.exception.OperazioneNonAutorizzataException?403:409;
            e=call("PUT","/api/utenti/9/ruolo","{\"ruoloGestionale\":\"AMMINISTRATORE\"}");assertEquals(status,e.status);assertFalse(e.body().contains("secret"));
        }
    }
    @Test void authorizationBeforeBodyRolesCsrfRevocationAndExpiry()throws Exception {
        var users=new FakeUtenteRepository();var clock=new MutableClock();var sessions=new SessionStore(clock,10);
        var auth=new AutenticazioneService(users,new PasswordHasher(),sessions);var config=new AuthConfig("local-http","http://localhost:8080");
        var handler=new AutorizzazioneHandler(controller,auth,sessions,config,mapper);
        var anon=new TestHttpExchange("POST","/api/utenti","broken");handler.handle(anon);assertEquals(401,anon.status);
        var session=sessions.create(7, 0L);
        for(var role:new RuoloGestionale[]{null,RuoloGestionale.OPERATORE_DESK,RuoloGestionale.AMMINISTRATORE}) {
            users.identity=new UtenteAutenticatoResponse(7,"N","C",true,true,role);
            var e=new TestHttpExchange("POST","/api/utenti",BODY);e.requestHeaders.set("Cookie",config.cookieName()+"="+session.id());e.requestHeaders.set("Origin",config.origin());e.requestHeaders.set("X-CSRF-Token",session.csrfToken());handler.handle(e);
            assertEquals(role==RuoloGestionale.AMMINISTRATORE?201:403,e.status);
        }
        for(boolean wrongOrigin:new boolean[]{false,true}) {
            var e=new TestHttpExchange("PUT","/api/utenti/9/ruolo","broken");e.requestHeaders.set("Cookie",config.cookieName()+"="+session.id());e.requestHeaders.set("Origin",wrongOrigin?"http://invalid.example":config.origin());
            if(wrongOrigin)e.requestHeaders.set("X-CSRF-Token",session.csrfToken());handler.handle(e);assertEquals(403,e.status);
        }
        var expired=sessions.create(7, 0L);clock.advance(java.time.Duration.ofMinutes(30));var expiredRequest=new TestHttpExchange("GET","/api/utenti","");expiredRequest.requestHeaders.set("Cookie",config.cookieName()+"="+expired.id());handler.handle(expiredRequest);assertEquals(401,expiredRequest.status);
        sessions.revoke(session.id());var e=new TestHttpExchange("GET","/api/utenti","");e.requestHeaders.set("Cookie",config.cookieName()+"="+session.id());handler.handle(e);assertEquals(401,e.status);
    }
    @Test void lockTimeoutIsRetryable503WithoutDetails()throws Exception {
        repo.failure=new it.medbook.repository.UtenteRepository.LockAccountTimeoutException();
        for(String[] request:new String[][]{{"POST","/api/utenti",BODY},{"PUT","/api/utenti/9/ruolo","{\"ruoloGestionale\":\"AMMINISTRATORE\"}"}}) {
            var e=call(request[0],request[1],request[2]);assertEquals(503,e.status);assertEquals("5",e.responseHeaders.getFirst("Retry-After"));
            assertEquals("{\"error\":\"Servizio temporaneamente non disponibile.\"}",e.body());
        }
        /* Retry-After riguarda il timeout del lock; un guasto SQL ordinario resta 500. */
        repo.failure=new SQLException("secret");var e=call("PUT","/api/utenti/9/ruolo","{\"ruoloGestionale\":\"AMMINISTRATORE\"}");
        assertEquals(500,e.status);assertNull(e.responseHeaders.getFirst("Retry-After"));
    }
    @Test void unexpectedRuntimeExceptionsBecomeGeneric500()throws Exception {
        for(RuntimeException failure:new RuntimeException[]{new IllegalStateException("secret-state"),new NullPointerException("secret-npe"),new UnsupportedOperationException("secret-op")}) {
            repo.failure=failure;
            for(String[] request:new String[][]{{"GET","/api/utenti",""},{"POST","/api/utenti",BODY},{"PUT","/api/utenti/9/ruolo","{\"ruoloGestionale\":\"OPERATORE_DESK\"}"}}) {
                var e=call(request[0],request[1],request[2]);assertEquals(500,e.status);assertEquals("{\"error\":\"Errore interno del server.\"}",e.body());
                assertFalse(e.body().contains("secret"));assertTrue(e.closed);
            }
        }
    }
    @Test void runtimeFailureAfterHeadersNeverSendsSecondResponse()throws Exception {
        var inner=new AuthenticatedTestExchange("GET","/api/utenti","",7);int[] headers={0};
        var broken=new HttpExchangeDelegate(inner){
            @Override public void sendResponseHeaders(int code,long length)throws IOException{headers[0]++;super.sendResponseHeaders(code,length);}
            @Override public OutputStream getResponseBody(){return new OutputStream(){@Override public void write(int b){throw new IllegalStateException("secret-stream");}
                @Override public void write(byte[] b,int off,int len){throw new IllegalStateException("secret-stream");}};}
        };
        controller.handle(broken);assertEquals(1,headers[0]);assertEquals(200,inner.exchange.status);assertTrue(inner.exchange.closed);assertEquals("",inner.exchange.body());
    }
    @Test void realHttpServerGetAuthorizationByRoleAndSession()throws Exception {
        var users=new FakeUtenteRepository();var clock=new MutableClock();var sessions=new SessionStore(clock,10);
        var auth=new AutenticazioneService(users,new PasswordHasher(),sessions);
        users.identities.put(1,new UtenteAutenticatoResponse(1,"A","Admin",false,false,RuoloGestionale.AMMINISTRATORE));
        users.identities.put(2,new UtenteAutenticatoResponse(2,"D","Desk",false,false,RuoloGestionale.OPERATORE_DESK));
        users.identities.put(3,new UtenteAutenticatoResponse(3,"P","Paziente",true,false,null));
        users.identities.put(4,new UtenteAutenticatoResponse(4,"M","Medico",false,true,null));
        users.identities.put(5,new UtenteAutenticatoResponse(5,"AM","Admin-medico",false,true,RuoloGestionale.AMMINISTRATORE));
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();var config=new AuthConfig("local-http",origin);
        /* Stesso collegamento applicativo: autorizzazione davanti al controller utenti. */
        server.createContext("/api/utenti",new AutorizzazioneHandler(controller,auth,sessions,config,mapper));server.start();
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            java.util.function.BiFunction<String,String,java.net.http.HttpResponse<String>> get=(path,sessionId)->{
                var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+path)).GET();
                if(sessionId!=null)builder.header("Cookie",config.cookieName()+"="+sessionId);
                try{return client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());}catch(Exception x){throw new AssertionError(x);}
            };
            for(int id=1;id<=5;id++) {
                var response=get.apply("/api/utenti",sessions.create(id, 0L).id());
                assertEquals(id==1||id==5?200:403,response.statusCode(),"utente "+id);
                if(id==1||id==5)assertEquals("[]",response.body());else assertEquals("{\"error\":\"Operazione non autorizzata.\"}",response.body());
            }
            assertEquals(401,get.apply("/api/utenti",null).statusCode());
            assertEquals(404,get.apply("/api/utenti/1",sessions.create(1, 0L).id()).statusCode());
            var revoked=sessions.create(1, 0L);sessions.revoke(revoked.id());assertEquals(401,get.apply("/api/utenti",revoked.id()).statusCode());
            var expired=sessions.create(1, 0L);clock.advance(java.time.Duration.ofMinutes(30));assertEquals(401,get.apply("/api/utenti",expired.id()).statusCode());
            assertEquals(2,repo.calls);
        }finally{server.stop(0);}
    }
    /* Delega ogni chiamata all'exchange originale; i test sovrascrivono singoli metodi. */
    static class HttpExchangeDelegate extends com.sun.net.httpserver.HttpExchange {
        final com.sun.net.httpserver.HttpExchange d;
        HttpExchangeDelegate(com.sun.net.httpserver.HttpExchange d){this.d=d;}
        @Override public com.sun.net.httpserver.Headers getRequestHeaders(){return d.getRequestHeaders();}
        @Override public com.sun.net.httpserver.Headers getResponseHeaders(){return d.getResponseHeaders();}
        @Override public java.net.URI getRequestURI(){return d.getRequestURI();}
        @Override public String getRequestMethod(){return d.getRequestMethod();}
        @Override public com.sun.net.httpserver.HttpContext getHttpContext(){return d.getHttpContext();}
        @Override public void close(){d.close();}
        @Override public InputStream getRequestBody(){return d.getRequestBody();}
        @Override public OutputStream getResponseBody(){return d.getResponseBody();}
        @Override public void sendResponseHeaders(int code,long length)throws IOException{d.sendResponseHeaders(code,length);}
        @Override public java.net.InetSocketAddress getRemoteAddress(){return d.getRemoteAddress();}
        @Override public int getResponseCode(){return d.getResponseCode();}
        @Override public java.net.InetSocketAddress getLocalAddress(){return d.getLocalAddress();}
        @Override public String getProtocol(){return d.getProtocol();}
        @Override public Object getAttribute(String name){return d.getAttribute(name);}
        @Override public void setAttribute(String name,Object value){d.setAttribute(name,value);}
        @Override public void setStreams(InputStream i,OutputStream o){d.setStreams(i,o);}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal(){return d.getPrincipal();}
    }
}
