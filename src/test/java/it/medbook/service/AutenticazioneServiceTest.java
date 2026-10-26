package it.medbook.service;

import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutenticazioneServiceTest {
    private static final PasswordHasher HASHER = new PasswordHasher();
    private static String hash;
    private final FakeUtenteRepository repository = new FakeUtenteRepository();
    private final SessionStore store = new SessionStore(new MutableClock(),10);
    private AutenticazioneService service;
    private final UtenteAutenticatoResponse identity = new UtenteAutenticatoResponse(7,"Nome","Test",true,true,RuoloGestionale.AMMINISTRATORE);
    @BeforeAll static void prepareHash() { hash = HASHER.hash("Solo-test-service".toCharArray()); }
    @BeforeEach void prepare() {
        repository.account = new UtenteRepository.Credenziali(identity,hash); repository.identity=identity;
        service = new AutenticazioneService(repository,HASHER,store);
    }
    @Test void loginLooksUpEmailOnceAndCreatesSessionForVerifiedUser() throws Exception {
        var session = service.login("test@example.invalid","Solo-test-service".toCharArray()).orElseThrow();
        assertEquals(7,session.idUtente()); assertEquals(1,repository.emailCalls);
        assertEquals("test@example.invalid",repository.requestedEmail); assertEquals(1,store.size());
    }
    @Test void incorrectPasswordDoesNotCreateSession() throws Exception {
        assertTrue(service.login("test@example.invalid","Errata".toCharArray()).isEmpty()); assertEquals(0,store.size());
    }
    @Test void missingUserDoesNotCreateSession() throws Exception {
        repository.account=null;
        assertTrue(service.login("missing@example.invalid","Errata".toCharArray()).isEmpty()); assertEquals(0,store.size());
    }
    @Test void unsupportedAndNullDatabaseHashesNeverAuthenticate() throws Exception {
        for(String value : new String[]{null,"hash-fittizio","Solo-test-service","pbkdf2-sha256$v1$600000$invalid$invalid"}) {
            repository.account=new UtenteRepository.Credenziali(identity,value);
            assertTrue(service.login("test@example.invalid","Solo-test-service".toCharArray()).isEmpty());
        }
        assertEquals(0,store.size());
    }
    @Test void invalidInputsNeverReachRepository() {
        for(String value : new String[]{null,""," ","x".repeat(256)}) {
            assertThrows(IllegalArgumentException.class, () -> service.login(value,"password".toCharArray()));
        }
        for(char[] value : new char[][]{null,new char[0],new char[1025]}) {
            assertThrows(IllegalArgumentException.class, () -> service.login("test@example.invalid",value));
        }
        assertEquals(0,repository.emailCalls);
    }
    @Test void sqlErrorIsPropagatedWithoutCreatingSession() {
        repository.failure=new SQLException("Dettaglio simulato");
        assertSame(repository.failure,assertThrows(SQLException.class, () -> service.login("test@example.invalid","password".toCharArray())));
        assertEquals(0,store.size());
    }
    @Test void identityAlwaysReloadsProfilesAndRoleFromDatabase() throws Exception {
        var session=store.create(7, 0L);
        assertEquals(identity,service.identity(session).orElseThrow());
        var changed=new UtenteAutenticatoResponse(7,"Nome","Test",false,true,RuoloGestionale.OPERATORE_DESK);
        repository.identity=changed;
        assertEquals(changed,service.identity(session).orElseThrow());
        assertEquals(2,repository.idCalls); assertEquals(7,repository.requestedId);
    }
    @Test void missingIdentityRevokesSession() throws Exception {
        var session=store.create(7, 0L); repository.identity=null;
        assertTrue(service.identity(session).isEmpty()); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void identitySqlErrorIsPropagated() {
        var session=store.create(7, 0L); repository.failure=new SQLException("Errore simulato");
        assertSame(repository.failure,assertThrows(SQLException.class, () -> service.identity(session)));
    }
}
