package it.medbook.service;

import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.repository.UtenteRepository.Anomalia;
import it.medbook.security.*;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Ordine login: lettura, KDF, credenziali, anomalie, stato, sessione. Il costo KDF è confrontato
 * con una base PBKDF2 misurata nella stessa JVM; PasswordHasher è finale e non espone contatori.
 */
class AutenticazioneServiceU2Test {
    static final PasswordHasher HASHER = new PasswordHasher();
    static final String PASSWORD = "Solo-test-U2";
    static String hash;
    static long kdfNanos;
    final FakeUtenteRepository repository = new FakeUtenteRepository();
    final SessionStore store = new SessionStore(new MutableClock(), 10);
    final UtenteAutenticatoResponse identity = new UtenteAutenticatoResponse(7, "Nome", "Test", true, false, RuoloGestionale.AMMINISTRATORE);
    AutenticazioneService service;

    @BeforeAll static void baseline() {
        hash = HASHER.hash(PASSWORD.toCharArray());
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 2; i++) { long t = System.nanoTime(); HASHER.verify("x".toCharArray(), hash); best = Math.min(best, System.nanoTime() - t); }
        kdfNanos = best;
    }
    @BeforeEach void prepare() {
        repository.account = new UtenteRepository.Credenziali(identity, hash); repository.identity = identity;
        service = new AutenticazioneService(repository, HASHER, store);
    }
    Optional<SessionStore.Session> timedLogin(String password) throws Exception {
        long t = System.nanoTime();
        try { return service.login("u2@example.invalid", password.toCharArray()); }
        finally { assertTrue(System.nanoTime() - t >= kdfNanos / 2, "KDF eseguita (durata confrontabile con PBKDF2)"); }
    }

    @Test void a_validCredentialsCreateSessionBoundToCommittedGeneration() throws Exception {
        repository.generazione = OptionalLong.of(Long.MAX_VALUE);
        var session = timedLogin(PASSWORD).orElseThrow();
        assertEquals(7, session.idUtente()); assertEquals(Long.MAX_VALUE, session.generazione()); assertEquals(1, store.size());
    }
    @Test void b_wrongPasswordIsEmpty() throws Exception {
        assertTrue(timedLogin("Errata").isEmpty()); assertEquals(0, store.size());
    }
    @Test void c_unknownAccountRunsDummyKdf() throws Exception {
        repository.account = null;
        assertTrue(timedLogin(PASSWORD).isEmpty()); assertEquals(0, store.size());
    }
    @Test void d_disabledAccountIsEmptyWithCorrectPassword() throws Exception {
        repository.statoAccesso = UtenteRepository.DISABILITATO;
        assertTrue(timedLogin(PASSWORD).isEmpty()); assertEquals(0, store.size());
    }
    @ParameterizedTest @EnumSource(Anomalia.class)
    void e_wrongPasswordWithAnomalousDataIsEmptyAfterKdf(Anomalia anomalia) throws Exception {
        anomalo(anomalia);
        assertTrue(timedLogin("Errata").isEmpty()); assertEquals(0, store.size());
    }
    @ParameterizedTest @EnumSource(Anomalia.class)
    void f_correctPasswordWithAnomalousDataThrowsWithoutSession(Anomalia anomalia) {
        anomalo(anomalia);
        assertThrows(AnomaliaDatiAccountException.class, () -> timedLogin(PASSWORD)); assertEquals(0, store.size());
    }
    @Test void f_anomalyTakesPrecedenceOverDisabled() {
        repository.statoAccesso = UtenteRepository.DISABILITATO; repository.generazione = OptionalLong.empty();
        assertThrows(AnomaliaDatiAccountException.class, () -> timedLogin(PASSWORD)); assertEquals(0, store.size());
    }
    @Test void g_realSqlErrorIsPropagatedAndIsNotAnAnomaly() {
        repository.failure = new SQLException("driver");
        var e = assertThrows(SQLException.class, () -> service.login("u2@example.invalid", PASSWORD.toCharArray()));
        assertSame(repository.failure, e); assertFalse(e instanceof AnomaliaDatiAccountException); assertEquals(0, store.size());
    }
    void anomalo(Anomalia anomalia) {
        switch (anomalia) {
            case STATO -> repository.statoAccesso = "attivo";
            case GENERAZIONE -> repository.generazione = OptionalLong.empty();
            case RUOLO -> repository.anomalie = Set.of(Anomalia.RUOLO);
        }
    }

    @Test void i_currentGenerationIsAuthorized() throws Exception {
        repository.generazione = OptionalLong.of(5);
        var session = store.create(7, 5);
        assertEquals(identity, service.identity(session).orElseThrow()); assertTrue(store.find(session.id()).isPresent());
    }
    @Test void j_staleGenerationIsRevoked() throws Exception {
        var session = store.create(7, 4); repository.generazione = OptionalLong.of(5);
        assertTrue(service.identity(session).isEmpty()); assertTrue(store.find(session.id()).isEmpty());
        /* Una sessione revocata non torna valida anche se un altro percorso osserva poi la stessa generazione. */
        assertTrue(service.identity(session).isEmpty());
    }
    @Test void j_newerSessionGenerationThanDatabaseIsAlsoRejected() throws Exception {
        var session = store.create(7, 6); repository.generazione = OptionalLong.of(5);
        assertTrue(service.identity(session).isEmpty()); assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void k_disabledAccountIsRevoked() throws Exception {
        var session = store.create(7, 0); repository.statoAccesso = UtenteRepository.DISABILITATO;
        assertTrue(service.identity(session).isEmpty()); assertTrue(store.find(session.id()).isEmpty());
    }
    @ParameterizedTest @EnumSource(Anomalia.class)
    void anomalousIdentityRevokesThenThrows(Anomalia anomalia) {
        var session = store.create(7, 0); anomalo(anomalia);
        assertThrows(AnomaliaDatiAccountException.class, () -> service.identity(session));
        assertTrue(store.find(session.id()).isEmpty());
    }
    @Test void realSqlErrorOnIdentityDoesNotRevoke() {
        var session = store.create(7, 0); repository.idFailure = new SQLException("driver");
        assertSame(repository.idFailure, assertThrows(SQLException.class, () -> service.identity(session)));
        assertTrue(store.find(session.id()).isPresent(), "un errore infrastrutturale non invalida la sessione");
    }
}
