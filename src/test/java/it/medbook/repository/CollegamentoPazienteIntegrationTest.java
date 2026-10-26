package it.medbook.repository;

import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Solo fixture MySQL isolata con UUID verificato; nessuna risorsa demo. */
class CollegamentoPazienteIntegrationTest {
    final MedicoRepositoryIntegrationTest f=new MedicoRepositoryIntegrationTest();
    PazienteDeskService service;AutenticazioneService auth;SessionStore sessions;int desk;
    static final String CF="TSTTST90A01A000Q", PASSWORD=" password fittizia medico ";
    static final LocalDate NASCITA=LocalDate.of(1990,1,1);
    @BeforeEach void setup()throws Exception {
        f.setup();desk=f.user("OPERATORE_DESK",false,false);sessions=new SessionStore(Clock.systemUTC(),100);
        service=new PazienteDeskService(new PazienteDeskRepository(f.factory),f.hasher,sessions);
        auth=new AutenticazioneService(new UtenteRepository(f.factory),f.hasher,sessions);
    }
    @AfterEach void cleanup()throws Exception {f.cleanup();}
    int medico()throws Exception {
        int id=f.user(null,true,false);f.execute("UPDATE utente SET password_hash=? WHERE id_utente=?",f.hasher.hash(PASSWORD.toCharArray()),id);return id;
    }
    String account(int id)throws Exception {
        return f.valore("SELECT SHA2(CONCAT_WS('|',id_utente,nome,cognome,email,password_hash,COALESCE(ruolo_gestionale,''),stato_accesso,generazione_autenticazione,COALESCE(modificato_da,''),COALESCE(modificato_il,'')),256) FROM utente WHERE id_utente=?",id);
    }
    @Test void sameAccountCredentialsProfilesAndMedicalStatesArePreserved()throws Exception {
        int id=medico();String before=account(id),email=f.valore("SELECT email FROM utente WHERE id_utente=?",id);
        var old=auth.login(email,PASSWORD.toCharArray()).orElseThrow();assertFalse(auth.identity(old).orElseThrow().paziente());
        assertFalse(f.repo.profiloPazientePerDesk(desk,id));
        var result=service.collega(desk,id," "+CF.toLowerCase(Locale.ROOT)+" ",NASCITA," +39 123-456 ");
        assertEquals(id,result.idPaziente());assertTrue(result.paziente());assertEquals(before,account(id));
        assertEquals(CF,f.valore("SELECT codice_fiscale FROM paziente WHERE id_utente=?",id));assertEquals("+39 123-456",f.valore("SELECT telefono FROM paziente WHERE id_utente=?",id));
        assertTrue(f.repo.profiloPazientePerDesk(desk,id));
        var current=auth.identity(auth.login(email,PASSWORD.toCharArray()).orElseThrow()).orElseThrow();
        assertTrue(current.medico());assertTrue(current.paziente());assertEquals(id,current.idUtente());
        assertTrue(auth.identity(old).orElseThrow().paziente()); // Lettura capacità esistente, nessuna credenziale cambiata.
        var rows=service.cerca(desk,Map.of("idPaziente",Integer.toString(id))).risultati();assertEquals(1,rows.size());assertTrue(rows.getFirst().medico());
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(desk,id,"password paziente".toCharArray()));
        assertEquals(before,account(id));assertEquals("ATTIVO",f.valore("SELECT stato FROM medico WHERE id_utente=?",id));
    }
    @Test void onlyDeskAndMedicalTargetsAndDisabledActorCannotWrite()throws Exception {
        int id=medico(),patient=f.user(null,false,true);
        for(int actor:List.of(f.admin,patient,id))assertThrows(OperazioneNonAutorizzataException.class,()->service.collega(actor,id,CF,NASCITA,null));
        assertThrows(RisorsaNonTrovataException.class,()->service.collega(desk,patient,CF,NASCITA,null));
        assertThrows(RisorsaNonTrovataException.class,()->service.collega(desk,Integer.MAX_VALUE,CF,NASCITA,null));
        f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?",desk);
        assertThrows(OperazioneNonAutorizzataException.class,()->service.collega(desk,id,CF,NASCITA,null));
        assertEquals("0",f.valore("SELECT COUNT(*) FROM paziente WHERE id_utente=?",id));
    }
    @Test void duplicateFiscalCodeAndExistingProfileNeverOverwrite()throws Exception {
        int a=medico(),b=medico();service.collega(desk,a,CF,NASCITA,null);
        assertThrows(ConflittoProfiloPazienteException.class,()->service.collega(desk,b,CF,NASCITA,"123"));
        assertThrows(ConflittoProfiloPazienteException.class,()->service.collega(desk,a,CF,NASCITA.plusDays(1),"123"));
        assertEquals(NASCITA.toString(),f.valore("SELECT data_nascita FROM paziente WHERE id_utente=?",a));assertNull(f.valore("SELECT telefono FROM paziente WHERE id_utente=?",a));
        assertEquals("0",f.valore("SELECT COUNT(*) FROM paziente WHERE id_utente=?",b));
    }
    @Test void validationAndInactiveOrDisabledRecipientDoesNotChangeStates()throws Exception {
        int id=medico();String before=account(id);
        assertThrows(IllegalArgumentException.class,()->service.collega(desk,id,"TSTTST90A01A000A",NASCITA,null));
        for(LocalDate d:Arrays.asList(null,LocalDate.of(999,1,1),LocalDate.now().plusDays(1)))assertThrows(IllegalArgumentException.class,()->service.collega(desk,id,CF,d,null));
        for(String tel:List.of("abc","+()", "1".repeat(21)))assertThrows(IllegalArgumentException.class,()->service.collega(desk,id,CF,NASCITA,tel));
        assertEquals(before,account(id));
        f.execute("UPDATE medico SET stato='INATTIVO' WHERE id_utente=?",id);f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?",id);
        String disabled=account(id);service.collega(desk,id,CF,NASCITA,"   ");assertEquals(disabled,account(id));assertEquals("INATTIVO",f.valore("SELECT stato FROM medico WHERE id_utente=?",id));
    }
    @Test void competingLinksProduceOneProfileAndNoAccountChange()throws Exception {
        int id=medico();String before=account(id);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> op=()->{gate.await();try{service.collega(desk,id,CF,NASCITA,null);return true;}catch(ConflittoProfiloPazienteException e){return false;}};
            var a=pool.submit(op);var b=pool.submit(op);gate.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals("1",f.valore("SELECT COUNT(*) FROM paziente WHERE id_utente=?",id));assertEquals(before,account(id));
    }
    @Test void differentDoctorsCompetingForSameFiscalCodeKeepExactlyOneProfile()throws Exception {
        int a=medico(),b=medico();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var first=pool.submit(()->{gate.await();try{service.collega(desk,a,CF,NASCITA,null);return true;}catch(ConflittoProfiloPazienteException e){return false;}});
            var second=pool.submit(()->{gate.await();try{service.collega(desk,b,CF,NASCITA,null);return true;}catch(ConflittoProfiloPazienteException e){return false;}});
            gate.countDown();assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertEquals("1",f.valore("SELECT COUNT(*) FROM paziente WHERE codice_fiscale='TSTTST90A01A000Q' AND id_utente>=?",Math.min(a,b)));
    }
    @Test void realHttpCreationThenDeskListAndMedicalDetailUseSameId()throws Exception {
        int id=medico();String before=account(id);
        var actor=sessions.create(desk,0L);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        String origin="http://127.0.0.1:"+server.getAddress().getPort();
        var config=new it.medbook.config.AuthConfig("local-http",origin);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var identities=new UtenteRepository(f.factory);
        server.createContext("/api/pazienti",new AutorizzazioneHandler(new it.medbook.controller.PazienteDeskController(service,mapper),auth,sessions,config,mapper));
        server.createContext("/api/medici",new AutorizzazioneHandler(new it.medbook.controller.MedicoController(f.service,identities,mapper),auth,sessions,config,mapper));
        server.start();
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/pazienti/"+id+"/collegamento"))
                .header("Cookie",config.cookieName()+"="+actor.id()).header("Origin",origin).header("X-CSRF-Token",actor.csrfToken()).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"codiceFiscale\":\"TSTTST90A01A000Q\",\"dataNascita\":\"1990-01-01\"}")).build();
            var result=client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(201,result.statusCode());assertEquals(id,mapper.readTree(result.body()).get("idPaziente").intValue());
            for(String path:List.of("/api/pazienti?idPaziente="+id,"/api/medici/"+id)) {
                var get=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+path)).header("Cookie",config.cookieName()+"="+actor.id()).GET().build();
                var response=client.send(get,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());var json=mapper.readTree(response.body());
                if(path.startsWith("/api/pazienti")){assertEquals(id,json.get("risultati").get(0).get("idPaziente").intValue());assertTrue(json.get("risultati").get(0).get("medico").booleanValue());}
                else {assertTrue(json.get("paziente").booleanValue());assertFalse(json.get("medico").has("codiceFiscale"));}
            }
            assertEquals(before,account(id));
        } finally {server.stop(0);}
    }

}
