package it.medbook.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/*
 * La pulizia rilascia catalogo, listener e storage anche quando fallisce il TSV, propagandone
 * l'errore. La proprietà globale delle osservazioni viene ripristinata: eseguire in sequenza, mai
 * in parallelo.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeardownTsvE2EIntegrationTest {
    private E2EConfigurazione.Completa cfg;
    private E2ELauncher osservatore;

    @BeforeAll void prepara() throws Exception {
        cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg != null) { osservatore = new E2ELauncher(cfg); }
    }

    @Test void tsvFailureDoesNotLeaveCatalogListenerOrStorage() throws Exception {
        Set<String> prima = osservatore.catalogiE2ESulServer();
        String precedente = System.getProperty(E2ETeardownTest.PROPRIETA);
        var avviato = new java.util.concurrent.atomic.AtomicBoolean();
        Path bersaglio = null;
        E2EAmbiente ambiente = null;
        String catalogo;
        Path storage;
        int porta;
        try {
            bersaglio = Files.createTempFile("medbook-e2e-tsv-non-directory-", ".txt");
            ambiente = E2EAmbiente.avvia(cfg);
            catalogo = ambiente.catalogo().nome();
            storage = ambiente.storage().base();
            porta = ambiente.server().base().getPort();
            System.setProperty(E2ETeardownTest.PROPRIETA, bersaglio.toString());
            E2ESegnalazioni v9 = new E2ESegnalazioni(new E2EStato(ambiente), getClass());
            E2EAmbiente a = ambiente;
            IOException e = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                    () -> { avviato.set(true); v9.scriviTsv(); }, a::close));
            assertEquals(0, e.getSuppressed().length, e.toString());
        } finally {
            if (precedente == null) { System.clearProperty(E2ETeardownTest.PROPRIETA); }
            else { System.setProperty(E2ETeardownTest.PROPRIETA, precedente); }
            if (bersaglio != null) { Files.deleteIfExists(bersaglio); }
            if (!avviato.get() && ambiente != null) { ambiente.close(); } /* Solo se la pulizia in prova non è mai iniziata. */
        }
        assertFalse(osservatore.esiste(catalogo), "catalogo eliminato");
        assertEquals(prima, osservatore.catalogiE2ESulServer(), "nessun catalogo residuo");
        assertFalse(Files.exists(storage, LinkOption.NOFOLLOW_LINKS), "storage rimosso");
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener chiuso");
        }
    }
}
