package it.medbook.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.controller.AutenticazioneController;
import it.medbook.model.RuoloGestionale;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.MutableClock;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* Stessa sessione: il permesso MEDICO viene ricalcolato dallo stato operativo corrente. */
class MedicoStatoIdentitaIntegrationTest {
    private static final String PASSWORD="password di prova F3";
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<Integer> users=new ArrayList<>();
    private ConnectionFactory factory;
    private MutableClock clock;
    private SessionStore sessions;
    private HttpServer server;
    private HttpClient client;
    private String origin;

    @BeforeEach void start() throws Exception {
        factory=TestDatabaseConnections.isolatedFactory();
        clock=new MutableClock();sessions=new SessionStore(clock,20);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        var config=new AuthConfig("local-http",origin);var auth=new AutenticazioneService(new UtenteRepository(factory),new PasswordHasher(),sessions);
        server.createContext("/api/auth",new AutenticazioneController(auth,sessions,new LoginThrottle(clock,100,100),config,mapper));
        /* Il delegato dimostra soltanto il passaggio dell'autorizzazione, non gli effetti clinici. */
        com.sun.net.httpserver.HttpHandler delegate=exchange->{
            byte[] body=("{\"principal\":\""+exchange.getPrincipal().getUsername()+"\"}").getBytes();
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        };
        for(String context:List.of("/api/prenotazioni","/api/disponibilita","/api/utenti"))
            server.createContext(context,new AutorizzazioneHandler(delegate,auth,sessions,config,mapper));
        server.start();client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void stop() throws Exception {
        try { if(client!=null)client.close(); if(server!=null)server.stop(0); }
        finally { if(factory!=null)for(Integer id:users)execute("DELETE FROM utente WHERE id_utente=?",id); }
    }

    private int user(String ruolo,boolean medico,boolean paziente) throws Exception {
        int id;
        try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(
                "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES('F3','Medico',?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1,"f3-"+UUID.randomUUID()+"@example.invalid");s.setString(2,new PasswordHasher().hash(PASSWORD.toCharArray()));s.setString(3,ruolo);
            assertEquals(1,s.executeUpdate());try(ResultSet k=s.getGeneratedKeys()){assertTrue(k.next());id=k.getInt(1);}
        }
        users.add(id);
        if(medico)execute("INSERT INTO medico(id_utente,specializzazione,numero_iscrizione_ordine) VALUES(?,?,?)",id,"F3","F3-"+UUID.randomUUID());
        if(paziente)execute("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,'1990-01-01')",id,
                UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT));
        return id;
    }
    private void execute(String sql,Object... values) throws SQLException {
        try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.executeUpdate();
        }
    }
    private void stato(int id,String stato) throws SQLException { execute("UPDATE medico SET stato=? WHERE id_utente=?",stato,id); }
    private String email(int id) throws SQLException {
        try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT email FROM utente WHERE id_utente=?")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    private String login(int id) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/login")).header("Origin",origin)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("email",email(id),"password",PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
    }
    private HttpResponse<String> get(String path,String cookie) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).GET();if(cookie!=null)builder.header("Cookie",cookie);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode me(String cookie) throws Exception {
        var response=get("/api/auth/me",cookie);assertEquals(200,response.statusCode());return mapper.readTree(response.body());
    }
    private int agenda(int id,String cookie) throws Exception { return get("/api/prenotazioni/agenda?medicoId="+id,cookie).statusCode(); }
    private int disponibilita(int id,String cookie) throws Exception { return get("/api/disponibilita?medicoId="+id,cookie).statusCode(); }
    private int paziente(int id,String cookie) throws Exception { return get("/api/prenotazioni/paziente?idPaziente="+id,cookie).statusCode(); }

    @Test void activeDoctorLogsInWithMedicoPermission() throws Exception {
        int id=user(null,true,false);String cookie=login(id);
        assertTrue(me(cookie).get("medico").asBoolean());
        assertEquals(200,agenda(id,cookie));assertEquals(200,disponibilita(id,cookie));
    }
    @Test void inactiveDoctorKeepsLoginAndPatientRightsButNotMedico() throws Exception {
        int id=user(null,true,true);stato(id,"INATTIVO");String cookie=login(id);
        var me=me(cookie);assertFalse(me.get("medico").asBoolean());assertTrue(me.get("paziente").asBoolean());
        assertEquals(403,agenda(id,cookie));assertEquals(403,disponibilita(id,cookie));assertEquals(200,paziente(id,cookie));
    }
    @Test void inactivationAndReactivationApplyToTheSameSession() throws Exception {
        int id=user(null,true,true);int admin=user("AMMINISTRATORE",false,false);String cookie=login(id);
        assertEquals(200,agenda(id,cookie));assertTrue(me(cookie).get("medico").asBoolean());
        new MedicoRepository(factory).cambiaStatoMedico(admin,id,"INATTIVO");
        assertEquals(403,agenda(id,cookie));assertEquals(403,disponibilita(id,cookie));
        assertFalse(me(cookie).get("medico").asBoolean());assertEquals(200,paziente(id,cookie));assertEquals(1,sessions.size());
        new MedicoRepository(factory).cambiaStatoMedico(admin,id,"ATTIVO");
        assertTrue(me(cookie).get("medico").asBoolean());assertEquals(200,agenda(id,cookie));assertEquals(200,disponibilita(id,cookie));
        /* 401 senza sessione, dopo revoca o dopo scadenza. */
        assertEquals(401,agenda(id,null));
        String expiring=login(id);clock.advance(Duration.ofMinutes(30));assertEquals(401,agenda(id,expiring));
        String revoked=login(id);var session=sessions.find(revoked.substring(revoked.indexOf('=')+1)).orElseThrow();
        sessions.revoke(session.id());assertEquals(401,agenda(id,revoked));
    }
    @Test void nonCanonicalStatesAdmittedByCollationNeverGrantMedico() throws Exception {
        int id=user(null,true,true);String cookie=login(id);
        for(String value:List.of("attivo","Attivo","aTTIVO","inattivo")) {
            stato(id,value);
            try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(
                    "SELECT HEX(stato),stato='ATTIVO' FROM medico WHERE id_utente=?")) {
                s.setInt(1,id);try(ResultSet r=s.executeQuery()) {
                    assertTrue(r.next());assertEquals(HexFormat.of().withUpperCase().formatHex(value.getBytes()),r.getString(1),"valore non convertito");
                    /* Il confronto ai_ci impedisce di decidere il permesso con il solo confronto SQL. */
                    assertEquals(value.equalsIgnoreCase("ATTIVO"),r.getBoolean(2));
                }
            }
            assertFalse(me(cookie).get("medico").asBoolean(),value);assertEquals(403,agenda(id,cookie),value);assertEquals(200,paziente(id,cookie),value);
        }
        stato(id,"ATTIVO");assertEquals(200,agenda(id,cookie));
    }
    @Test void uc26StillSeesActiveAndInactiveDoctorProfiles() throws Exception {
        int actor=user("AMMINISTRATORE",false,false);
        int adminDoctor=user("AMMINISTRATORE",true,false);stato(adminDoctor,"INATTIVO");
        int inactiveDoctor=user(null,true,false);stato(inactiveDoctor,"INATTIVO");
        int deskDoctor=user("OPERATORE_DESK",true,false);
        var accounts=new UtenteRepository(factory);
        var listed=accounts.findGestionali().stream().filter(a->a.idUtente()==adminDoctor||a.idUtente()==deskDoctor).toList();
        assertEquals(2,listed.size());assertTrue(listed.stream().allMatch(a->a.medico()));
        for(String value:List.of("INATTIVO","ATTIVO","attivo")) {
            stato(deskDoctor,value);
            for(int target:List.of(inactiveDoctor,deskDoctor))
                assertEquals("Profilo medico incompatibile con ruolo gestionale.",assertThrows(UtenteRepository.ConflittoAccountException.class,
                        ()->accounts.updateRuolo(actor,target,RuoloGestionale.AMMINISTRATORE)).getMessage());
        }
        assertNull(accounts.findById(inactiveDoctor).orElseThrow().ruoloGestionale());
        assertEquals(RuoloGestionale.OPERATORE_DESK,accounts.findById(deskDoctor).orElseThrow().ruoloGestionale());
        /*
         * Una combinazione amministratore-medico preesistente mantiene i diritti amministrativi
         * anche con medico inattivo.
         */
        var created=accounts.createInterno(adminDoctor,"Nuovo","Interno","f3-"+UUID.randomUUID()+"@example.invalid","synthetic-test-hash",RuoloGestionale.OPERATORE_DESK);
        users.add(created.idUtente());assertFalse(created.medico());
        String cookie=login(adminDoctor);var me=me(cookie);
        assertFalse(me.get("medico").asBoolean());assertEquals("AMMINISTRATORE",me.get("ruoloGestionale").asText());
        assertEquals(200,get("/api/utenti",cookie).statusCode());assertEquals(403,agenda(adminDoctor,cookie));
    }
}
