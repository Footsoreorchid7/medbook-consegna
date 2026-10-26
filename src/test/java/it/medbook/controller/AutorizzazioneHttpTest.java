package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.FakeUtenteRepository;
import it.medbook.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Listener effimero e repository simulato: nessuna connessione MySQL. */
class AutorizzazioneHttpTest {
    @Test void clinicalContextsRequireSessionCsrfAndExactRouting() throws Exception {
        var mapper=new ObjectMapper(); var repository=new FakeUtenteRepository();
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,true,null);
        var store=new SessionStore(new MutableClock(),10); var session=store.create(7, 0L);
        var auth=new AutenticazioneService(repository,new PasswordHasher(),store);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        var config=new AuthConfig("local-http",origin);
        HttpHandler delegate=exchange -> {
            byte[] response=mapper.writeValueAsBytes(java.util.Map.of(
                    "principal",exchange.getPrincipal()==null?"public":exchange.getPrincipal().getUsername(),
                    "body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200,response.length);
            try(var output=exchange.getResponseBody()){output.write(response);}
        };
        for(String context:java.util.List.of("/api/prestazioni","/api/disponibilita","/api/prenotazioni")){
            server.createContext(context,new AutorizzazioneHandler(delegate,auth,store,config,mapper));
        }
        server.start();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
            assertEquals(200,send(client,origin,"GET","/api/prestazioni",null,null,null,null).statusCode());
            assertEquals(200,send(client,origin,"GET","/api/disponibilita/prenotabili",null,null,null,null).statusCode());
            assertEquals(401,send(client,origin,"GET","/api/prenotazioni/paziente?idPaziente=7",null,null,null,null).statusCode());
            String cookie=config.cookieName()+"="+session.id();
            assertEquals(403,send(client,origin,"GET","/api/prenotazioni/paziente?idPaziente=8",null,cookie,null,null).statusCode());
            assertEquals(403,send(client,origin,"POST","/api/prenotazioni","{\"idPaziente\":7}",cookie,null,origin).statusCode());
            assertEquals(403,send(client,origin,"POST","/api/prenotazioni","{\"idPaziente\":7}",cookie,session.csrfToken(),"http://example.invalid").statusCode());
            String body="{\"idPaziente\":7,\"idDisponibilita\":90}";
            var valid=send(client,origin,"POST","/api/prenotazioni",body,cookie,session.csrfToken(),origin);
            assertEquals(200,valid.statusCode());var json=mapper.readTree(valid.body());
            assertEquals("7",json.get("principal").asText());assertEquals(body,json.get("body").asText());
            assertEquals(403,send(client,origin,"GET","/api/prenotazioni/richieste",null,cookie,null,null).statusCode());
            for(String path:java.util.List.of("/api/prestazioni/extra","/api/disponibilita/prenotabili/extra","/api/prenotazioni/7/conferma/extra")){
                assertEquals(404,send(client,origin,"GET",path,null,cookie,null,null).statusCode());
            }
            var wrong=send(client,origin,"PUT","/api/prenotazioni",null,null,null,null);
            assertEquals(405,wrong.statusCode());assertEquals("POST",wrong.headers().firstValue("Allow").orElseThrow());
            store.revoke(session.id());
            assertEquals(401,send(client,origin,"GET","/api/prenotazioni/paziente?idPaziente=7",null,cookie,null,null).statusCode());
        }finally{server.stop(0);}
    }
    @Test void catalogNumericResourceHasPutOnlyWhileUnknownPathsStay404() throws Exception {
        var mapper=new ObjectMapper();var repository=new FakeUtenteRepository();
        var store=new SessionStore(new MutableClock(),10);
        var auth=new AutenticazioneService(repository,new PasswordHasher(),store);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        var config=new AuthConfig("local-http",origin);
        server.createContext("/api/prestazioni",new AutorizzazioneHandler(exchange -> fail("Unrecognized or unauthorized request delegated"),auth,store,config,mapper));
        server.start();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for(String path:java.util.List.of("/api/prestazioni/7","/api/prestazioni/2147483647")) {
                var response=send(client,origin,"GET",path,null,null,null,null);
                assertEquals(405,response.statusCode());assertEquals("PUT",response.headers().firstValue("Allow").orElseThrow());
                assertEquals(401,send(client,origin,"PUT",path,"{}",null,null,origin).statusCode());
            }
            for(String path:java.util.List.of("/api/prestazioni/extra","/api/prestazioni/0","/api/prestazioni/-1","/api/prestazioni/01","/api/prestazioni/2147483648","/api/prestazioni/7/extra","/api/prestazioni/7/","/api/prestazioni/%37")) {
                for(String method:java.util.List.of("GET","PUT")) {
                    var response=send(client,origin,method,path,null,null,null,null);
                    assertEquals(404,response.statusCode(),path);assertTrue(response.headers().firstValue("Allow").isEmpty());
                }
            }
        }finally{server.stop(0);}
    }
    @Test void roomsArePrivateAndUseExactRouting() throws Exception {
        var mapper=new ObjectMapper();var repository=new FakeUtenteRepository();
        var store=new SessionStore(new MutableClock(),10);var session=store.create(7, 0L);
        var auth=new AutenticazioneService(repository,new PasswordHasher(),store);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();var config=new AuthConfig("local-http",origin);
        var data=new StanzaControllerTest.Repo();
        server.createContext("/api/stanze",new AutorizzazioneHandler(new StanzaController(
                new it.medbook.service.StanzaService(data),mapper),auth,store,config,mapper));
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            String cookie=config.cookieName()+"="+session.id();
            assertEquals(401,send(client,origin,"GET","/api/stanze",null,null,null,null).statusCode());
            repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,true,null);
            assertEquals(403,send(client,origin,"GET","/api/stanze",null,cookie,null,null).statusCode());
            repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",false,false,it.medbook.model.RuoloGestionale.OPERATORE_DESK);
            assertEquals(200,send(client,origin,"GET","/api/stanze",null,cookie,null,null).statusCode());
            assertEquals(403,send(client,origin,"POST","/api/stanze","malformed",cookie,session.csrfToken(),origin).statusCode());
            repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,true,it.medbook.model.RuoloGestionale.AMMINISTRATORE);
            assertEquals(200,send(client,origin,"GET","/api/stanze",null,cookie,null,null).statusCode());
            assertEquals(403,send(client,origin,"POST","/api/stanze","{}",cookie,null,origin).statusCode());
            assertEquals(403,send(client,origin,"PUT","/api/stanze/7","{}",cookie,session.csrfToken(),"http://invalid.example").statusCode());
            var wrong=send(client,origin,"GET","/api/stanze/7",null,null,null,null);
            assertEquals(405,wrong.statusCode());assertEquals("PUT",wrong.headers().firstValue("Allow").orElseThrow());
            for(String path:java.util.List.of("/api/stanze/extra","/api/stanze/0","/api/stanze/2147483648","/api/stanze/7/extra","/api/stanze/%37","/api/stanze/")) {
                assertEquals(404,send(client,origin,"GET",path,null,cookie,null,null).statusCode());
            }
            assertEquals(0,data.writes);store.revoke(session.id());
            assertEquals(401,send(client,origin,"GET","/api/stanze",null,cookie,null,null).statusCode());
        } finally {server.stop(0);}
    }
    @Test void administratorCreatesAndUpdatesRoomThroughAuthenticatedHttpPipeline() throws Exception {
        var mapper=new ObjectMapper();var repository=new FakeUtenteRepository();
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,true,it.medbook.model.RuoloGestionale.AMMINISTRATORE);
        var store=new SessionStore(new MutableClock(),10);var session=store.create(7, 0L);
        var auth=new AutenticazioneService(repository,new PasswordHasher(),store);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();var config=new AuthConfig("local-http",origin);
        var data=new StanzaControllerTest.Repo();
        server.createContext("/api/stanze",new AutorizzazioneHandler(new StanzaController(
                new it.medbook.service.StanzaService(data),mapper),auth,store,config,mapper));
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            String cookie=config.cookieName()+"="+session.id();
            for(String method:java.util.List.of("POST","PUT")) {
                String path=method.equals("POST")?"/api/stanze":"/api/stanze/7";
                String body=method.equals("POST")?"{\"nome\":\" Sala \",\"descrizione\":\" testo \"}":"{\"nome\":\"Sala aggiornata\"}";
                int reads=repository.idCalls;
                var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(10))
                        .header("Cookie",cookie).header("Origin",origin).header("X-CSRF-Token",session.csrfToken())
                        .header("Content-Type","application/json; charset=UTF-8")
                        .method(method,HttpRequest.BodyPublishers.ofString(body)).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(method.equals("POST")?201:200,response.statusCode());
                assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
                var json=mapper.readTree(response.body());assertEquals(3,json.size());assertEquals(7,json.get("idStanza").intValue());
                assertEquals(method.equals("POST")?"Sala":"Sala aggiornata",json.get("nome").textValue());
                if(method.equals("POST"))assertEquals(" testo ",json.get("descrizione").textValue());else assertTrue(json.get("descrizione").isNull());
                assertEquals(reads+1,repository.idCalls);assertEquals(7,repository.requestedId);
            }
            assertEquals(2,data.writes);assertEquals(7,data.id);assertNull(data.input.getDescrizione());
            /* La sessione resta, ma i permessi vengono riletti a ogni richiesta. */
            repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,true,it.medbook.model.RuoloGestionale.OPERATORE_DESK);
            assertEquals(403,send(client,origin,"PUT","/api/stanze/7","{}",cookie,session.csrfToken(),origin).statusCode());
            assertEquals(2,data.writes);
        }finally{server.stop(0);}
    }
    private HttpResponse<String> send(HttpClient client,String base,String method,String path,String body,
                                      String cookie,String csrf,String origin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(cookie!=null){request.header("Cookie",cookie);}if(csrf!=null){request.header("X-CSRF-Token",csrf);}
        if(origin!=null){request.header("Origin",origin);}
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
