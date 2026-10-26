package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Verifiche infrastrutturali su Docker reale e catalogo proprio, senza fixture cliniche o guasti
 * iniettati. Configurazione assente facoltativa: skip esplicito; incompleta o obbligatoria: errore.
 */
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfrastrutturaE2EIntegrationTest {
    private static final String PASSWORD = "Password-sintetica-e2e";
    private E2EAmbiente ambiente;
    private E2ELauncher launcher;
    private E2ECatalogo catalogo;
    private E2EStorage storage;
    private E2EServer server;
    private final E2EManifest manifest = new E2EManifest();
    private final List<E2EClient> clienti = new ArrayList<>();

    @BeforeAll void avvia() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        // E2EAmbiente releases what it already created if a later setup step fails.
        ambiente = E2EAmbiente.avvia(cfg);
        launcher = ambiente.launcher(); catalogo = ambiente.catalogo(); storage = ambiente.storage(); server = ambiente.server();
    }

    /* Rilascia listener, storage e cataloghi propri; segnala quelli di appartenenza incerta. */
    @AfterAll void ferma() throws Exception {
        if (ambiente != null) { ambiente.close(); }
    }

    /* I client HTTP vengono chiusi dopo ogni test, anche se fallisce un'asserzione. */
    @AfterEach void chiudiClienti() {
        clienti.forEach(E2EClient::close);
        clienti.clear();
    }

    E2EClient cliente() { return cliente(server); }

    E2EClient cliente(E2EServer destinazione) {
        E2EClient c = new E2EClient(destinazione);
        clienti.add(c);
        return c;
    }

    E2EClient replay(E2EClient.Credenziali credenziali) {
        E2EClient c = E2EClient.conCredenziali(server, credenziali);
        clienti.add(c);
        return c;
    }

    /* Registra un paziente sintetico tramite API; email nel manifest prima della richiesta. */
    String registraPaziente() throws Exception {
        String email = manifest.nuovaEmail("paziente");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", "Fixture"); body.put("cognome", "Sintetica"); body.put("email", email);
        body.put("password", PASSWORD); body.put("codiceFiscale", manifest.nuovoCodiceFiscale());
        body.put("dataNascita", "1990-01-01"); body.put("telefono", null);
        E2EClient.Risposta r = cliente().postJson("/api/auth/register", body);
        assertEquals(201, r.status(), r.testo());
        return email;
    }

    @Test void serverAnswersAPublicRouteOnLoopback() throws Exception {
        assertEquals("127.0.0.1", server.base().getHost());
        E2EClient.Risposta r = cliente().get("/api/prestazioni");
        assertEquals(200, r.status(), r.testo());
        assertTrue(r.json().isArray());
    }

    @Test void protectedRouteWithoutSessionIs401WithExpiredCookie() throws Exception {
        E2EClient.Risposta r = cliente().get("/api/prenotazioni/paziente?idPaziente=1");
        assertEquals(401, r.status());
        assertEquals("{\"error\":\"Autenticazione richiesta.\"}", r.testo());
        assertEquals("no-store", r.header("Cache-Control").orElse(null));
        assertTrue(r.header("Set-Cookie").orElse("").contains("Max-Age=0"));
    }

    @Test void realLoginGivesAUsableSessionAndTheOldCookieIsRefusedAfterLogout() throws Exception {
        String email = registraPaziente();
        E2EClient client = cliente();
        int prima = server.sessioniAttive();
        E2EClient.Risposta login = client.login(email, PASSWORD);
        assertEquals(200, login.status(), login.testo());
        assertEquals(prima + 1, server.sessioniAttive(), "sessione creata dal login reale");
        int id = login.json().get("idUtente").intValue();
        assertEquals(manifest.idUtente(catalogo, email), id);

        assertEquals(200, client.get("/api/prenotazioni/paziente?idPaziente=" + id).status());
        assertEquals(403, client.get("/api/prenotazioni/paziente?idPaziente=" + (id + 1)).status(), "attore altrui");
        E2EClient.Credenziali vecchie = client.credenziali().orElseThrow();
        assertEquals(403, client.invia("POST", "/api/auth/logout", null, Map.of("X-CSRF-Token", "")).status(), "senza CSRF");
        assertEquals(200, client.invia("POST", "/api/auth/logout", null, Map.of()).status());
        assertTrue(client.credenziali().isEmpty(), "cookie scaduto recepito dal client");
        assertEquals(prima, server.sessioniAttive());

        E2EClient.Risposta vecchio = replay(vecchie).get("/api/auth/me");
        assertEquals(401, vecchio.status(), "vecchio cookie rifiutato dopo il logout");
    }

    @Test void passwordChangeRotatesCookieAndCsrfAndRevokesThePreviousSession() throws Exception {
        String email = registraPaziente();
        E2EClient client = cliente();
        assertEquals(200, client.login(email, PASSWORD).status());
        E2EClient.Credenziali prima = client.credenziali().orElseThrow();
        String nuova = "Nuova-password-sintetica";
        E2EClient.Risposta cambio = client.putJson("/api/auth/password", Map.of("passwordAttuale", PASSWORD, "nuovaPassword", nuova));
        assertEquals(200, cambio.status(), cambio.testo());
        assertFalse(cambio.json().get("nuovoAccessoRichiesto").booleanValue());
        E2EClient.Credenziali dopo = client.credenziali().orElseThrow();
        assertNotEquals(prima.cookie(), dopo.cookie(), "cookie ruotato");
        assertNotEquals(prima.csrf(), dopo.csrf(), "token CSRF ruotato");
        assertEquals(200, client.get("/api/auth/me").status(), "nuova sessione utilizzabile");
        assertEquals(401, replay(prima).get("/api/auth/me").status(), "sessione precedente revocata");
        assertEquals(401, cliente().login(email, PASSWORD).status(), "vecchia password");
        assertEquals(200, cliente().login(email, nuova).status(), "nuova password");
    }

    @Test void advancingTheDomainClockDoesNotExpireSessions() throws Exception {
        String email = registraPaziente();
        E2EClient client = cliente();
        assertEquals(200, client.login(email, PASSWORD).status());
        try {
            server.domainClock().advance(Duration.ofDays(400));
            assertEquals(200, client.get("/api/auth/me").status(), "sessione sul clock di autenticazione, non di dominio");
            assertEquals(E2EServer.AUTH_T0, server.authClock().instant(), "clock di autenticazione invariato");
        } finally {
            server.domainClock().set(E2EServer.T0);
        }
    }

    @Test void catalogIsExclusiveOwnedAndTheRuntimeAccountHasDmlOnly() throws Exception {
        assertTrue(E2ELauncher.CATALOGO.matcher(catalogo.nome()).matches(), catalogo.nome());
        assertTrue(launcher.catalogiAttivi().contains(catalogo.nome()));
        try (var c = catalogo.factory().getConnection(); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT DATABASE(), @@server_uuid, CURRENT_USER()")) {
                assertTrue(r.next());
                assertEquals(catalogo.nome(), r.getString(1));
                assertEquals(launcher.configurazione().serverUuid(), r.getString(2));
            }
            assertThrows(SQLException.class, () -> s.execute("CREATE TABLE e2e_non_consentita (id INT)"), "nessun DDL al runtime");
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1 FROM `" + launcher.configurazione().template() + "`.utente"),
                    "il runtime non legge il modello");
        }
    }

    @Test void incompatibleOrForeignTargetsAreRefusedWithoutSideEffects() throws Exception {
        E2EConfigurazione.Completa ok = launcher.configurazione();
        var altroUuid = new E2EConfigurazione.Completa(ok.obbligatoria(), ok.host(), ok.porta(), ok.template(),
                "00000000-0000-0000-0000-000000000000", ok.launcherUser(), ok.launcherPassword(), ok.runtimeUser(), ok.runtimePassword());
        var attivi = launcher.catalogiAttivi();
        var sulServer = launcher.catalogiE2ESulServer();
        assertThrows(SQLException.class, () -> new E2ELauncher(altroUuid), "UUID del server diverso");
        assertThrows(SQLException.class, () -> new E2ECatalogo(catalogo.nome(), altroUuid).factory().getConnection(),
                "anche la factory runtime rifiuta un server con UUID diverso");
        assertEquals(attivi, launcher.catalogiAttivi(), "registro invariato");
        assertEquals(sulServer, launcher.catalogiE2ESulServer(), "nessun catalogo creato sul server");
        assertThrows(SQLException.class, () -> launcher.elimina(ok.template()), "il modello non è un catalogo di esecuzione");
        assertThrows(SQLException.class, () -> launcher.elimina("medbook"), "catalogo applicativo");
        assertThrows(SQLException.class, () -> launcher.elimina("medbook_test_e2e_r20000101000000_00000000deadbeef"), "non creato da questo launcher");
        assertTrue(launcher.esiste(ok.template()), "modello intatto");
    }

    @Test void cleanupReallyReleasesCatalogStorageAndListener() throws Exception {
        E2ECatalogo secondo = launcher.creaCatalogo();
        try {
            assertTrue(launcher.esiste(secondo.nome()));
            Path base;
            int porta;
            try (E2EStorage altro = new E2EStorage()) {
                base = altro.base();
                try (E2EServer temporaneo = new E2EServer(secondo.factory(), altro); E2EClient c = new E2EClient(temporaneo)) {
                    porta = temporaneo.base().getPort();
                    assertEquals(200, c.get("/api/prestazioni").status());
                }
                try (Socket socket = new Socket()) {
                    assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener chiuso");
                }
            }
            assertFalse(Files.exists(base, LinkOption.NOFOLLOW_LINKS), "directory temporanea rimossa");
            launcher.elimina(secondo.nome());
            assertFalse(launcher.esiste(secondo.nome()), "catalogo eliminato");
            assertFalse(launcher.catalogiAttivi().contains(secondo.nome()));
        } finally {
            /* Anche dopo un'asserzione fallita: elimina soltanto risorse ancora dimostrabilmente proprie. */
            if (launcher.catalogiAttivi().contains(secondo.nome())) { launcher.elimina(secondo.nome()); }
        }
    }

    @Test void headerOverridesAreCaseInsensitive() throws Exception {
        String email = registraPaziente();
        E2EClient client = cliente();
        assertEquals(200, client.login(email, PASSWORD).status());
        /* Origin minuscolo sostituisce il valore predefinito invece di duplicarlo. */
        E2EClient.Risposta r = client.invia("POST", "/api/auth/logout", null, Map.of("origin", "http://invalid.example"));
        assertEquals(403, r.status(), r.testo());
        assertEquals(403, client.invia("POST", "/api/auth/logout", null, Map.of("x-csrf-token", "")).status(), "rimozione minuscola");
        assertEquals(200, client.get("/api/auth/me").status(), "sessione ancora valida dopo i rifiuti");
    }

    @Test void manifestReconcilesOnlyItsOwnIdentifiers() throws Exception {
        String email = manifest.nuovaEmail("riconcilia");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", "Fixture"); body.put("cognome", "Sintetica"); body.put("email", email);
        body.put("password", PASSWORD); body.put("codiceFiscale", manifest.nuovoCodiceFiscale());
        body.put("dataNascita", "1990-01-01"); body.put("telefono", null);
        cliente().postJson("/api/auth/register", body); // response deliberately ignored, as if lost
        assertNotNull(manifest.riconcilia(catalogo).get(email), "risorsa ritrovata tramite il manifest");
        assertThrows(IllegalArgumentException.class, () -> manifest.idUtente(catalogo, "estraneo@example.invalid"));
    }

    @Test void storageIsPrivateTemporaryAndOutsideTheRepository() throws Exception {
        Path radice = storage.radice();
        assertFalse(radice.startsWith(Path.of("").toAbsolutePath().toRealPath()), radice + " dentro il repository");
        assertEquals(radice, radice.toRealPath(), "percorso canonico, senza link");
        for (String dir : new String[]{"", "staging", "documents", "scratch"}) {
            Path p = dir.isEmpty() ? radice : radice.resolve(dir);
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS), p.toString());
        }
        try (var listing = Files.list(radice.resolve("documents"))) { assertEquals(0, listing.count()); }
    }

    @Test void levelAValidatorAcceptsOnlyRegisteredFixtureDocuments() throws Exception {
        FixturePdfValidator validator = server.pdfValidator();
        byte[] a = FixturePdfValidator.documento("smoke-A");
        byte[] alterato = a.clone(); alterato[12] ^= 1;
        validator.accetta(a);
        Path dir = storage.storage().scratch();
        Path fileA = Files.write(dir.resolve("smoke-a.pdf"), a);
        Path fileB = Files.write(dir.resolve("smoke-b.pdf"), FixturePdfValidator.documento("smoke-B"));
        Path fileAlt = Files.write(dir.resolve("smoke-alt.pdf"), alterato);
        try {
            validator.validate(fileA);
            assertThrows(it.medbook.referto.PdfRifiutatoException.class, () -> validator.validate(fileB), "non registrato");
            assertThrows(it.medbook.referto.PdfRifiutatoException.class, () -> validator.validate(fileAlt), "alterato di un bit");
        } finally {
            Files.delete(fileA); Files.delete(fileB); Files.delete(fileAlt);
        }
    }

    @Test void clocksAreDeterministicAndSeparate() {
        assertNotSame(server.domainClock(), server.authClock());
        assertFalse(server.domainClock().condivideIstante(server.authClock()));
        assertEquals(E2EServer.T0, server.domainClock().instant());
        assertEquals(E2EServer.AUTH_T0, server.authClock().instant());
    }
}
