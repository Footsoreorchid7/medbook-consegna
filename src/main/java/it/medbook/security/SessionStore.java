package it.medbook.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Singola JVM: riavvio = logout. Scadenza inattiva 30 minuti, assoluta 8 ore.
 * Pulizia lazy a ogni creazione/ricerca/accesso riuscito e lettura della dimensione;
 * la capacità impedisce crescita illimitata e non espelle sessioni attive.
 * Revoca immediata: non cancella retroattivamente una risposta già in corso.
 * Nessuna sessione o segreto deve essere registrato nei log.
 */
public final class SessionStore {
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE = Duration.ofHours(8);
    /* Sessione associata alla generazione letta da dati account confermati; riverificata a ogni uso. */
    public static final class Session {
        private final String id, csrfToken;
        private final int idUtente;
        private final long generazione;
        private final Instant created;
        private Instant lastAccess;
        private Session(String id, String csrfToken, int idUtente, long generazione, Instant now) {
            this.id = id; this.csrfToken = csrfToken; this.idUtente = idUtente; this.generazione = generazione;
            this.created = now; this.lastAccess = now;
        }
        public String id() { return id; }
        public String csrfToken() { return csrfToken; }
        public int idUtente() { return idUtente; }
        public long generazione() { return generazione; }
    }
    private final Clock clock;
    private final int capacity;
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    public SessionStore(Clock clock, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity <= 0) { throw new IllegalArgumentException("Capacità non valida."); }
        this.capacity = capacity;
    }
    /* La generazione viene da dati confermati, mai da un valore futuro atteso; dominio 0..Long.MAX_VALUE. */
    public synchronized Session create(int idUtente, long generazione) {
        if (idUtente <= 0) { throw new IllegalArgumentException("Utente non valido."); }
        if (generazione < 0) { throw new IllegalArgumentException("Generazione non valida."); }
        Instant now = clock.instant();
        cleanup(now);
        if (sessions.size() >= capacity) { throw new CapacityException(); }
        String id;
        do { id = token(); } while (sessions.containsKey(id));
        Session session = new Session(id, token(), idUtente, generazione, now);
        sessions.put(id, session);
        return session;
    }
    public synchronized Optional<Session> find(String id) {
        Instant now = clock.instant();
        cleanup(now);
        return Optional.ofNullable(sessions.get(id));
    }
    /** Chiamare solo dopo autenticazione e, per operazioni modificative, verifica CSRF. */
    public synchronized boolean touch(Session session) {
        Instant now = clock.instant();
        cleanup(now);
        if (sessions.get(session.id) != session) { return false; }
        session.lastAccess = now;
        return true;
    }
    public synchronized void revoke(String id) { sessions.remove(id); }
    /*
     * Rimuove solo sessioni con generazione strettamente inferiore, confrontando long non negativi.
     * Monotonia impedisce a chiamanti tardivi di rimuovere sessioni nuove. È sola pulizia: la
     * validità è garantita dalla verifica database a ogni uso.
     */
    public synchronized void revokeStale(int idUtente, long generazione) {
        sessions.values().removeIf(s -> s.idUtente == idUtente && s.generazione < generazione);
    }
    public synchronized int size() { cleanup(clock.instant()); return sessions.size(); }
    private void cleanup(Instant now) {
        sessions.values().removeIf(s -> !now.isBefore(s.created.plus(ABSOLUTE))
                || !now.isBefore(s.lastAccess.plus(IDLE)));
    }
    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static final class CapacityException extends RuntimeException {
        public CapacityException() { super("Servizio temporaneamente non disponibile."); }
    }
}
