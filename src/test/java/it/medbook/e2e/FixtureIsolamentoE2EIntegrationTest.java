package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Isolamento su due cataloghi esclusivi della stessa istanza: le modifiche all'uno non raggiungono
 * l'altro; verifica anche cleanup e guasti.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixtureIsolamentoE2EIntegrationTest {
    private E2EConfigurazione.Completa cfg;
    private E2ELauncher osservatore;
    private Set<String> iniziali;
    private E2EAmbiente a, b;
    private E2EFixtureClinica fa, fb;

    @BeforeAll void prepara() throws Exception {
        cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        osservatore = new E2ELauncher(cfg);
        iniziali = osservatore.catalogiE2ESulServer();
        a = E2EAmbiente.avvia(cfg);
        fa = E2EFixtureClinica.prepara(a, new E2EManifest());
        b = E2EAmbiente.avvia(cfg);
        fb = E2EFixtureClinica.prepara(b, new E2EManifest());
    }

    @AfterAll void chiudi() throws Exception {
        Exception errore = null;
        for (AutoCloseable r : new AutoCloseable[]{fa, a, fb, b}) {
            try { if (r != null) { r.close(); } } catch (Exception e) { if (errore == null) { errore = e; } else { errore.addSuppressed(e); } }
        }
        if (errore != null) { throw errore; }
        if (osservatore != null) { assertEquals(iniziali, osservatore.catalogiE2ESulServer(), "nessun catalogo residuo"); }
    }

    @Test void mutationsOnOneFixtureNeverReachTheOther() throws Exception {
        assertNotEquals(a.catalogo().nome(), b.catalogo().nome());
        E2EStato sa = new E2EStato(a), sb = new E2EStato(b);
        E2EStato.Istantanea bPrima = sb.istantanea();
        E2EStato.Istantanea aPrima = sa.istantanea();

        E2EFixtureClinica.Attore dkA = fa.attore("DK");
        assertEquals(200, dkA.client().postJson("/api/rimborsi/" + fa.rimborsoEC() + "/approva", Map.of()).status());
        assertEquals(200, fa.attore("AD").client().putJson("/api/medici/" + fa.attore("MI").id() + "/stato", Map.of("stato", "INATTIVO")).status(),
                "F-IN: inattivazione raggiungibile via API");
        assertEquals("APPROVATO", sa.riga("SELECT stato FROM rimborso WHERE id_rimborso = ?", fa.rimborsoEC()).get("stato"));
        assertEquals("INATTIVO", sa.riga("SELECT stato FROM medico WHERE id_utente = ?", fa.attore("MI").id()).get("stato"));
        assertNotEquals(aPrima, sa.istantanea(), "A è cambiata");
        assertEquals(bPrima, sb.istantanea(), "B invariata dopo le modifiche su A");

        E2EStato.Istantanea aDopo = sa.istantanea();
        E2EFixtureClinica.Attore dkB = fb.attore("DK");
        assertEquals(200, dkB.client().postJson("/api/rimborsi/" + fb.rimborsoEC() + "/respingi", Map.of()).status());
        assertEquals("RESPINTO", sb.riga("SELECT stato FROM rimborso WHERE id_rimborso = ?", fb.rimborsoEC()).get("stato"));
        assertEquals("ATTIVO", sb.riga("SELECT stato FROM medico WHERE id_utente = ?", fb.attore("MI").id()).get("stato"));
        assertEquals(aDopo, sa.istantanea(), "A invariata dopo le modifiche su B");
        assertEquals("APPROVATO", sa.riga("SELECT stato FROM rimborso WHERE id_rimborso = ?", fa.rimborsoEC()).get("stato"));
    }

    @Test void identitiesAndSessionsBelongToOneFixtureOnly() throws Exception {
        for (String c : E2EFixtureClinica.ATTORI) {
            assertNotEquals(fa.attore(c).email(), fb.attore(c).email(), c);
            assertFalse(fb.manifest().appartiene(fa.attore(c).email()), c);
        }
        /* Stesso ID numerico in cataloghi diversi: la sessione di A è sconosciuta al server B. */
        E2EClient.Credenziali diA = fa.attore("P1").client().credenziali().orElseThrow();
        try (E2EClient replay = E2EClient.conCredenziali(b.server(), diA)) {
            assertEquals(401, replay.get("/api/auth/me").status());
        }
        assertEquals(200, fa.attore("P1").client().get("/api/auth/me").status(), "la sessione resta valida sul proprio server");
        assertEquals(0, new E2EStato(b).conta("SELECT COUNT(*) FROM utente WHERE email = ?", fa.attore("P1").email()));
    }

    @Test void closingAnEnvironmentReleasesCatalogStorageListenerAndClients() throws Exception {
        E2EAmbiente c = E2EAmbiente.avvia(cfg);
        String catalogo = c.catalogo().nome();
        Path storage = c.storage().base();
        int porta = c.server().base().getPort();
        E2EClient client;
        try (E2EFixtureClinica fc = E2EFixtureClinica.prepara(c, new E2EManifest())) {
            assertEquals(3, new E2EStato(c).file().size(), "referti nello storage");
            client = fc.attore("P1").client();
        } finally {
            c.close();
        }
        assertFalse(osservatore.esiste(catalogo), "catalogo eliminato");
        assertFalse(Files.exists(storage, LinkOption.NOFOLLOW_LINKS), "storage e referti rimossi");
        assertPortaChiusa(porta);
        assertThrows(Exception.class, () -> client.get("/api/auth/me"), "client chiuso con la fixture");
    }

    @Test void failuresWhileCreatingTheFixtureAreCleanedUp() throws Exception {
        for (String passo : new String[]{"prenotazioni", "referti"}) {
            Set<String> prima = osservatore.catalogiE2ESulServer();
            E2EAmbiente d = E2EAmbiente.avvia(cfg);
            String catalogo = d.catalogo().nome();
            Path storage = d.storage().base();
            int porta = d.server().base().getPort();
            try {
                E2EGuasti.Iniettato e = assertThrows(E2EGuasti.Iniettato.class, () -> {
                    E2EFixtureClinica f = E2EFixtureClinica.preparaT0(d, new E2EManifest(), n -> {
                        if (n.equals(passo)) { throw new E2EGuasti.Iniettato("guasto iniettato prima di " + n); }
                    });
                    try { f.passaAT1(); } finally { f.close(); }
                });
                assertTrue(e.getMessage().contains(passo));
                assertTrue(osservatore.esiste(catalogo), "dati parziali presenti fino alla chiusura");
            } finally {
                d.close();
            }
            assertFalse(osservatore.esiste(catalogo), passo + ": catalogo eliminato");
            assertFalse(Files.exists(storage, LinkOption.NOFOLLOW_LINKS), passo + ": storage rimosso");
            assertPortaChiusa(porta);
            assertEquals(prima, osservatore.catalogiE2ESulServer(), passo);
        }
    }

    private static void assertPortaChiusa(int porta) throws Exception {
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener rilasciato");
        }
    }
}
