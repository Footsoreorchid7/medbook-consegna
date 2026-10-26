package it.medbook.e2e;

import java.util.Map;
import java.util.Objects;

/*
 * Ambiente per classe: launcher, catalogo esclusivo, storage e HTTP creati in ordine e rilasciati
 * al contrario. Ogni pulizia viene tentata; errori aggregati e cataloghi incerti segnalati, mai
 * eliminati.
 */
public final class E2EAmbiente implements AutoCloseable {
    private final E2ELauncher launcher;
    private E2ECatalogo catalogo;
    private E2EStorage storage;
    private E2EServer server;

    private E2EAmbiente(E2ELauncher launcher) { this.launcher = launcher; }

    public static E2EAmbiente avvia(E2EConfigurazione.Completa cfg) throws Exception { return avvia(cfg, E2EGuasti.NESSUNO); }

    static E2EAmbiente avvia(E2EConfigurazione.Completa cfg, E2EGuasti guasti) throws Exception {
        E2EAmbiente a = new E2EAmbiente(new E2ELauncher(Objects.requireNonNull(cfg), guasti));
        try {
            a.catalogo = a.launcher.creaCatalogo();
            a.storage = new E2EStorage(guasti);
            a.server = new E2EServer(a.catalogo.factory(), a.storage, guasti);
            return a;
        } catch (Exception | Error e) {
            try { a.close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public E2ELauncher launcher() { return launcher; }
    public E2ECatalogo catalogo() { return catalogo; }
    public E2EStorage storage() { return storage; }
    public E2EServer server() { return server; }

    @Override public void close() throws Exception {
        Exception errore = null;
        try { if (server != null) { server.close(); } } catch (Exception e) { errore = accumula(errore, e); }
        try { if (storage != null) { storage.close(); } } catch (Exception e) { errore = accumula(errore, e); }
        for (String nome : launcher.catalogiAttivi()) {
            try { launcher.elimina(nome); } catch (Exception e) { errore = accumula(errore, e); }
        }
        for (Map.Entry<String, String> incerto : launcher.catalogiIncerti().entrySet()) {
            if (!launcher.catalogiAttivi().contains(incerto.getKey())) {
                errore = accumula(errore, new IllegalStateException(incerto.getValue()));
            }
        }
        if (errore != null) { throw errore; }
    }

    private static Exception accumula(Exception primo, Exception nuovo) {
        if (primo == null) { return nuovo; }
        primo.addSuppressed(nuovo);
        return primo;
    }
}
