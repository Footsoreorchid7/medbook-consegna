package it.medbook.repository;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PazientiDeskIntegrationTest {
    final MedicoRepositoryIntegrationTest f=new MedicoRepositoryIntegrationTest();
    PazienteDeskService service; SessionStore sessions; AutenticazioneService auth; int desk;
    static final String OLD="vecchia password fittizia", NEW=" nuova password paziente ";
    @BeforeEach void setup()throws Exception{f.setup();desk=f.user("OPERATORE_DESK",false,false);sessions=new SessionStore(Clock.systemUTC(),100);service=new PazienteDeskService(new PazienteDeskRepository(f.factory),f.hasher,sessions);auth=new AutenticazioneService(new UtenteRepository(f.factory),f.hasher,sessions);}
    @AfterEach void cleanup()throws Exception{f.cleanup();}
    int paziente()throws Exception{int id=f.user(null,false,true);f.execute("UPDATE utente SET password_hash=? WHERE id_utente=?",f.hasher.hash(OLD.toCharArray()),id);return id;}
    @Test void resetInvalidatesSessionsWithoutChangingEmailProfilesOrAccess()throws Exception{
        int id=paziente();String email=f.valore("SELECT email FROM utente WHERE id_utente=?",id);
        var a=auth.login(email,OLD.toCharArray()).orElseThrow();var b=auth.login(email,OLD.toCharArray()).orElseThrow();
        assertTrue(service.reset(desk,id,NEW.toCharArray()).sessioniInvalidate());
        assertTrue(auth.identity(a).isEmpty());assertTrue(auth.identity(b).isEmpty());assertTrue(sessions.find(a.id()).isEmpty());
        assertTrue(auth.login(email,OLD.toCharArray()).isEmpty());assertTrue(auth.login(email,NEW.toCharArray()).isPresent());
        assertEquals(email,f.valore("SELECT email FROM utente WHERE id_utente=?",id));assertEquals("ATTIVO",f.valore("SELECT stato_accesso FROM utente WHERE id_utente=?",id));
        assertEquals("1",f.valore("SELECT COUNT(*) FROM paziente WHERE id_utente=?",id));assertNull(f.valore("SELECT ruolo_gestionale FROM utente WHERE id_utente=?",id));
    }
    @Test void targetRestrictionsAreCheckedByServerAndAdminDoesNotInheritDesk()throws Exception{
        int plain=paziente(),mixed=f.user(null,true,true),manager=f.user("OPERATORE_DESK",false,true),onlyDoctor=f.user(null,true,false);
        for(int actor:List.of(f.admin,plain,mixed)) {
            assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(actor,plain,NEW.toCharArray()));
            assertThrows(OperazioneNonAutorizzataException.class,()->service.cerca(actor,Map.of()));
        }
        for(int id:List.of(mixed,manager)) {assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(desk,id,NEW.toCharArray()));assertEquals("0",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));}
        assertThrows(RisorsaNonTrovataException.class,()->service.reset(desk,onlyDoctor,NEW.toCharArray()));
        f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?",desk);
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(desk,plain,NEW.toCharArray()));
    }
    @Test void passwordBoundsUnicodeAndDisabledTarget()throws Exception{
        int id=paziente();for(String p:List.of("1234567","x".repeat(1025),"12345678\uD800"))assertThrows(IllegalArgumentException.class,()->service.reset(desk,id,p.toCharArray()));
        f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?",id);
        service.reset(desk,id," 123456 ".toCharArray());assertEquals("DISABILITATO",f.valore("SELECT stato_accesso FROM utente WHERE id_utente=?",id));
        assertTrue(f.hasher.verify(" 123456 ".toCharArray(),f.valore("SELECT password_hash FROM utente WHERE id_utente=?",id)));
        f.execute("UPDATE utente SET generazione_autenticazione=? WHERE id_utente=?",Long.MAX_VALUE,id);
        assertThrows(GenerazioneEsauritaException.class,()->service.reset(desk,id,NEW.toCharArray()));
    }
    @Test void listingPaginationPrefixesAndMixedAccounts()throws Exception{
        String prefix="Desk"+UUID.randomUUID().toString().replace("-","");
        int a=paziente(),b=f.user(null,true,true),c=f.user("OPERATORE_DESK",false,true);
        for(int id:List.of(a,b,c))f.execute("UPDATE utente SET nome=?,cognome='Ricerca',email=? WHERE id_utente=?",prefix,prefix.toLowerCase(Locale.ROOT)+id+"@example.test",id);
        var first=service.cerca(desk,Map.of("nome",prefix,"limite","2"));assertEquals(List.of(a,b),first.risultati().stream().map(x->x.idPaziente()).toList());assertEquals(b,first.prossimoDopoId());assertTrue(first.risultati().get(1).medico());
        var second=service.cerca(desk,Map.of("nome",prefix,"limite","2","dopoId",Integer.toString(b)));assertEquals(c,second.risultati().getFirst().idPaziente());assertTrue(second.risultati().getFirst().gestionale());assertNull(second.prossimoDopoId());
        assertEquals(3,service.cerca(desk,Map.of("email",prefix.toUpperCase(Locale.ROOT))).risultati().size());
        assertTrue(service.cerca(desk,Map.of("nome",prefix+"%")).risultati().isEmpty());
        assertEquals(1,service.cerca(desk,Map.of("idPaziente",Integer.toString(a),"cognome","Ric")).risultati().size());
        for(var filters:List.of(Map.of("limite","51"),Map.of("idPaziente","01"),Map.of("dopoId","-1"),Map.of("ruolo","AMMINISTRATORE"),Map.of("nome"," ")))assertThrows(IllegalArgumentException.class,()->service.cerca(desk,filters));
    }
    @Test void concurrentResetsSerializeAndLinkingCannotBypassEligibility()throws Exception{
        int id=paziente();var old=sessions.create(id,0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return service.reset(desk,id,NEW.toCharArray());});
            var b=pool.submit(()->{gate.await();return service.reset(desk,id,"seconda password".toCharArray());});
            gate.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        assertEquals("2",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));assertTrue(auth.identity(old).isEmpty());
        f.service.collega(f.admin,id,new it.medbook.dto.CollegamentoMedicoRequest("Generale",MedicoRepositoryIntegrationTest.numero()));
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(desk,id,NEW.toCharArray()));
        assertEquals("2",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));
    }
    @Test void resetRacingWithMedicalLinkIsSerializedUnderSameAccountMutex()throws Exception {
        int id=paziente();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var reset=pool.submit(()->{gate.await();try {service.reset(desk,id,NEW.toCharArray());return true;}catch(OperazioneNonAutorizzataException e){return false;}});
            var link=pool.submit(()->{gate.await();return f.service.collega(f.admin,id,new it.medbook.dto.CollegamentoMedicoRequest("Generale",MedicoRepositoryIntegrationTest.numero()));});
            gate.countDown();boolean saved=reset.get(20,TimeUnit.SECONDS);link.get(20,TimeUnit.SECONDS);
            assertEquals(saved?"1":"0",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));
        }
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reset(desk,id,NEW.toCharArray()));
    }

}
