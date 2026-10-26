package it.medbook.security;

import java.time.*;
import java.util.*;

/**
 * Finestra fissa di 5 minuti per peer TCP, indipendente dall'esistenza dell'account.
 * Conta anche login riusciti; esaurimento della capacità = rifiuto, mai bypass.
 * Dietro reverse proxy il peer può essere comune a tutti i client: non fidarsi
 * di X-Forwarded-For senza una successiva configurazione esplicita di proxy fidati.
 */
public final class LoginThrottle {
    private record Attempts(Instant start, int count) { }
    private final Map<String, Attempts> attempts = new HashMap<>();
    private final Clock clock;
    private final int capacity, limit;
    public LoginThrottle(Clock clock, int capacity, int limit) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity <= 0 || limit <= 0) { throw new IllegalArgumentException("Limiti non validi."); }
        this.capacity = capacity; this.limit = limit;
    }
    public synchronized boolean allow(String address) {
        Instant now = clock.instant();
        attempts.values().removeIf(a -> !now.isBefore(a.start.plus(Duration.ofMinutes(5))));
        Attempts previous = attempts.get(address);
        if (previous == null && attempts.size() >= capacity) { return false; }
        if (previous != null && previous.count >= limit) { return false; }
        attempts.put(address, previous == null ? new Attempts(now, 1)
                : new Attempts(previous.start, previous.count + 1));
        return true;
    }
}
