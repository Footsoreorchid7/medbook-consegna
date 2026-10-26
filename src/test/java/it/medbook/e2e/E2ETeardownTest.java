package it.medbook.e2e;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Pulizia ordinata senza database: tutti i passi sono tentati una volta; primo Throwable rilanciato
 * con identità e tipo originali, successivi soppressi in ordine senza autosoppressione. La
 * proprietà globale viene ripristinata: test sequenziali.
 */
class E2ETeardownTest {
    static final String PROPRIETA = "medbook.e2e.osservazioni";

    private static E2ETeardown.Passo registra(List<String> eseguiti, String nome, Throwable lancia) {
        return () -> {
            eseguiti.add(nome);
            if (lancia instanceof Exception e) { throw e; }
            if (lancia instanceof Error e) { throw e; }
        };
    }

    /* Un AssertionError iniziale non impedisce i passi successivi e resta l'errore principale. */
    @Test void assertionErrorInTheFirstStepDoesNotStopTheOthers() {
        List<String> eseguiti = new ArrayList<>();
        AssertionError asserzione = new AssertionError("asserzione fallita (iniettata)");
        AssertionError e = assertThrows(AssertionError.class, () -> E2ETeardown.esegui(
                registra(eseguiti, "tsv", asserzione), registra(eseguiti, "fixture", null), registra(eseguiti, "ambiente", null)));
        assertSame(asserzione, e);
        assertEquals(0, e.getSuppressed().length);
        assertEquals(List.of("tsv", "fixture", "ambiente"), eseguiti);
    }

    /* IOException iniziale resta principale anche se una chiusura successiva genera AssertionError. */
    @Test void initialIoExceptionStaysMainWhenALaterCloseThrowsAnError() {
        List<String> eseguiti = new ArrayList<>();
        IOException tsv = new IOException("TSV non scritto (iniettato)");
        AssertionError chiusura = new AssertionError("chiusura fallita (iniettata)");
        IOException e = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                registra(eseguiti, "tsv", tsv), registra(eseguiti, "fixture", chiusura), registra(eseguiti, "ambiente", null)));
        assertSame(tsv, e, "tipo e identità del primo Throwable preservati");
        assertEquals(1, e.getSuppressed().length);
        assertSame(chiusura, e.getSuppressed()[0]);
        assertEquals(List.of("tsv", "fixture", "ambiente"), eseguiti);
    }

    /* Stessa istanza lanciata due volte: nessuna autosoppressione o IllegalArgumentException. */
    @Test void theSameInstanceThrownTwiceIsNeverSuppressedOntoItself() {
        List<String> eseguiti = new ArrayList<>();
        IllegalStateException condivisa = new IllegalStateException("stessa istanza (iniettata)");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> E2ETeardown.esegui(
                registra(eseguiti, "uno", condivisa), registra(eseguiti, "due", condivisa), registra(eseguiti, "tre", null)));
        assertSame(condivisa, e);
        assertEquals(0, e.getSuppressed().length, "nessuna autosoppressione");
        assertEquals(List.of("uno", "due", "tre"), eseguiti);
        /* Una stessa istanza successiva viene allegata una sola volta. */
        IOException primo = new IOException("primo (iniettato)");
        RuntimeException ripetuta = new RuntimeException("ripetuta (iniettata)");
        IOException f = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                registra(new ArrayList<>(), "a", primo), registra(new ArrayList<>(), "b", ripetuta), registra(new ArrayList<>(), "c", ripetuta)));
        assertSame(primo, f);
        assertArrayEquals(new Throwable[]{ripetuta}, f.getSuppressed());
    }

    /* Errori distinti soppressi nell'ordine dei passi, ciascuno tentato esattamente una volta. */
    @Test void differentErrorsAreSuppressedInOrderAndEveryStepRunsOnce() {
        List<String> eseguiti = new ArrayList<>();
        IOException tsv = new IOException("tsv (iniettato)");
        IllegalStateException legacy = new IllegalStateException("legacy (iniettato)");
        AssertionError fixture = new AssertionError("fixture (iniettato)");
        OutOfMemoryError ambiente = new OutOfMemoryError("ambiente (simulato)");
        IOException e = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                registra(eseguiti, "tsv", tsv), registra(eseguiti, "legacy", legacy), registra(eseguiti, "ok", null),
                registra(eseguiti, "fixture", fixture), registra(eseguiti, "ambiente", ambiente)));
        assertSame(tsv, e);
        Throwable[] soppressi = e.getSuppressed();
        assertEquals(3, soppressi.length);
        assertSame(legacy, soppressi[0]);
        assertSame(fixture, soppressi[1]);
        assertSame(ambiente, soppressi[2]);
        assertEquals(List.of("tsv", "legacy", "ok", "fixture", "ambiente"), eseguiti, "ogni passo tentato una sola volta, in ordine");
        assertDoesNotThrow(() -> E2ETeardown.esegui(registra(eseguiti, "solo", null)));
        assertEquals(1, eseguiti.stream().filter("solo"::equals).count());
    }

    @Test void aRealTsvFailureStillReleasesListenerAndStorage() throws Exception {
        String precedente = System.getProperty(PROPRIETA);
        AtomicBoolean avviato = new AtomicBoolean();
        Path bersaglio = null;
        E2EStorage storage = null;
        E2EServer server = null;
        try {
            bersaglio = Files.createTempFile("medbook-e2e-tsv-non-directory-", ".txt");
            storage = new E2EStorage();
            server = new E2EServer(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()), storage);
            Path base = storage.base();
            int porta = server.base().getPort();
            /* La destinazione è un file regolare: createDirectories fallisce come una vera scrittura. */
            System.setProperty(PROPRIETA, bersaglio.toString());
            E2ESegnalazioni v9 = new E2ESegnalazioni(new E2EStato(catalogoSintetico(), storage.radice()), getClass());
            E2EServer s = server; E2EStorage st = storage;
            IOException e = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                    () -> { avviato.set(true); v9.scriviTsv(); }, s::close, st::close));
            assertEquals(0, e.getSuppressed().length, e.toString());
            try (Socket socket = new Socket()) {
                assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener chiuso");
            }
            assertFalse(Files.exists(base, LinkOption.NOFOLLOW_LINKS), "storage rimosso");
            assertTrue(Files.isRegularFile(bersaglio), "nessuna scrittura parziale al posto del file");
        } finally {
            if (precedente == null) { System.clearProperty(PROPRIETA); } else { System.setProperty(PROPRIETA, precedente); }
            if (!avviato.get()) { /* Solo se la pulizia in prova non è mai iniziata. */
                if (server != null) { server.close(); }
                if (storage != null) { storage.close(); }
            }
            if (bersaglio != null) { Files.deleteIfExists(bersaglio); }
        }
    }

    /* Catalogo mai connesso: la scrittura TSV non accede al database. */
    static E2ECatalogo catalogoSintetico() {
        Map<String, String> env = new HashMap<>();
        env.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:33861/medbook_test_e2e_template_nuovo");
        env.put("MEDBOOK_E2E_SERVER_UUID", "00000000-0000-0000-0000-000000000000");
        env.put("MEDBOOK_E2E_LAUNCHER_USERNAME", "l"); env.put("MEDBOOK_E2E_LAUNCHER_PASSWORD", "x");
        env.put("MEDBOOK_E2E_USERNAME", "r"); env.put("MEDBOOK_E2E_PASSWORD", "y");
        var cfg = (E2EConfigurazione.Completa) E2EConfigurazione.leggi(env);
        return new E2ECatalogo("medbook_test_e2e_r20310110080000_0000000000000000", cfg);
    }
}
