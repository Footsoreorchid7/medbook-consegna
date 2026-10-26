package it.medbook.controller;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.*;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PazienteDeskControllerTest {
    static class Repo extends PazienteDeskRepository {
        int writes,reads,links;Filtri filters;String cf,tel;
        Repo(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        @Override public void collega(int a,int id,String cf,java.time.LocalDate d,String tel){assertEquals(7,a);assertEquals(9,id);links++;this.cf=cf;this.tel=tel;}
        @Override public long reimpostaPassword(int a,int id,String hash){assertEquals(7,a);assertEquals(9,id);assertTrue(hash.startsWith("pbkdf2"));writes++;return 1;}
        @Override public ElencoPazientiDeskResponse cerca(int a,Filtri f){reads++;filters=f;return new ElencoPazientiDeskResponse(List.of(new PazienteDeskResponse(9,"P","Test","synthetic@example.test",false,false,"ATTIVO")),null);}
    }
    ObjectMapper mapper=new ObjectMapper();FakeUtenteRepository identity;Repo data;SessionStore store;SessionStore.Session session;
    HttpServer server;HttpClient client;String origin,cookie;AuthConfig config;
    @BeforeEach void start()throws Exception{
        identity=new FakeUtenteRepository();role(RuoloGestionale.OPERATORE_DESK);store=new SessionStore(java.time.Clock.systemUTC(),10);session=store.create(7,0);data=new Repo();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+server.getAddress().getPort();config=new AuthConfig("local-http",origin);cookie=config.cookieName()+"="+session.id();
        server.createContext("/api/pazienti",new AutorizzazioneHandler(new PazienteDeskController(new PazienteDeskService(data,new PasswordHasher(),store),mapper),new AutenticazioneService(identity,new PasswordHasher(),store),store,config,mapper));server.start();client=HttpClient.newHttpClient();
    }
    void role(RuoloGestionale r){identity.identity=new UtenteAutenticatoResponse(7,"N","C",false,false,r);}
    @AfterEach void stop(){if(client!=null)client.close();if(server!=null)server.stop(0);}
    HttpResponse<String> request(String method,String path,byte[] body,String type,String auth,String csrf,String requestOrigin)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(origin+path)).timeout(java.time.Duration.ofSeconds(15));
        if(type!=null)b.header("Content-Type",type);if(auth!=null)b.header("Cookie",auth);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(requestOrigin!=null)b.header("Origin",requestOrigin);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> send(String m,String p,String body)throws Exception{return request(m,p,body==null?null:body.getBytes(StandardCharsets.UTF_8),"application/json",cookie,session.csrfToken(),origin);}
    @Test void exactContractAndNoSecrets()throws Exception{
        var r=send("PUT","/api/pazienti/9/password","{\"nuovaPassword\":\" password paziente \"}");assertEquals(200,r.statusCode());
        assertEquals(3,mapper.readTree(r.body()).size());assertEquals(9,mapper.readTree(r.body()).get("idPaziente").intValue());assertTrue(mapper.readTree(r.body()).get("sessioniInvalidate").booleanValue());assertFalse(r.body().contains("password paziente"));assertEquals(1,data.writes);
        var list=send("GET","/api/pazienti",null);assertEquals(200,list.statusCode());assertFalse(list.body().contains("codiceFiscale"));assertFalse(list.body().contains("password"));
        assertEquals(200,send("GET","/api/pazienti?nome=Ana&email=NINA%40EXAMPLE.TEST&dopoId=8&limite=10",null).statusCode());assertEquals("nina@example.test",data.filters.email());assertEquals(8,data.filters.dopoId());
    }
    @Test void roleSessionOriginAndCsrf()throws Exception{
        String p="/api/pazienti/9/password",body="{\"nuovaPassword\":\"password paziente\"}";
        assertEquals(401,request("PUT",p,body.getBytes(),"application/json",null,null,origin).statusCode());
        assertEquals(403,request("PUT",p,body.getBytes(),"application/json",cookie,null,origin).statusCode());
        assertEquals(403,request("PUT",p,body.getBytes(),"application/json",cookie,session.csrfToken(),"http://wrong.invalid").statusCode());
        for(var role:Arrays.asList(RuoloGestionale.AMMINISTRATORE,null)){role(role);assertEquals(403,send("PUT",p,body).statusCode());assertEquals(403,send("GET","/api/pazienti",null).statusCode());}assertEquals(0,data.writes);
    }
    @Test void strictPathsQueriesBodiesAndMethods()throws Exception{
        for(String p:List.of("/api/pazienti/09/password","/api/pazienti/%39/password","/api/pazienti/9/password/","/api/pazienti/2147483648/password"))assertEquals(404,send("PUT",p,"{}").statusCode());
        assertEquals(405,send("POST","/api/pazienti", "{}").statusCode());assertEquals(405,send("GET","/api/pazienti/9/password",null).statusCode());
        for(String q:List.of("?nome=A&%6eome=B","?nome=","?nome=%FF","?ruolo=NESSUNO","?limite=51","?idPaziente=01"))assertEquals(400,send("GET","/api/pazienti"+q,null).statusCode(),q);
        for(String body:List.of("{}","[]","{\"nuovaPassword\":12}","{\"nuovaPassword\":\"1234567\"}","{\"nuovaPassword\":\"12345678\",\"ripetizione\":\"12345678\"}","{\"nuovaPassword\":\"12345678\",\"nuovaPassword\":\"87654321\"}"))assertEquals(400,send("PUT","/api/pazienti/9/password",body).statusCode());
        /* HttpClient elimina il punto interrogativo isolato: il parser raw viene verificato direttamente. */
        assertThrows(IllegalArgumentException.class,()->UtenteController.parametri(""));
        assertEquals(415,request("PUT","/api/pazienti/9/password","{}".getBytes(),"text/plain",cookie,session.csrfToken(),origin).statusCode());
        assertEquals(413,send("PUT","/api/pazienti/9/password"," ".repeat(8193)).statusCode());assertEquals(0,data.writes);
    }
    @Test void collegamentoExactContractPermissionsAndValidation()throws Exception {
        String path="/api/pazienti/9/collegamento", body="{\"codiceFiscale\":\"tsttst90a01a000q\",\"dataNascita\":\"1990-01-01\"}";
        var result=send("POST",path,body);assertEquals(201,result.statusCode());assertEquals(3,mapper.readTree(result.body()).size());assertEquals(9,mapper.readTree(result.body()).get("idPaziente").intValue());assertTrue(mapper.readTree(result.body()).get("paziente").booleanValue());assertFalse(result.body().contains("codiceFiscale"));assertEquals("TSTTST90A01A000Q",data.cf);assertNull(data.tel);
        assertEquals(405,send("PUT",path,body).statusCode());assertEquals(400,send("POST",path+"?x=1",body).statusCode());
        for(String invalid:List.of("{}",body.replace("1990-01-01","1990-02-30"),body.replace("}",",\"idUtente\":9}"),body.replace("}",",\"telefono\":12}")))assertEquals(400,send("POST",path,invalid).statusCode());
        assertEquals(404,send("POST","/api/pazienti/09/collegamento",body).statusCode());
        assertEquals(403,request("POST",path,body.getBytes(),"application/json",cookie,null,origin).statusCode());
        assertEquals(403,request("POST",path,body.getBytes(),"application/json",cookie,session.csrfToken(),"http://wrong.invalid").statusCode());
        assertEquals(401,request("POST",path,body.getBytes(),"application/json",null,null,origin).statusCode());
        role(RuoloGestionale.AMMINISTRATORE);assertEquals(403,send("POST",path,body).statusCode());role(null);assertEquals(403,send("POST",path,body).statusCode());assertEquals(1,data.links);
    }

}
