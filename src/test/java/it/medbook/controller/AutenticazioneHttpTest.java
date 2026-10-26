package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Server loopback effimero, nessun MySQL; stop garantito al termine del test. */
class AutenticazioneHttpTest {
    @Test void loginMeCsrfLogoutAndRoutingWorkThroughHttpServer() throws Exception {
        ObjectMapper mapper=new ObjectMapper(); PasswordHasher hasher=new PasswordHasher();
        FakeUtenteRepository repository=new FakeUtenteRepository();
        repository.identity=new UtenteAutenticatoResponse(7,"Fixture","HTTP",true,false,null);
        repository.account=new UtenteRepository.Credenziali(repository.identity,hasher.hash("Solo-test-http".toCharArray()));
        MutableClock clock=new MutableClock(); SessionStore sessions=new SessionStore(clock,10);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        AuthConfig config=new AuthConfig("local-http",origin);
        server.createContext("/api/auth",new AutenticazioneController(
                new AutenticazioneService(repository,hasher,sessions),sessions,new LoginThrottle(clock,10,10),config,mapper));
        server.start();
        try(HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String json=mapper.writeValueAsString(Map.of("email","http@example.invalid","password","Solo-test-http"));
            var login=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/login"))
                    .timeout(Duration.ofSeconds(10)).header("Origin",origin).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,login.statusCode()); assertEquals(7,mapper.readTree(login.body()).get("idUtente").asInt());
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            String csrf=login.headers().firstValue("X-CSRF-Token").orElseThrow();
            var me=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/me")).header("Cookie",cookie)
                    .GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,me.statusCode()); assertEquals("no-store",me.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(csrf,me.headers().firstValue("X-CSRF-Token").orElseThrow()); assertFalse(me.body().contains("password"));
            var rejected=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/logout"))
                    .header("Cookie",cookie).header("Origin",origin).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,rejected.statusCode()); assertEquals(1,sessions.size());
            var logout=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/logout"))
                    .header("Cookie",cookie).header("Origin",origin).header("X-CSRF-Token",csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,logout.statusCode()); assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
            var revoked=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/me")).header("Cookie",cookie)
                    .GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(401,revoked.statusCode());
            var unknown=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/me/extra")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(404,unknown.statusCode());
            var wrong=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/login")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(405,wrong.statusCode()); assertEquals("POST",wrong.headers().firstValue("Allow").orElseThrow());
        } finally { server.stop(0); }
    }
}
