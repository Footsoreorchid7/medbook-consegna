package it.medbook.e2e;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/*
 * Login reali sotto il limitatore reale: massimo dieci tentativi per finestra e peer. Prima di
 * superarlo si avanza solo l'orologio d'autenticazione di cinque minuti e un secondo; mai quello di
 * dominio. Un 429 esterno al contatore produce lo stesso avanzamento e un solo retry. Le sessioni
 * mantengono i limiti di inattività e durata assoluta.
 */
public final class E2EAccessi {
    static final int LIMITE = 10;
    static final Duration FINESTRA = Duration.ofMinutes(5);
    private final E2EServer server;
    private Instant inizioFinestra;
    private int tentativi;
    private int avanzamenti;

    public E2EAccessi(E2EServer server) { this.server = Objects.requireNonNull(server); }

    /* Client separato con login HTTP reale; chiuso se la preparazione fallisce. */
    public E2EClient accedi(String email, String password) throws IOException, InterruptedException {
        E2EClient client = new E2EClient(server);
        try {
            riserva();
            E2EClient.Risposta r = client.login(email, password);
            if (r.status() == 429) {
                superaFinestra();
                riserva();
                r = client.login(email, password);
            }
            if (r.status() != 200) { throw new IllegalStateException("Login di fixture non riuscito: HTTP " + r.status()); }
            return client;
        } catch (IOException | InterruptedException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /* Numero di avanzamenti dell'orologio d'autenticazione. */
    public int avanzamenti() { return avanzamenti; }

    private void riserva() {
        Instant adesso = server.authClock().instant();
        if (inizioFinestra == null || !adesso.isBefore(inizioFinestra.plus(FINESTRA))) { inizioFinestra = adesso; tentativi = 0; }
        if (tentativi >= LIMITE) { superaFinestra(); inizioFinestra = server.authClock().instant(); tentativi = 0; }
        tentativi++;
    }

    private void superaFinestra() {
        server.authClock().advance(FINESTRA.plusSeconds(1));
        avanzamenti++;
    }
}
