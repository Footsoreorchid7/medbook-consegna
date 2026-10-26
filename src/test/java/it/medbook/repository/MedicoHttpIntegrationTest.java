package it.medbook.repository;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.controller.MedicoController;
import it.medbook.model.StatoPrenotazione;
import it.medbook.security.*;
import it.medbook.service.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoHttpIntegrationTest {
    MedicoAbilitazioniIntegrationTest.F f;HttpServer server;HttpClient client;String origin;SessionStore store;
    SessionStore.Session admin,desk;AuthConfig config;final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void start()throws Exception{
        f=new MedicoAbilitazioniIntegrationTest.F();store=new SessionStore(Clock.systemUTC(),20);admin=store.create(f.admin, 0L);desk=store.create(f.operator, 0L);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+server.getAddress().getPort();config=new AuthConfig("local-http",origin);
        var users=new UtenteRepository(f.factory);var hasher=new PasswordHasher();
        server.createContext("/api/medici",new AutorizzazioneHandler(new MedicoController(new MedicoService(f.medici,hasher),users,mapper),new AutenticazioneService(users,hasher,store),store,config,mapper));
        server.start();client=HttpClient.newHttpClient();
    }
    @AfterEach void stop()throws Exception{try{if(client!=null)client.close();if(server!=null)server.stop(0);}finally{if(f!=null)f.close();}}
    HttpResponse<String> send(SessionStore.Session session,String method,String path,String json)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15)).header("Cookie",config.cookieName()+"="+session.id()).header("Origin",origin).header("X-CSRF-Token",session.csrfToken()).header("Content-Type","application/json");
        return client.send(b.method(method,json==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String med(int id){return "/api/medici/"+id;}
    @Test void realCreateLinkAndUpdatesPreservePrivilegesAndUseExistingTransactions()throws Exception{
        String email="f8-"+UUID.randomUUID()+"@example.test",number="F8-"+UUID.randomUUID();
        var create=send(admin,"POST","/api/medici",mapper.writeValueAsString(Map.of("nome","F8","cognome","Test","email",email,"password","password di prova F8","specializzazione","S","numeroIscrizioneOrdine",number)));
        assertEquals(201,create.statusCode(),create.body());int id=mapper.readTree(create.body()).get("idUtente").asInt();f.users.add(id);
        assertFalse(create.body().contains("password"));assertTrue(mapper.readTree(create.body()).get("ruoloGestionale").isNull());
        try(var c=f.factory.getConnection();var s=c.prepareStatement("SELECT password_hash,ruolo_gestionale FROM utente WHERE id_utente=?")){
            s.setInt(1,id);try(var r=s.executeQuery()){assertTrue(r.next());assertTrue(new PasswordHasher().verify("password di prova F8".toCharArray(),r.getString(1)));assertNull(r.getString(2));}
        }
        assertEquals(409,send(admin,"POST","/api/medici",mapper.writeValueAsString(Map.of("nome","F8","cognome","Test","email",email,"password","password di prova F8","specializzazione","S","numeroIscrizioneOrdine","other"))).statusCode());
        int user=f.user();var linked=send(admin,"POST",med(user)+"/collegamento",mapper.writeValueAsString(Map.of("specializzazione","S","numeroIscrizioneOrdine","L-"+UUID.randomUUID())));assertEquals(201,linked.statusCode(),linked.body());
        assertEquals(409,send(admin,"POST",med(user)+"/collegamento","{\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}").statusCode());
        assertEquals(409,send(admin,"POST",med(f.operator)+"/collegamento","{\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}").statusCode());
        var personal=send(admin,"PUT",med(id),mapper.writeValueAsString(Map.of("tipo","PERSONALI","nome","Updated","cognome","Test","email",email)));assertEquals(200,personal.statusCode());assertEquals("Updated",mapper.readTree(personal.body()).get("nome").asText());
        var professional=send(admin,"PUT",med(id),mapper.writeValueAsString(Map.of("tipo","PROFESSIONALI","specializzazione","Updated S","numeroIscrizioneOrdine",number)));assertEquals(200,professional.statusCode());assertEquals("Updated S",mapper.readTree(professional.body()).get("specializzazione").asText());
        assertEquals("Updated",f.medici.dettaglioPerAmministratore(f.admin,id).nome());
    }
    @Test void stateAbilitiesAndPrivacyEndToEnd()throws Exception{
        int m=f.doctor(),p=f.prestazione();
        for(int i=0;i<2;i++)assertEquals(200,send(admin,"PUT",med(m)+"/stato","{\"stato\":\"INATTIVO\"}").statusCode());
        String payload="{\"operazione\":\"ASSEGNA\",\"idPrestazione\":"+p+"}";
        assertEquals(200,send(admin,"PUT",med(m)+"/prestazioni",payload).statusCode());assertEquals(1,f.count(m,p));assertEquals(409,send(admin,"PUT",med(m)+"/prestazioni",payload).statusCode());
        var list=send(desk,"GET","/api/medici?stato=INATTIVO&idPrestazione="+p,null);assertEquals(200,list.statusCode());assertEquals(1,mapper.readTree(list.body()).size());assertEquals(m,mapper.readTree(list.body()).get(0).get("idUtente").asInt());
        var detail=mapper.readTree(send(desk,"GET",med(m),null).body());assertEquals(6,detail.get("medico").size());assertFalse(detail.get("medico").has("email"));assertEquals(p,detail.get("prestazioniAutorizzate").get(0).get("idPrestazione").asInt());
        assertEquals(200,send(admin,"PUT",med(m)+"/prestazioni",payload.replace("ASSEGNA","RIMUOVI")).statusCode());assertEquals(0,f.count(m,p));assertEquals(409,send(admin,"PUT",med(m)+"/prestazioni",payload.replace("ASSEGNA","RIMUOVI")).statusCode());
        assertEquals(403,send(desk,"PUT",med(m)+"/stato","{\"stato\":\"ATTIVO\"}").statusCode());
    }
    @Test void realDependenciesAndNoncanonicalStateRemainControlledConflicts()throws Exception{
        int b=f.booking(StatoPrenotazione.RICHIESTA,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(2));var before=f.snapshot(b);
        assertEquals(409,send(admin,"PUT",med(f.owner)+"/stato","{\"stato\":\"INATTIVO\"}").statusCode());
        assertEquals(409,send(admin,"PUT",med(f.owner)+"/prestazioni","{\"operazione\":\"RIMUOVI\",\"idPrestazione\":"+f.prestazione+"}").statusCode());assertEquals(before,f.snapshot(b));
        f.execute("UPDATE medico SET stato='attivo' WHERE id_utente=?",f.owner);
        assertEquals(200,send(admin,"GET",med(f.owner),null).statusCode());assertEquals(409,send(desk,"GET",med(f.owner),null).statusCode());
        assertEquals(404,send(admin,"GET",med(Integer.MAX_VALUE),null).statusCode());
    }
    @Test void actualNamedMutexTimeoutReturns503RetryAfterWithoutMutation()throws Exception{
        var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",f.owner);
        try(var c=f.factory.getConnection();var s=c.prepareStatement("SELECT GET_LOCK(?,0)")) {
            String name=AccountMutex.lockName(c.getCatalog());s.setString(1,name);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}
            try {
                var response=send(admin,"PUT",med(f.owner)+"/stato","{\"stato\":\"INATTIVO\"}");assertEquals(503,response.statusCode(),response.body());assertEquals("5",response.headers().firstValue("Retry-After").orElseThrow());assertFalse(response.body().contains(name));assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",f.owner));
            }finally{try(var release=c.prepareStatement("SELECT RELEASE_LOCK(?)")){release.setString(1,name);try(var r=release.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));}}}
        }
    }
    @Test void roleChangesOnExistingSessionsAreEffective()throws Exception{
        assertEquals(200,send(admin,"GET","/api/medici",null).statusCode());
        f.execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?",f.admin);
        assertEquals(403,send(admin,"PUT",med(f.owner)+"/stato","{\"stato\":\"ATTIVO\"}").statusCode());
        var response=send(admin,"GET","/api/medici",null);assertEquals(200,response.statusCode());for(var item:mapper.readTree(response.body()))assertFalse(item.has("email"));
        store.revoke(admin.id());assertEquals(401,send(admin,"GET","/api/medici",null).statusCode());
    }
}
