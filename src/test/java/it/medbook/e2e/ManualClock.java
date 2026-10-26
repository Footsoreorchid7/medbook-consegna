package it.medbook.e2e;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Orologio mosso dal thread dei test fra richieste. Componenti di dominio condividono l'istanza;
 * sessioni e limitatori ne hanno una distinta. withZone condivide l'istante tra copie: non crea un
 * orologio indipendente.
 */
public final class ManualClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public ManualClock(Instant start) { this(new AtomicReference<>(Objects.requireNonNull(start)), ZoneOffset.UTC); }

    private ManualClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = Objects.requireNonNull(zone);
    }

    public void set(Instant instant) { now.set(Objects.requireNonNull(instant)); }

    public void advance(Duration duration) {
        if (duration.isNegative()) { throw new IllegalArgumentException("Il clock di test non torna indietro."); }
        now.updateAndGet(i -> i.plus(duration));
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public ManualClock withZone(ZoneId zone) {
        return Objects.requireNonNull(zone).equals(this.zone) ? this : new ManualClock(now, zone);
    }
    @Override public Instant instant() { return now.get(); }
    /* Vero solo per questo orologio e le sue copie di zona. */
    public boolean condivideIstante(ManualClock other) { return other != null && other.now == now; }
}
