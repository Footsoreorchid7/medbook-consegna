package it.medbook.repository;

import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResetPasswordMedicoIntegrationTest {
    final MedicoRepositoryIntegrationTest f = new MedicoRepositoryIntegrationTest();
    SessionStore sessions; MedicoService service; AutenticazioneService auth;
    static final String OLD=" Password precedente ", NEW=" Password successiva ";
    @BeforeEach void setup() throws Exception {
        f.setup(); sessions=new SessionStore(Clock.systemUTC(),100);
        service=new MedicoService(f.repo,f.hasher,sessions);
        auth=new AutenticazioneService(new UtenteRepository(f.factory),f.hasher,sessions);
    }
    @AfterEach void cleanup() throws Exception { f.cleanup(); }
    int medico() throws Exception {
        int id=f.user(null,true,true);
        f.execute("UPDATE utente SET password_hash=? WHERE id_utente=?",f.hasher.hash(OLD.toCharArray()),id);
        return id;
    }
    @Test void resetInvalidatesEverySessionAndKeepsProfilesAndStates() throws Exception {
        int id=medico(); String email=f.valore("SELECT email FROM utente WHERE id_utente=?",id);
        var a=auth.login(email,OLD.toCharArray()).orElseThrow();var b=auth.login(email,OLD.toCharArray()).orElseThrow();
        var result=service.reimpostaPassword(f.admin,id,NEW.toCharArray());
        assertEquals(id,result.idMedico());assertTrue(result.sessioniInvalidate());
        assertTrue(sessions.find(a.id()).isEmpty());assertTrue(sessions.find(b.id()).isEmpty());
        assertTrue(auth.identity(a).isEmpty());assertTrue(auth.identity(b).isEmpty());
        assertTrue(auth.login(email,OLD.toCharArray()).isEmpty());
        var fresh=auth.login(email,NEW.toCharArray()).orElseThrow();assertTrue(auth.identity(fresh).orElseThrow().paziente());
        assertEquals("ATTIVO",f.valore("SELECT stato FROM medico WHERE id_utente=?",id));
        assertEquals("ATTIVO",f.valore("SELECT stato_accesso FROM utente WHERE id_utente=?",id));
        assertNull(f.valore("SELECT ruolo_gestionale FROM utente WHERE id_utente=?",id));
    }
    @Test void onlyAdminAndOnlyMedicalTargets() throws Exception {
        int id=medico(),desk=f.user("OPERATORE_DESK",false,false),patient=f.user(null,false,true);
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reimpostaPassword(desk,id,NEW.toCharArray()));
        assertThrows(OperazioneNonAutorizzataException.class,()->service.reimpostaPassword(patient,id,NEW.toCharArray()));
        assertThrows(RisorsaNonTrovataException.class,()->service.reimpostaPassword(f.admin,patient,NEW.toCharArray()));
        assertThrows(IllegalArgumentException.class,()->service.reimpostaPassword(f.admin,id,"short".toCharArray()));
        assertThrows(IllegalArgumentException.class,()->service.reimpostaPassword(f.admin,id,new char[1025]));
        assertThrows(IllegalArgumentException.class,()->service.reimpostaPassword(f.admin,id,('x'+"123456789012"+'\uD800').toCharArray()));
        assertEquals("0",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));
    }
    @Test void disabledAndInactiveStayUnchangedAndGenerationCannotOverflow() throws Exception {
        int id=medico();f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?",id);
        f.execute("UPDATE medico SET stato='INATTIVO' WHERE id_utente=?",id);
        service.reimpostaPassword(f.admin,id,NEW.toCharArray());
        assertEquals("DISABILITATO",f.valore("SELECT stato_accesso FROM utente WHERE id_utente=?",id));
        assertEquals("INATTIVO",f.valore("SELECT stato FROM medico WHERE id_utente=?",id));
        f.execute("UPDATE utente SET generazione_autenticazione=? WHERE id_utente=?",Long.MAX_VALUE,id);
        assertThrows(GenerazioneEsauritaException.class,()->service.reimpostaPassword(f.admin,id,OLD.toCharArray()));
    }
    @Test void concurrentResetsSerializeAndInvalidateOlderGenerations() throws Exception {
        int id=medico();var first=sessions.create(id,0L);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return service.reimpostaPassword(f.admin,id,NEW.toCharArray());});
            var b=pool.submit(()->{start.await();return service.reimpostaPassword(f.admin,id," Altra password valida ".toCharArray());});
            start.countDown();assertTrue(a.get(20,TimeUnit.SECONDS).sessioniInvalidate());assertTrue(b.get(20,TimeUnit.SECONDS).sessioniInvalidate());
        }
        assertEquals("2",f.valore("SELECT generazione_autenticazione FROM utente WHERE id_utente=?",id));
        assertTrue(auth.identity(first).isEmpty());
        var hash=f.valore("SELECT password_hash FROM utente WHERE id_utente=?",id);
        assertTrue(f.hasher.verify(NEW.toCharArray(),hash) ^ f.hasher.verify(" Altra password valida ".toCharArray(),hash));
    }
}
