package it.medbook.controller;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.config.*;
import it.medbook.repository.*;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Controller, service, repository reali; fixture sintetiche solo su database isolato verificato. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutorizzazioneIntegrationTest {
    private ConnectionFactory factory;
    private final List<Integer> users=new ArrayList<>(),slots=new ArrayList<>();
    private final Map<Integer,String> emails=new HashMap<>();
    private final List<SessionStore.Session> issued=new ArrayList<>();
    private int patient,otherPatient,doctor,otherDoctor,desk,admin,dual,prestazione,stanza,ownSlot,otherSlot,unconfigured,otherBooking;
    private LocalDateTime start;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AuthConfig config=new AuthConfig("local-http","http://localhost:8080");
    private final SessionStore sessions=new SessionStore(Clock.systemUTC(),1000);
    private AutenticazioneService auth;
    private AutorizzazioneHandler disponibilita,prenotazioni,prestazioni;
    private char[] password;
    private String hash;
    @BeforeAll void fixture() throws Exception {
        String expected=System.getenv("MEDBOOK_TEST_DB_SERVER_UUID");
        assumeTrue(expected!=null && !expected.isBlank(),"UUID esplicito del database di test richiesto.");
        factory=TestDatabaseConnections.isolatedFactory();
        password=UUID.randomUUID().toString().toCharArray();hash=new PasswordHasher().hash(password);
        patient=user(true,false,null);otherPatient=user(true,false,null);
        doctor=user(false,true,null);otherDoctor=user(false,true,null);
        desk=user(false,false,"OPERATORE_DESK");admin=user(false,false,"AMMINISTRATORE");dual=user(true,true,"AMMINISTRATORE");
        prestazione=insert("INSERT INTO prestazione(nome,durata,prezzo) VALUES(?,?,?)","Fixture authz",60,new java.math.BigDecimal("110.00"));
        stanza=insert("INSERT INTO stanza(nome) VALUES(?)","authz-"+UUID.randomUUID());
        for(int medico:List.of(doctor,otherDoctor,dual)){update("INSERT INTO medico_prestazione(id_medico,id_prestazione) VALUES(?,?)",medico,prestazione);}
        start=LocalDate.now().plusDays(90).atTime(9,0);
        ownSlot=slot(doctor,start,true);otherSlot=slot(otherDoctor,start.plusHours(3),true);unconfigured=slot(doctor,start.plusHours(2),false);
        auth=new AutenticazioneService(new UtenteRepository(factory),new PasswordHasher(),sessions);
        disponibilita=new AutorizzazioneHandler(new DisponibilitaController(new DisponibilitaService(new DisponibilitaRepository(factory)),mapper),auth,sessions,config,mapper);
        prenotazioni=new AutorizzazioneHandler(new PrenotazioneController(new PrenotazioneService(new PrenotazioneRepository(factory)),mapper),auth,sessions,config,mapper);
        prestazioni=new AutorizzazioneHandler(new PrestazioneController(new PrestazioneService(new PrestazioneRepository(factory)),mapper),auth,sessions,config,mapper);
    }
    private int user(boolean paziente,boolean medico,String role) throws Exception {
        String email="authz-"+UUID.randomUUID()+"@example.invalid";
        int id=insert("INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale) VALUES(?,?,?,?,?)","Fixture","UC02",email,hash,role);
        users.add(id);emails.put(id,email);
        if(paziente){update("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,?)",id,UUID.randomUUID().toString().replace("-","").substring(0,16),java.sql.Date.valueOf("2000-01-01"));}
        if(medico){update("INSERT INTO medico(id_utente,specializzazione,numero_iscrizione_ordine) VALUES(?,?,?)",id,"Test",UUID.randomUUID().toString());}
        return id;
    }
    private int slot(int medico,LocalDateTime time,boolean configured) throws Exception {
        int id=insert("INSERT INTO disponibilita(id_medico,data_ora_inizio,data_ora_fine,id_prestazione,id_stanza) VALUES(?,?,?,?,?)",
                medico,time,time.plusHours(1),configured?prestazione:null,configured?stanza:null);slots.add(id);return id;
    }
    private int insert(String sql,Object... values) throws Exception {
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){
            for(int i=0;i<values.length;i++){statement.setObject(i+1,values[i]);}assertEquals(1,statement.executeUpdate());
            try(ResultSet keys=statement.getGeneratedKeys()){assertTrue(keys.next());return keys.getInt(1);}
        }
    }
    private void update(String sql,Object... values) throws Exception {
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement(sql)){
            for(int i=0;i<values.length;i++){statement.setObject(i+1,values[i]);}statement.executeUpdate();
        }
    }
    private String field(int id,String column) throws Exception {
        assertTrue(Set.of("stato","indisponibilita_segnalata","id_medico_effettivo","id_paziente","gestita_da").contains(column));
        try(Connection connection=factory.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT "+column+" FROM prenotazione WHERE id_prenotazione=?")){
            statement.setInt(1,id);try(ResultSet row=statement.executeQuery()){assertTrue(row.next());return row.getString(1);}
        }
    }
    @BeforeEach void booking() throws Exception {
        if(factory==null){return;}
        otherBooking=insert("INSERT INTO prenotazione(id_paziente,id_disponibilita,data_ora_richiesta,prezzo) VALUES(?,?,?,?)",
                otherPatient,otherSlot,LocalDateTime.now().withNano(0),new java.math.BigDecimal("110.00"));
    }
    @AfterEach void reset() throws Exception {
        if(factory==null || slots.isEmpty()){return;}
        for(int slot:slots){update("DELETE FROM prenotazione WHERE id_disponibilita=?",slot);}
        if(unconfigured>0){update("UPDATE disponibilita SET id_prestazione=NULL,id_stanza=NULL WHERE id_disponibilita=?",unconfigured);}
        if(desk>0){update("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?",desk);}
        for(var session:issued){sessions.revoke(session.id());}issued.clear();
    }
    @AfterAll void cleanup() throws Exception {
        try{
            if(factory!=null){
                for(int slot:slots){update("DELETE FROM prenotazione WHERE id_disponibilita=?",slot);update("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);}
                for(int user:users){update("DELETE FROM utente WHERE id_utente=?",user);}
                if(prestazione>0){update("DELETE FROM prestazione WHERE id_prestazione=?",prestazione);}
                if(stanza>0){update("DELETE FROM stanza WHERE id_stanza=?",stanza);}
            }
        }finally{if(password!=null){Arrays.fill(password,'\0');}}
    }
    private SessionStore.Session session(int user){var result=sessions.create(user, 0L);issued.add(result);return result;}
    private TestHttpExchange request(String method,String path,Object body,SessionStore.Session current) throws Exception {
        var request=new TestHttpExchange(method,path,body==null?"":mapper.writeValueAsString(body));
        if(current!=null){request.requestHeaders.set("Cookie",config.cookieName()+"="+current.id());request.requestHeaders.set("X-CSRF-Token",current.csrfToken());}
        if(!method.equals("GET")){request.requestHeaders.set("Origin",config.origin());}
        if(path.startsWith("/api/prestazioni")){prestazioni.handle(request);}
        else if(path.startsWith("/api/disponibilita")){disponibilita.handle(request);}
        else{prenotazioni.handle(request);}return request;
    }
    private int create(SessionStore.Session current) throws Exception {
        var created=request("POST","/api/prenotazioni",Map.of("idPaziente",patient,"idDisponibilita",ownSlot),current);
        assertEquals(201,created.status,created.body());return mapper.readTree(created.body()).get("idPrenotazione").intValue();
    }
    @Test void publicCatalogAndSearchRemainAccessibleWithoutSession() throws Exception {
        assertEquals(200,request("GET","/api/prestazioni",null,null).status);
        var result=request("GET","/api/disponibilita/prenotabili?medicoId="+doctor,null,null);
        assertEquals(200,result.status);assertTrue(mapper.readTree(result.body()).isArray());assertFalse(result.body().contains("password"));
    }
    @Test void patientCreatesAndReadsOnlyOwnAppointments() throws Exception {
        var current=session(patient);int created=create(current);
        var result=request("GET","/api/prenotazioni/paziente?idPaziente="+patient,null,current);
        assertEquals(200,result.status);var array=mapper.readTree(result.body());assertEquals(1,array.size());assertEquals(created,array.get(0).get("idPrenotazione").intValue());
        assertEquals("RICHIESTA",field(created,"stato"));assertEquals("0",field(created,"indisponibilita_segnalata"));
        assertFalse(result.body().contains("password"));assertFalse(result.body().contains("indisponibilitaSegnalata"));
    }
    @Test void forgedPatientActorIs403WithoutCreatingOrCancellingOtherBooking() throws Exception {
        var current=session(patient);
        assertEquals(403,request("POST","/api/prenotazioni",Map.of("idPaziente",otherPatient,"idDisponibilita",ownSlot),current).status);
        assertEquals(403,request("GET","/api/prenotazioni/paziente?idPaziente="+otherPatient,null,current).status);
        assertEquals(403,request("POST","/api/prenotazioni/"+otherBooking+"/annulla-paziente",Map.of("idPaziente",otherPatient,"statoAtteso","RICHIESTA"),current).status);
        assertEquals("RICHIESTA",field(otherBooking,"stato"));
    }
    @Test void ownActorAgainstAnotherPatientsBookingRemains404() throws Exception {
        var current=session(patient);
        var other=request("POST","/api/prenotazioni/"+otherBooking+"/annulla-paziente",Map.of("idPaziente",patient,"statoAtteso","RICHIESTA"),current);
        var missing=request("POST","/api/prenotazioni/2147483647/annulla-paziente",Map.of("idPaziente",patient,"statoAtteso","RICHIESTA"),current);
        assertEquals(404,other.status);assertEquals(404,missing.status);assertEquals(other.body(),missing.body());assertEquals("RICHIESTA",field(otherBooking,"stato"));
    }
    @Test void doctorCanReadOwnSlotsAndCreateFutureInterval() throws Exception {
        var current=session(doctor);assertEquals(200,request("GET","/api/disponibilita?medicoId="+doctor,null,current).status);
        var created=request("POST","/api/disponibilita",Map.of("idMedico",doctor,"dataOraInizio",start.plusDays(2),"dataOraFine",start.plusDays(2).plusHours(2)),current);
        assertEquals(201,created.status,created.body());var array=mapper.readTree(created.body());assertEquals(2,array.size());
        for(var row:array){slots.add(row.get("idDisponibilita").intValue());assertEquals(doctor,row.get("idMedico").intValue());}
    }
    @Test void doctorForgedActorIs403AndOtherSlotWithOwnActorIs404() throws Exception {
        var current=session(doctor);
        assertEquals(403,request("GET","/api/disponibilita?medicoId="+otherDoctor,null,current).status);
        assertEquals(403,request("DELETE","/api/disponibilita/"+otherSlot+"?medicoId="+otherDoctor,null,current).status);
        assertEquals(404,request("DELETE","/api/disponibilita/"+otherSlot+"?medicoId="+doctor,null,current).status);
        var update=request("PUT","/api/disponibilita/"+otherSlot+"/orario",Map.of("idMedico",doctor,"dataOraInizio",start.plusDays(1),"dataOraFine",start.plusDays(1).plusHours(1)),current);
        assertEquals(404,update.status);
    }
    @Test void deskConsultsAnotherDoctorAndConfiguresWithoutDoctorProfile() throws Exception {
        var current=session(desk);assertEquals(200,request("GET","/api/disponibilita?medicoId="+otherDoctor,null,current).status);
        var result=request("PUT","/api/disponibilita/"+unconfigured+"/configurazione",Map.of("idPrestazione",prestazione,"idStanza",stanza),current);
        assertEquals(200,result.status,result.body());assertTrue(mapper.readTree(result.body()).get("configurata").asBoolean());
    }
    @Test void administratorDoesNotInheritDeskAndDualProfilesRemainIndependent() throws Exception {
        var administrator=session(admin);assertEquals(403,request("GET","/api/prenotazioni/richieste",null,administrator).status);
        assertEquals(403,request("PUT","/api/disponibilita/"+unconfigured+"/configurazione",Map.of("idPrestazione",prestazione,"idStanza",stanza),administrator).status);
        var current=session(dual);
        assertEquals(200,request("GET","/api/prenotazioni/paziente?idPaziente="+dual,null,current).status);
        assertEquals(200,request("GET","/api/prenotazioni/agenda?medicoId="+dual,null,current).status);
        assertEquals(403,request("GET","/api/prenotazioni/richieste",null,current).status);
    }
    @Test void missingCsrfAndRevokedDeskRoleCannotConfirmOrModifyBooking() throws Exception {
        var current=session(desk);
        var missing=new TestHttpExchange("POST","/api/prenotazioni/"+otherBooking+"/conferma",mapper.writeValueAsString(Map.of("idOperatore",desk)));
        missing.requestHeaders.set("Origin",config.origin());missing.requestHeaders.set("Cookie",config.cookieName()+"="+current.id());prenotazioni.handle(missing);
        assertEquals(403,missing.status);assertEquals("RICHIESTA",field(otherBooking,"stato"));
        update("UPDATE utente SET ruolo_gestionale=NULL WHERE id_utente=?",desk);
        assertEquals(403,request("POST","/api/prenotazioni/"+otherBooking+"/conferma",Map.of("idOperatore",desk),current).status);
        assertEquals("RICHIESTA",field(otherBooking,"stato"));
    }
    @Test void authorizedWorkflowPreservesUc05Uc11Uc17Uc20Uc13() throws Exception {
        var patientSession=session(patient);var doctorSession=session(doctor);var substituteSession=session(otherDoctor);var deskSession=session(desk);
        int id=create(patientSession);
        assertEquals(200,request("POST","/api/prenotazioni/segnala-indisponibilita",Map.of("medicoId",doctor,"idPrenotazioni",List.of(id)),doctorSession).status);
        assertEquals("1",field(id,"indisponibilita_segnalata"));
        assertEquals(409,request("POST","/api/prenotazioni/"+id+"/conferma",Map.of("idOperatore",desk),deskSession).status);
        var replacement=request("POST","/api/prenotazioni/"+id+"/sostituisci-medico",Map.of("idOperatore",desk,"idMedicoSostituto",otherDoctor,"idMedicoIndisponibile",doctor,"statoAtteso","RICHIESTA"),deskSession);
        assertEquals(200,replacement.status,replacement.body());assertEquals("CONFERMATA",field(id,"stato"));assertEquals(Integer.toString(otherDoctor),field(id,"id_medico_effettivo"));
        var agenda=request("GET","/api/prenotazioni/agenda?medicoId="+otherDoctor+"&data="+start.toLocalDate(),null,substituteSession);
        assertEquals(200,agenda.status);assertTrue(agenda.body().contains("\"idPrenotazione\":"+id));
        assertEquals(200,request("POST","/api/prenotazioni/segnala-indisponibilita",Map.of("medicoId",otherDoctor,"idPrenotazioni",List.of(id)),substituteSession).status);
        assertEquals(200,request("GET","/api/prenotazioni/segnalazioni-indisponibilita",null,deskSession).status);
        assertEquals(200,request("POST","/api/prenotazioni/"+id+"/annulla",Map.of("idOperatore",desk,"statoAtteso","CONFERMATA"),deskSession).status);
        assertEquals("ANNULLATA",field(id,"stato"));assertEquals("0",field(id,"indisponibilita_segnalata"));
    }
    @Test void authorizedUc07AndUc12ClearFlagAndReopenSlot() throws Exception {
        var patientSession=session(patient);var doctorSession=session(doctor);var deskSession=session(desk);int id=create(patientSession);
        assertEquals(200,request("POST","/api/prenotazioni/segnala-indisponibilita",Map.of("medicoId",doctor,"idPrenotazioni",List.of(id)),doctorSession).status);
        assertEquals(200,request("POST","/api/prenotazioni/"+id+"/annulla-paziente",Map.of("idPaziente",patient,"statoAtteso","RICHIESTA"),patientSession).status);
        assertEquals("0",field(id,"indisponibilita_segnalata"));int next=create(patientSession);
        assertEquals(200,request("POST","/api/prenotazioni/segnala-indisponibilita",Map.of("medicoId",doctor,"idPrenotazioni",List.of(next)),doctorSession).status);
        assertEquals(200,request("POST","/api/prenotazioni/"+next+"/rifiuta",Map.of("idOperatore",desk),deskSession).status);
        assertEquals("RIFIUTATA",field(next,"stato"));assertEquals("0",field(next,"indisponibilita_segnalata"));assertTrue(create(patientSession)>0);
    }
    @Test void actualLoginSessionAuthorizesClinicalReadWithoutDeclaredIdentityTrust() throws Exception {
        var login=new TestHttpExchange("POST","/api/auth/login",mapper.writeValueAsString(Map.of("email",emails.get(patient),"password",new String(password))));
        login.requestHeaders.set("Origin",config.origin());new AutenticazioneController(auth,sessions,new LoginThrottle(Clock.systemUTC(),100,10),config,mapper).handle(login);
        assertEquals(200,login.status);assertEquals(patient,mapper.readTree(login.body()).get("idUtente").intValue());
        String id=login.responseHeaders.getFirst("Set-Cookie").split(";",2)[0].split("=",2)[1];
        var current=sessions.find(id).orElseThrow();issued.add(current);
        assertEquals(200,request("GET","/api/prenotazioni/paziente?idPaziente="+patient,null,current).status);
        assertEquals(403,request("GET","/api/prenotazioni/paziente?idPaziente="+otherPatient,null,current).status);
    }
}
