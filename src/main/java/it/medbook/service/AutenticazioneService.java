package it.medbook.service;

import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import java.sql.SQLException;
import java.util.*;

public final class AutenticazioneService {
    private final UtenteRepository repository;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    private final String dummyHash;
    public AutenticazioneService(UtenteRepository repository, PasswordHasher hasher, SessionStore sessions) {
        this.repository = Objects.requireNonNull(repository);
        this.hasher = Objects.requireNonNull(hasher);
        this.sessions = Objects.requireNonNull(sessions);
        char[] dummy = UUID.randomUUID().toString().toCharArray();
        try { dummyHash = hasher.hash(dummy); }
        finally { Arrays.fill(dummy, '\0'); }
    }
    /*
     * Login: lettura, KDF sempre a costo equivalente, poi credenziali. Password errata o account
     * ignoto: 401 anche con anomalie; password corretta e dati anomali: 500 senza sessione;
     * DISABILITATO: 401 indistinguibile. Solo ATTIVO canonico riceve sessione della generazione
     * confermata.
     */
    public Optional<SessionStore.Session> login(String email, char[] password) throws SQLException {
        if (email == null || email.isBlank() || email.length() > 255 || password == null
                || password.length == 0 || password.length > 1024) {
            throw new IllegalArgumentException("Richiesta non valida.");
        }
        Optional<UtenteRepository.CredenzialiLogin> account = repository.leggiCredenzialiLogin(email);
        String hash = account.map(UtenteRepository.CredenzialiLogin::passwordHash).orElse(null);
        boolean supported = hasher.isSupported(hash);
        // Anche account mancanti, hash malformati o dati anomali eseguono una KDF con lo stesso costo.
        boolean valid = hasher.verify(password, supported ? hash : dummyHash);
        if (!valid || !supported || account.isEmpty()) { return Optional.empty(); }
        UtenteRepository.CredenzialiLogin credenziali = account.get();
        if (!credenziali.anomalie().isEmpty()) { throw new AnomaliaDatiAccountException(); }
        if (!UtenteRepository.ATTIVO.equals(credenziali.statoAccesso())) { return Optional.empty(); }
        return Optional.of(sessions.create(credenziali.idUtente(), credenziali.generazione().getAsLong()));
    }
    /*
     * Sessione utilizzabile solo con account esistente, ATTIVO esatto e stessa generazione
     * confermata. Altrimenti revoca e 401; anomalie revocano e generano eccezione.
     */
    public Optional<UtenteAutenticatoResponse> identity(SessionStore.Session session) throws SQLException {
        Optional<UtenteRepository.StatoSessione> stato;
        try { stato = repository.findSessione(session.idUtente()); }
        catch (AnomaliaDatiAccountException e) { sessions.revoke(session.id()); throw e; }
        if (stato.isEmpty() || stato.get().generazione() != session.generazione()) {
            sessions.revoke(session.id());
            return Optional.empty();
        }
        return Optional.of(stato.get().identita());
    }

    /* Sessione fallita nella lettura preliminare già revocata: 401, oppure 500 per dati anomali. */
    public static final class SessioneNonValidaException extends RuntimeException {
        private final boolean anomalia;
        SessioneNonValidaException(boolean anomalia) { super("Autenticazione richiesta."); this.anomalia = anomalia; }
        public boolean anomalia() { return anomalia; }
    }
    /** §6 point 5: 403. */
    public static final class PasswordAttualeErrataException extends RuntimeException {
        PasswordAttualeErrataException() { super("Password attuale non corretta."); }
    }
    /* Input invalido: 400 col messaggio previsto. */
    public static final class NuovaPasswordUgualeException extends IllegalArgumentException {
        NuovaPasswordUgualeException() { super("La nuova password deve essere diversa dall'attuale."); }
    }

    /*
     * Cambio sul solo account della sessione, mai dichiarato nel payload. KDF fuori transazione;
     * nuova generazione solo con commit confermato. Errore precedente propagato senza
     * invalidazioni; commit ambiguo come eccezione di esito incerto.
     */
    public long cambiaPassword(SessionStore.Session session, char[] attuale, char[] nuova) throws SQLException {
        if (attuale == null || nuova == null) { throw new IllegalArgumentException("Richiesta non valida."); }
        Optional<UtenteRepository.DatiPassword> letti;
        try { letti = repository.leggiPerCambioPassword(session.idUtente()); }
        catch (AnomaliaDatiAccountException e) { sessions.revoke(session.id()); throw new SessioneNonValidaException(true); }
        if (letti.isEmpty() || !UtenteRepository.ATTIVO.equals(letti.get().statoAccesso())
                || letti.get().generazione() != session.generazione()) {
            sessions.revoke(session.id());
            throw new SessioneNonValidaException(false);
        }
        UtenteRepository.DatiPassword dati = letti.get();
        if (!hasher.verify(attuale, dati.passwordHash())) { throw new PasswordAttualeErrataException(); }
        /* Politica scelta dalla categoria riletta prima del cambiamento. */
        if (dati.rigorosa()) { ValidazioneAccount.password(nuova); } else { ValidazioneAccount.passwordPaziente(nuova); }
        if (hasher.verify(nuova, dati.passwordHash())) { throw new NuovaPasswordUgualeException(); }
        String nuovoHash = hasher.hash(nuova);
        return repository.aggiornaPassword(session.idUtente(), session.generazione(), dati.rigorosa(), nuovoHash);
    }
}
