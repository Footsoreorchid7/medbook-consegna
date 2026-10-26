package it.medbook.e2e;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Guasti su listener e storage temporanei senza database o Docker; la factory sintetica non apre
 * connessioni durante la costruzione.
 */
class RisorseLocaliGuastiTest {
    private static final ConnectionFactory NESSUN_DATABASE = new ConnectionFactory(TestDatabaseConnections.simulatedConfig());

    /* Registra ogni fase e fallisce in quella selezionata. */
    private static final class Guasto implements E2EGuasti {
        final Map<Fase, String> viste = new EnumMap<>(Fase.class);
        private final Fase fallisci;
        Guasto(Fase fallisci) { this.fallisci = fallisci; }
        @Override public void fase(Fase fase, String dettaglio) {
            viste.put(fase, dettaglio);
            if (fase == fallisci) { throw new Iniettato("guasto iniettato in " + fase); }
        }
    }

    @Test void serverFailingBeforeStartReleasesTheBoundListener() throws Exception {
        Guasto g = new Guasto(E2EGuasti.Fase.SERVER_IN_ASCOLTO);
        try (E2EStorage storage = new E2EStorage()) {
            assertThrows(E2EGuasti.Iniettato.class, () -> new E2EServer(NESSUN_DATABASE, storage, g));
            assertPortaChiusa(Integer.parseInt(g.viste.get(E2EGuasti.Fase.SERVER_IN_ASCOLTO)));
        }
    }

    @Test void storageFailingAfterCreationRemovesItsDirectory() {
        Guasto g = new Guasto(E2EGuasti.Fase.STORAGE_CREATO);
        assertThrows(E2EGuasti.Iniettato.class, () -> new E2EStorage(g));
        Path creata = Path.of(g.viste.get(E2EGuasti.Fase.STORAGE_CREATO));
        assertTrue(creata.getFileName().toString().startsWith(E2EStorage.PREFISSO));
        assertFalse(Files.exists(creata, LinkOption.NOFOLLOW_LINKS), "directory rimossa dopo il guasto");
    }

    @Test void serverAndStorageCloseAreIdempotentAndReleaseEverything() throws Exception {
        E2EStorage storage = new E2EStorage();
        Path base = storage.base();
        E2EServer server = new E2EServer(NESSUN_DATABASE, storage);
        int porta = server.base().getPort();
        try (E2EClient client = new E2EClient(server)) {
            assertEquals(401, client.get("/api/auth/me").status(), "server reale in ascolto");
        }
        server.close(); server.close();
        assertPortaChiusa(porta);
        storage.close(); storage.close();
        assertFalse(Files.exists(base, LinkOption.NOFOLLOW_LINKS));
    }

    @Test void storageCleanupRefusesDirectoriesItDidNotCreate() throws Exception {
        Path estranea = Files.createTempDirectory("medbook-altro-").toRealPath();
        try {
            assertThrows(java.io.IOException.class, () -> E2EStorage.rimuovi(estranea));
            assertTrue(Files.isDirectory(estranea), "directory estranea intatta");
        } finally {
            Files.delete(estranea);
        }
    }

    private static void assertPortaChiusa(int porta) throws Exception {
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener rilasciato");
        }
    }
}
