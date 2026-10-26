package it.medbook.controller;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.exception.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.*;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoControllerTest {
    static class Repo extends MedicoRepository {
        int calls;String operation;Object[] arguments;RuntimeException failure;SQLException sql;Runnable onCall;
        final MedicoResponse admin=new MedicoResponse(9,"N","C","private@example.test","S","O","ATTIVO",true,null);
        final MedicoDeskResponse desk=new MedicoDeskResponse(9,"N","C","S","O","ATTIVO");
        Repo(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        void call(String op,Object...args)throws SQLException{calls++;operation=op;arguments=args;if(onCall!=null)onCall.run();if(failure!=null)throw failure;if(sql!=null)throw sql;}
        @Override public boolean profiloPazientePerDesk(int a,int id)throws SQLException {call("patientFlag",a,id);return false;}
        @Override public long reimpostaPassword(int a,int id,String h)throws SQLException{call("reset",a,id);assertTrue(h.startsWith("pbkdf2"));return 1L;}
        @Override public MedicoResponse crea(int a,String n,String c,String e,String h,String s,String o)throws SQLException{call("create",a,n,c,e,h,s,o);assertTrue(h.startsWith("pbkdf2"));return admin;}
        @Override public MedicoResponse collega(int a,int id,String s,String o)throws SQLException{call("link",a,id,s,o);return admin;}
        @Override public MedicoResponse aggiornaDatiPersonali(int a,int id,String n,String c,String e)throws SQLException{call("personal",a,id,n,c,e);return admin;}
        @Override public MedicoResponse aggiornaDatiProfessionali(int a,int id,String s,String o)throws SQLException{call("professional",a,id,s,o);return admin;}
        @Override public MedicoResponse cambiaStatoMedico(int a,int id,String s)throws SQLException{call("state",a,id,s);return admin;}
        @Override public void assegnaPrestazione(int a,int id,int p)throws SQLException{call("add",a,id,p);}
        @Override public void rimuoviPrestazione(int a,int id,int p)throws SQLException{call("remove",a,id,p);}
        @Override public List<MedicoResponse> elencaPerAmministratore(int a,String s,Integer p)throws SQLException{call("adminList",a,s,p);return p!=null&&p==2147483647?List.of():List.of(admin);}
        @Override public List<MedicoDeskResponse> elencaPerDesk(int a,String s,Integer p)throws SQLException{call("deskList",a,s,p);return List.of(desk);}
        @Override public MedicoResponse dettaglioPerAmministratore(int a,int id)throws SQLException{call("adminDetail",a,id);return admin;}
        @Override public MedicoDeskResponse dettaglioPerDesk(int a,int id)throws SQLException{call("deskDetail",a,id);return desk;}
        @Override public List<PrestazioneResponse> consultaAbilitazioni(int a,int id)throws SQLException{call("abilities",a,id);return List.of();}
    }
    ObjectMapper mapper;FakeUtenteRepository identity;Repo data;SessionStore store;SessionStore.Session session;MutableClock clock;
    HttpServer server;HttpClient client;String origin,cookie;AuthConfig config;
    @BeforeEach void start()throws Exception{
        mapper=new ObjectMapper();identity=new FakeUtenteRepository();role(RuoloGestionale.AMMINISTRATORE);
        clock=new MutableClock();store=new SessionStore(clock,10);session=store.create(7, 0L);data=new Repo();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);origin="http://127.0.0.1:"+server.getAddress().getPort();config=new AuthConfig("local-http",origin);cookie=config.cookieName()+"="+session.id();
        server.createContext("/api/medici",new AutorizzazioneHandler(new MedicoController(new MedicoService(data,new PasswordHasher()),identity,mapper),new AutenticazioneService(identity,new PasswordHasher(),store),store,config,mapper));
        server.start();client=HttpClient.newHttpClient();
    }
    void role(RuoloGestionale r){identity.identity=new UtenteAutenticatoResponse(7,"N","C",true,true,r);}
    @AfterEach void stop(){if(client!=null)client.close();if(server!=null)server.stop(0);}
    HttpResponse<String> request(String method,String path,byte[] body,String type,String auth,String csrf,String requestOrigin)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(origin+path)).timeout(java.time.Duration.ofSeconds(15));
        if(type!=null)b.header("Content-Type",type);if(auth!=null)b.header("Cookie",auth);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(requestOrigin!=null)b.header("Origin",requestOrigin);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> send(String m,String p,String body)throws Exception{return request(m,p,body==null?null:body.getBytes(StandardCharsets.UTF_8),"application/json",cookie,session.csrfToken(),origin);}
    @Test void resetPasswordContractAndAuthorization() throws Exception {
        String path="/api/medici/9/password", body="{\"nuovaPassword\":\" password nuova \"}";
        var r=send("PUT",path,body);assertEquals(200,r.statusCode());
        assertEquals(9,mapper.readTree(r.body()).get("idMedico").intValue());
        assertTrue(mapper.readTree(r.body()).get("sessioniInvalidate").booleanValue());
        assertFalse(r.body().contains("password nuova"));assertEquals("reset",data.operation);
        assertEquals(405,send("GET",path,null).statusCode());
        assertEquals(400,send("PUT",path,"{\"nuovaPassword\":\"breve\"}").statusCode());
        assertEquals(400,send("PUT",path,"{\"nuovaPassword\":\" password nuova \",\"ripetizione\":\" password nuova \"}").statusCode());
        assertEquals(400,send("PUT",path,"{\"nuovaPassword\":\" password nuova \",\"nuovaPassword\":\" password nuova \"}").statusCode());
        assertEquals(403,request("PUT",path,body.getBytes(),"application/json",cookie,null,origin).statusCode());
        assertEquals(403,request("PUT",path,body.getBytes(),"application/json",cookie,session.csrfToken(),"http://wrong.invalid").statusCode());
        assertEquals(401,request("PUT",path,body.getBytes(),"application/json",null,null,origin).statusCode());
        role(RuoloGestionale.OPERATORE_DESK);assertEquals(403,send("PUT",path,body).statusCode());
        role(null);assertEquals(403,send("PUT",path,body).statusCode());
        assertEquals(1,data.calls);
    }
    @Test void allRoutesDelegateOneMutationAndSerializeSafeDtos()throws Exception{
        assertEquals(201,send("POST","/api/medici","{\"nome\":\" Nome \",\"cognome\":\"C\",\"email\":\"NEW@example.test\",\"password\":\"password di prova\",\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}").statusCode());assertEquals("create",data.operation);assertEquals(7,data.arguments[0]);assertEquals("Nome",data.arguments[1]);
        assertEquals(201,send("POST","/api/medici/9/collegamento","{\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}").statusCode());assertEquals("link",data.operation);
        assertEquals(200,send("PUT","/api/medici/9","{\"tipo\":\"PERSONALI\",\"nome\":\"N\",\"cognome\":\"C\",\"email\":\"new@example.test\"}").statusCode());assertEquals("personal",data.operation);
        assertEquals(200,send("PUT","/api/medici/9","{\"tipo\":\"PROFESSIONALI\",\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}").statusCode());assertEquals("professional",data.operation);
        for(String state:List.of("ATTIVO","INATTIVO"))assertEquals(200,send("PUT","/api/medici/9/stato","{\"stato\":\""+state+"\"}").statusCode());
        for(String op:List.of("ASSEGNA","RIMUOVI")) {var r=send("PUT","/api/medici/9/prestazioni","{\"operazione\":\""+op+"\",\"idPrestazione\":7}");assertEquals(200,r.statusCode());assertEquals(op,mapper.readTree(r.body()).get("operazione").asText());assertEquals(9,mapper.readTree(r.body()).get("idMedico").asInt());}
        assertEquals(8,data.calls);
        var list=send("GET","/api/medici?stato=INATTIVO&idPrestazione=7",null);assertEquals(200,list.statusCode());assertEquals("adminList",data.operation);assertArrayEquals(new Object[]{7,"INATTIVO",7},data.arguments);
        assertEquals("[]",send("GET","/api/medici?idPrestazione=2147483647",null).body());
        assertTrue(mapper.readTree(send("GET","/api/medici/9",null).body()).get("medico").has("email"));
        role(RuoloGestionale.OPERATORE_DESK);var detail=send("GET","/api/medici/9",null);assertEquals(200,detail.statusCode());
        var j=mapper.readTree(detail.body());assertTrue(j.get("prestazioniAutorizzate").isArray());assertEquals(6,j.get("medico").size());assertTrue(j.get("paziente").isBoolean());
        for(String field:List.of("email","ruoloGestionale","paziente","password","passwordHash"))assertFalse(j.get("medico").has(field));
        assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElseThrow());assertEquals("nosniff",detail.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }
    @ParameterizedTest @ValueSource(strings={"/api/medici/0","/api/medici/-1","/api/medici/01","/api/medici/2147483648","/api/medici/%39","/api/medici/9/","/api/medici/9/extra","/api/medici/","/api/medicix"})
    void unknownPathsStay404(String path)throws Exception{for(String m:List.of("GET","POST","PUT"))assertEquals(404,send(m,path,"{}").statusCode());assertEquals(0,data.calls);}
    @Test void methodsAndAuthPrecedeParsing()throws Exception{
        for(String p:List.of("/api/medici","/api/medici/9","/api/medici/9/collegamento","/api/medici/9/stato","/api/medici/9/prestazioni")){
            var r=send("DELETE",p,"{}");assertEquals(405,r.statusCode());assertEquals(p.equals("/api/medici")?"GET, POST":p.equals("/api/medici/9")?"GET, PUT":p.endsWith("collegamento")?"POST":"PUT",r.headers().firstValue("Allow").orElseThrow());
        }
        assertEquals(401,request("GET","/api/medici",null,null,null,null,null).statusCode());
        role(null);assertEquals(403,send("GET","/api/medici",null).statusCode());role(RuoloGestionale.OPERATORE_DESK);
        assertEquals(403,send("POST","/api/medici","not-json").statusCode());role(RuoloGestionale.AMMINISTRATORE);
        assertEquals(403,request("PUT","/api/medici/9/stato","{}".getBytes(),"application/json",cookie,null,origin).statusCode());
        assertEquals(403,request("PUT","/api/medici/9/stato","{}".getBytes(),"application/json",cookie,session.csrfToken(),"http://invalid.test").statusCode());
        store.revoke(session.id());assertEquals(401,send("GET","/api/medici",null).statusCode());assertEquals(0,data.calls);
    }
    @ParameterizedTest @ValueSource(strings={"", "null","[]","{}","{\"stato\":null}","{\"stato\":\"attivo\"}","{\"stato\":\"ATTIVO \"}","{\"stato\":\"ATTIVO\",\"stato\":\"INATTIVO\"}","{\"stato\":\"ATTIVO\",\"idOperatore\":7}","{\"stato\":\"ATTIVO\"} {}"})
    void strictJson(String body)throws Exception{assertEquals(400,send("PUT","/api/medici/9/stato",body).statusCode());assertEquals(0,data.calls);}
    @Test void bodyLimitsMediaTypeUtf8AndMixedFields()throws Exception{
        String json="{\"stato\":\"ATTIVO\"}";String exact=json+" ".repeat(8192-json.length());assertEquals(200,send("PUT","/api/medici/9/stato",exact).statusCode());
        assertEquals(413,send("PUT","/api/medici/9/stato",exact+" ").statusCode());
        assertEquals(415,request("PUT","/api/medici/9/stato",json.getBytes(),"text/plain",cookie,session.csrfToken(),origin).statusCode());
        assertEquals(400,request("PUT","/api/medici/9/stato",new byte[]{(byte)0xc3,0x28},"application/json",cookie,session.csrfToken(),origin).statusCode());
        assertEquals(400,send("PUT","/api/medici/9","{\"tipo\":\"PERSONALI\",\"nome\":\"N\",\"cognome\":\"C\",\"email\":\"a@example.test\",\"specializzazione\":\"S\"}").statusCode());
        for(String id:List.of("\"7\"","7.0","0","2147483648"))assertEquals(400,send("PUT","/api/medici/9/prestazioni","{\"operazione\":\"ASSEGNA\",\"idPrestazione\":"+id+"}").statusCode());
        assertEquals(1,data.calls);
    }
    @ParameterizedTest @ValueSource(strings={"?stato","?stato=","?stato=attivo","?stato=ATTIVO&stato=ATTIVO","?vista=admin","?idPrestazione=0","?idPrestazione=7.0","?idPrestazione=2147483648","?%73tato=ATTIVO&stato=INATTIVO"})
    void invalidFilters(String query)throws Exception{assertEquals(400,send("GET","/api/medici"+query,null).statusCode());assertEquals(0,data.calls);}
    @Test void queriesForbiddenAndSafeErrors()throws Exception{
        assertEquals(400,send("GET","/api/medici/9?stato=ATTIVO",null).statusCode());assertEquals(400,send("PUT","/api/medici/9/stato?x=7","{}").statusCode());
        for(int code:List.of(403,404,409,500,503)){
            data.failure=code==403?new OperazioneNonAutorizzataException("secret"):code==404?new RisorsaNonTrovataException("secret"):code==409?new ConflittoMedicoException("secret"):null;
            data.sql=code==500?new SQLException("SELECT secret credentials"):code==503?new UtenteRepository.LockAccountTimeoutException():null;
            var r=send("PUT","/api/medici/9/stato","{\"stato\":\"ATTIVO\"}");assertEquals(code,r.statusCode());assertFalse(r.body().contains("secret"));
            if(code==503)assertEquals("5",r.headers().firstValue("Retry-After").orElseThrow());
        }
    }
    @Test void roleChangedDuringReadDiscardsProjectionWithoutFallback()throws Exception {
        data.onCall=()->role(RuoloGestionale.OPERATORE_DESK);
        var r=send("GET","/api/medici/9",null);assertEquals(403,r.statusCode());assertFalse(r.body().contains("private@example.test"));
        assertEquals(2,data.calls); /* Dettaglio e abilitazioni, mai una seconda proiezione. */
    }
    @Test void expiredAndUnknownSessionsDoNotReachService()throws Exception {
        clock.advance(java.time.Duration.ofMinutes(31));assertEquals(401,send("GET","/api/medici",null).statusCode());
        assertEquals(401,request("GET","/api/medici",null,null,config.cookieName()+"=unknown",null,null).statusCode());assertEquals(0,data.calls);
    }
    @Test void creationAndVariantInputsRejectUnknownFieldsInvalidUnicodeAndCoercions()throws Exception {
        String valid="{\"nome\":\"N\",\"cognome\":\"C\",\"email\":\"a@example.test\",\"password\":\"password di prova\",\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\"}";
        for(String body:List.of(valid.replace("\"nome\":\"N\"","\"nome\":\"\\uD800\""),valid.replace("password di prova","short"),valid.replace("\"nome\":\"N\"","\"nome\":7"),valid.replace("\"nome\":\"N\"","\"nome\":null"),valid.replace("}",",\"ruoloGestionale\":\"AMMINISTRATORE\"}"),valid.replace("}",",\"stato\":\"INATTIVO\"}")))
            assertEquals(400,send("POST","/api/medici",body).statusCode(),body);
        for(String body:List.of("{\"tipo\":\"personali\"}","{\"tipo\":\"PERSONALI\",\"nome\":\"N\"}","{\"tipo\":\"PROFESSIONALI\",\"specializzazione\":\"S\",\"numeroIscrizioneOrdine\":\"O\",\"password\":\"other\"}"))assertEquals(400,send("PUT","/api/medici/9",body).statusCode());
        assertEquals(0,data.calls);
    }
    @Test void serializationFailureIsGeneric500Not400AndMutationIsNotRepeated()throws Exception {
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(MedicoResponse.class,new com.fasterxml.jackson.databind.JsonSerializer<MedicoResponse>() {
            @Override public void serialize(MedicoResponse value,com.fasterxml.jackson.core.JsonGenerator g,SerializerProvider provider)throws java.io.IOException {throw new java.io.IOException("secret serializer failure");}
        });mapper.registerModule(module);
        var r=send("PUT","/api/medici/9/stato","{\"stato\":\"ATTIVO\"}");assertEquals(500,r.statusCode());assertFalse(r.body().contains("secret"));assertEquals(1,data.calls);
    }
    @Test void contentTypeVariantsAndGetOriginAreEnforced()throws Exception {
        String body="{\"stato\":\"ATTIVO\"}";
        assertEquals(200,request("PUT","/api/medici/9/stato",body.getBytes(),"application/json; charset=\"UTF-8\"",cookie,session.csrfToken(),origin).statusCode());
        for(String type:Arrays.asList(null,"application/json; charset=ISO-8859-1","application/json; unknown=7"))assertEquals(415,request("PUT","/api/medici/9/stato",body.getBytes(),type,cookie,session.csrfToken(),origin).statusCode());
        assertEquals(200,request("GET","/api/medici",null,null,cookie,null,null).statusCode());
        assertEquals(403,request("GET","/api/medici",null,null,cookie,null,"http://invalid.test").statusCode());
    }
    /* Due Content-Type o un valore unito da virgola sono rifiutati prima del parsing. */
    @Test void duplicateContentTypeHeadersAre415WithoutDelegation()throws Exception {
        String body="{\"stato\":\"INATTIVO\"}";
        for(List<String> types:List.of(List.of("application/json","application/json"),List.of("application/json","text/plain"),
                List.of("text/plain","application/json; charset=UTF-8"),List.of("application/json, application/json"))) {
            var b=HttpRequest.newBuilder(URI.create(origin+"/api/medici/9/stato")).timeout(java.time.Duration.ofSeconds(15))
                    .header("Cookie",cookie).header("X-CSRF-Token",session.csrfToken()).header("Origin",origin);
            for(String type:types)b.header("Content-Type",type);
            var r=client.send(b.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(415,r.statusCode(),types.toString());
            assertEquals(Map.of("error","Content-Type non supportato."),mapper.readValue(r.body(),Map.class));
        }
        assertEquals(0,data.calls,"nessuna delega al service");
    }
    /* Cambio ruolo nella direzione opposta, senza riutilizzare una proiezione non autorizzata. */
    @Test void roleChangedFromDeskToAdminDuringReadIsRejectedWithoutAdminProjection()throws Exception {
        for(String path:List.of("/api/medici/9","/api/medici")) {
            /* Scritto dal thread server e letto dal thread del test. */
            role(RuoloGestionale.OPERATORE_DESK);List<String> operations=new java.util.concurrent.CopyOnWriteArrayList<>();
            data.onCall=()->{operations.add(data.operation);role(RuoloGestionale.AMMINISTRATORE);};
            var r=send("GET",path,null);
            assertEquals(403,r.statusCode(),path);
            assertEquals(Map.of("error","Operazione non autorizzata."),mapper.readValue(r.body(),Map.class));
            assertFalse(r.body().contains("private@example.test"));
            /* Solo proiezione desk: nessuna lettura amministrativa o retry col nuovo ruolo. */
            assertEquals(path.equals("/api/medici")?List.of("deskList"):List.of("deskDetail","abilities","patientFlag"),operations);
        }
    }

}
