package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Guasti JDBC e di preparazione su Docker isolato. Ogni test usa un launcher proprio e confronta i
 * cataloghi finali con quelli iniziali. I cataloghi ambigui o estranei sono recuperati solo dal
 * test che sa di averli creati, mai adottati dal launcher.
 */
@Tag("e2e-guasti")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuastiE2EIntegrationTest {
    private E2EConfigurazione.Completa cfg;
    private E2ELauncher osservatore;
    private Set<String> iniziali;
    private final List<E2ELauncher> launcher = new ArrayList<>();

    @BeforeAll void avvia() throws Exception {
        cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        osservatore = new E2ELauncher(cfg);
        iniziali = osservatore.catalogiE2ESulServer();
    }

    /* Elimina anche dopo fallimenti solo cataloghi ancora dimostrabilmente propri. */
    @AfterEach void rilascia() throws Exception {
        Exception errore = null;
        for (E2ELauncher l : launcher) {
            for (String nome : l.catalogiAttivi()) {
                try { l.elimina(nome); } catch (Exception e) { if (errore == null) { errore = e; } else { errore.addSuppressed(e); } }
            }
        }
        launcher.clear();
        if (errore != null) { throw errore; }
    }

    @AfterAll void nessunResiduo() throws Exception {
        if (osservatore != null) { assertEquals(iniziali, osservatore.catalogiE2ESulServer(), "cataloghi residui sul server"); }
    }

    // ---------------------------------------------------------------- CREATE DATABASE

    @Test void ddlAcknowledgedThenCloseFailsTheCatalogIsRegisteredAndDropped() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.CREA, Modo.CLOSE_FALLISCE);
        E2ELauncher l = nuovo(g);
        SQLException e = assertThrows(SQLException.class, l::creaCatalogo);
        assertTrue(e.getMessage().contains("close()"), e.getMessage());
        assertEquals(1, g.eseguiti.get(), "il DDL è stato eseguito davvero");
        assertTrue(g.statoPrimaDelDrop().vuoto(), "catalogo creato, registrato e trovato vuoto al momento del DROP");
        assertFalse(osservatore.esiste(g.nome()), "eliminato dal cleanup del launcher");
        assertTrue(l.catalogiAttivi().isEmpty());
        assertTrue(l.catalogiIncerti().isEmpty());
    }

    @Test void ambiguousCreateWithTheCatalogPresentNeverTakesOwnership() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.CREA, Modo.ESEGUI_POI_FALLISCI);
        E2ELauncher l = nuovo(g);
        SQLException e = assertThrows(SQLException.class, l::creaCatalogo);
        String nome = g.nome();
        assertTrue(osservatore.esiste(nome), "il server ha creato il catalogo, ma la risposta è andata persa");
        assertFalse(l.catalogiAttivi().contains(nome), "proprietà non acquisita");
        String diagnostica = l.catalogiIncerti().get(nome);
        assertNotNull(diagnostica);
        assertEquals(diagnostica, e.getMessage());
        for (String atteso : List.of(nome, cfg.serverUuid(), cfg.host() + ":" + cfg.porta(), "ambiguo", "tabelle=0", "DROP DATABASE")) {
            assertTrue(diagnostica.contains(atteso), "diagnostica senza " + atteso + ": " + diagnostica);
        }
        assertFalse(diagnostica.contains(cfg.launcherPassword()) || diagnostica.contains(cfg.runtimePassword()), "nessuna credenziale");
        assertNull(g.statoPrimaDelDrop(), "nessun tentativo di DROP");
        SQLException rifiuto = assertThrows(SQLException.class, () -> l.elimina(nome));
        assertTrue(rifiuto.getMessage().contains("non creato da questo launcher"), rifiuto.getMessage());
        assertTrue(osservatore.esiste(nome), "nessuna cancellazione con proprietà incerta");
        recuperoManuale(nome);
    }

    @Test void createFailingBeforeExecutionLeavesNothingAndNoUncertainty() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.CREA, Modo.FALLISCI_SENZA_ESEGUIRE);
        E2ELauncher l = nuovo(g);
        SQLException e = assertThrows(SQLException.class, l::creaCatalogo);
        assertTrue(e.getMessage().contains("catalogo assente"), e.getMessage());
        assertFalse(osservatore.esiste(g.nome()));
        assertTrue(l.catalogiAttivi().isEmpty());
        assertTrue(l.catalogiIncerti().isEmpty());
    }

    // ---------------------------------------------------------------- ownership marker

    @Test void failureBeforeTheMarkerDropsTheEmptyCatalog() throws Exception {
        Iniezione g = new Iniezione().fallisciIn(E2EGuasti.Fase.CATALOGO_CREATO);
        E2ELauncher l = nuovo(g);
        assertThrows(E2EGuasti.Iniettato.class, l::creaCatalogo);
        assertTrue(g.statoPrimaDelDrop().vuoto());
        assertFalse(osservatore.esiste(g.nome()));
        assertTrue(l.catalogiAttivi().isEmpty() && l.catalogiIncerti().isEmpty());
    }

    @Test void markerStatementFailingBeforeExecutionDropsTheEmptyCatalog() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.MARKER, Modo.FALLISCI_SENZA_ESEGUIRE);
        E2ELauncher l = nuovo(g);
        assertThrows(SQLException.class, l::creaCatalogo);
        assertEquals(0, g.eseguiti.get());
        assertTrue(g.statoPrimaDelDrop().vuoto(), "nessuna tabella marker senza token");
        assertFalse(osservatore.esiste(g.nome()));
        assertTrue(l.catalogiAttivi().isEmpty() && l.catalogiIncerti().isEmpty());
    }

    @Test void markerWrittenButAnswerLostIsRecoveredThroughTheToken() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.MARKER, Modo.ESEGUI_POI_FALLISCI);
        E2ELauncher l = nuovo(g);
        assertThrows(SQLException.class, l::creaCatalogo);
        assertEquals(1, g.eseguiti.get());
        E2ELauncher.Ispezione stato = g.statoPrimaDelDrop();
        assertTrue(stato.proprio() && stato.tabelle() == 1, "marker e token scritti insieme: " + stato);
        assertFalse(osservatore.esiste(g.nome()));
        assertTrue(l.catalogiAttivi().isEmpty() && l.catalogiIncerti().isEmpty());
    }

    @Test void failureAfterTheTokenAndAfterTheSchemaDropsThroughTheToken() throws Exception {
        for (E2EGuasti.Fase fase : List.of(E2EGuasti.Fase.MARKER_SCRITTO, E2EGuasti.Fase.SCHEMA_COPIATO)) {
            Iniezione g = new Iniezione().fallisciIn(fase);
            E2ELauncher l = nuovo(g);
            assertThrows(E2EGuasti.Iniettato.class, l::creaCatalogo, fase.name());
            E2ELauncher.Ispezione stato = g.statoPrimaDelDrop();
            assertTrue(stato.proprio(), fase + ": " + stato);
            assertTrue(fase == E2EGuasti.Fase.MARKER_SCRITTO ? stato.tabelle() == 1 : stato.tabelle() > 1, fase + ": " + stato);
            assertFalse(osservatore.esiste(g.nome()), fase.name());
            assertTrue(l.catalogiAttivi().isEmpty() && l.catalogiIncerti().isEmpty(), fase.name());
        }
    }

    @Test void markerCreationIsAtomicOnTheServer() throws Exception {
        E2ELauncher l = nuovo(E2EGuasti.NESSUNO);
        String nome = l.creaCatalogo().nome();
        /* Stessa forma del marcatore: il duplicato fa fallire la SELECT dopo la definizione della tabella. */
        try (Connection c = comeLauncher(nome); PreparedStatement p = c.prepareStatement(
                "CREATE TABLE e2e_atomicita (token CHAR(32) NOT NULL PRIMARY KEY) ENGINE=InnoDB SELECT ? AS token UNION ALL SELECT ?")) {
            p.setString(1, "a".repeat(32)); p.setString(2, "a".repeat(32));
            SQLException e = assertThrows(SQLException.class, p::executeUpdate);
            assertEquals(1062, e.getErrorCode(), e.getMessage());
        }
        try (Connection c = comeLauncher(nome); PreparedStatement p = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'e2e_atomicita'")) {
            p.setString(1, nome);
            try (ResultSet r = p.executeQuery()) { r.next(); assertEquals(0, r.getLong(1), "nessuna tabella senza righe"); }
        }
        l.elimina(nome);
        assertFalse(osservatore.esiste(nome));
    }

    @Test void missingAlteredOrExtraTokensAndAMissingMarkerRefuseTheDrop() throws Exception {
        E2ELauncher l = nuovo(E2EGuasti.NESSUNO);
        String nome = l.creaCatalogo().nome();
        String originale;
        try (Connection c = comeLauncher(nome); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT token FROM " + E2ELauncher.MARKER)) {
            assertTrue(r.next()); originale = r.getString(1); assertFalse(r.next());
        }
        String drop = "DROP TABLE " + E2ELauncher.MARKER;
        Map<String, List<String>> manomissioni = Map.of(
                "righe marker=0", List.of(drop, ricrea(null)),
                "righe marker=1, token=assente o diverso", List.of(drop, ricrea("b".repeat(32))),
                "righe marker=2, token=corrispondente", List.of(drop, ricrea(originale),
                        "INSERT INTO " + E2ELauncher.MARKER + " VALUES ('" + "c".repeat(32) + "', UTC_TIMESTAMP())"),
                "marker=assente", List.of(drop));
        for (var m : manomissioni.entrySet()) {
            esegui(nome, m.getValue());
            SQLException e = assertThrows(SQLException.class, () -> l.elimina(nome), m.getKey());
            assertTrue(e.getMessage().contains("DROP rifiutato") && e.getMessage().contains(m.getKey()), e.getMessage());
            assertFalse(e.getMessage().contains(originale), "il token non compare nella diagnostica");
            assertTrue(osservatore.esiste(nome), "nessuna cancellazione: " + m.getKey());
            assertTrue(l.catalogiAttivi().contains(nome) && l.catalogiIncerti().containsKey(nome));
            esegui(nome, List.of("DROP TABLE IF EXISTS " + E2ELauncher.MARKER, ricrea(originale)));
        }
        l.elimina(nome); /* Marcatore ripristinato: il protocollo invariato lo accetta nuovamente. */
        assertFalse(osservatore.esiste(nome));
        assertTrue(l.catalogiIncerti().isEmpty());
    }

    @Test void foreignCatalogsAreRefusedEvenWithAValidName() throws Exception {
        E2ELauncher a = nuovo(E2EGuasti.NESSUNO), b = nuovo(E2EGuasti.NESSUNO);
        String diA = a.creaCatalogo().nome();
        SQLException e = assertThrows(SQLException.class, () -> b.elimina(diA), "catalogo di un altro launcher");
        assertTrue(e.getMessage().contains("non creato da questo launcher"));
        assertTrue(osservatore.esiste(diA));

        String estraneo = "medbook_test_e2e_r20000101000000_" + "0".repeat(15) + "1";
        try (Connection c = comeLauncher(cfg.template()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + estraneo + "`");
        }
        for (E2ELauncher l : List.of(a, b)) { assertThrows(SQLException.class, () -> l.elimina(estraneo)); }
        assertTrue(osservatore.esiste(estraneo), "catalogo estraneo intatto");
        recuperoManuale(estraneo);
        a.elimina(diA);
    }



    @Test void setupFailingAfterTheListenerReleasesListenerStorageAndCatalog() throws Exception {
        Iniezione g = new Iniezione().fallisciIn(E2EGuasti.Fase.SERVER_IN_ASCOLTO);
        Set<String> prima = osservatore.catalogiE2ESulServer();
        assertThrows(E2EGuasti.Iniettato.class, () -> E2EAmbiente.avvia(cfg, g));
        int porta = Integer.parseInt(g.viste.get(E2EGuasti.Fase.SERVER_IN_ASCOLTO));
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", porta), 2000), "listener rilasciato");
        }
        assertFalse(Files.exists(Path.of(g.viste.get(E2EGuasti.Fase.STORAGE_CREATO)), LinkOption.NOFOLLOW_LINKS), "storage rimosso");
        assertFalse(osservatore.esiste(g.nome()), "catalogo eliminato");
        assertEquals(prima, osservatore.catalogiE2ESulServer());
    }

    @Test void setupFailingInTheStorageReleasesDirectoryAndCatalog() throws Exception {
        Iniezione g = new Iniezione().fallisciIn(E2EGuasti.Fase.STORAGE_CREATO);
        Set<String> prima = osservatore.catalogiE2ESulServer();
        assertThrows(E2EGuasti.Iniettato.class, () -> E2EAmbiente.avvia(cfg, g));
        assertFalse(Files.exists(Path.of(g.viste.get(E2EGuasti.Fase.STORAGE_CREATO)), LinkOption.NOFOLLOW_LINKS));
        assertNull(g.viste.get(E2EGuasti.Fase.SERVER_IN_ASCOLTO), "server mai avviato");
        assertFalse(osservatore.esiste(g.nome()));
        assertEquals(prima, osservatore.catalogiE2ESulServer());
    }

    @Test void setupFailingInTheCatalogReportsTheUncertainOneAndCreatesNoLocalResource() throws Exception {
        Iniezione g = new Iniezione().jdbc(E2EGuasti.Scopo.CREA, Modo.ESEGUI_POI_FALLISCI);
        assertThrows(SQLException.class, () -> E2EAmbiente.avvia(cfg, g));
        assertNull(g.viste.get(E2EGuasti.Fase.STORAGE_CREATO), "nessuno storage creato");
        assertTrue(osservatore.esiste(g.nome()), "lasciato in sede, non eliminato");
        recuperoManuale(g.nome());
    }

    // ---------------------------------------------------------------- helpers

    private E2ELauncher nuovo(E2EGuasti guasti) throws SQLException {
        E2ELauncher l = new E2ELauncher(cfg, guasti);
        launcher.add(l);
        if (guasti instanceof Iniezione i) { i.launcher = l; }
        return l;
    }

    private Connection comeLauncher(String catalogo) throws SQLException {
        Connection c = DriverManager.getConnection(cfg.urlCatalogo(catalogo), cfg.launcherUser(), cfg.launcherPassword());
        try { E2ELauncher.verificaServer(c, catalogo, cfg.serverUuid()); return c; }
        catch (SQLException e) { c.close(); throw e; }
    }

    private void esegui(String catalogo, List<String> sql) throws SQLException {
        try (Connection c = comeLauncher(catalogo); Statement s = c.createStatement()) {
            for (String q : sql) { s.execute(q); }
        }
    }

    /* Ricrea il marcatore vuoto o con un token usando i privilegi launcher, senza UPDATE/DELETE. */
    private static String ricrea(String token) {
        String base = "CREATE TABLE " + E2ELauncher.MARKER + " (token CHAR(32) NOT NULL PRIMARY KEY, creato_il DATETIME NOT NULL) ENGINE=InnoDB";
        return token == null ? base : base + " SELECT '" + token + "' AS token, UTC_TIMESTAMP() AS creato_il";
    }

    /*
     * Recupero manuale eseguito dal solo test creatore: UUID verificato, nome valido e catalogo
     * interamente vuoto prima di DROP.
     */
    private void recuperoManuale(String nome) throws SQLException {
        assertTrue(E2ELauncher.CATALOGO.matcher(nome).matches());
        try (Connection c = comeLauncher(cfg.template())) {
            try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
                p.setString(1, nome);
                try (ResultSet r = p.executeQuery()) { r.next(); assertEquals(0, r.getLong(1), "recupero manuale solo su catalogo vuoto"); }
            }
            try (Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + nome + "`"); }
        }
        assertFalse(osservatore.esiste(nome));
    }

    enum Modo { CLOSE_FALLISCE, ESEGUI_POI_FALLISCI, FALLISCI_SENZA_ESEGUIRE }

    /* Guasto configurabile su connessioni con uno scopo preciso o su una fase di preparazione. */
    static final class Iniezione implements E2EGuasti {
        final Map<Fase, String> viste = new EnumMap<>(Fase.class);
        final AtomicInteger eseguiti = new AtomicInteger();
        private Scopo scopo;
        private Modo modo;
        private Fase fallisci;
        private E2ELauncher launcher;
        private E2ELauncher.Ispezione primaDelDrop;

        Iniezione jdbc(Scopo s, Modo m) { scopo = s; modo = m; return this; }
        Iniezione fallisciIn(Fase f) { fallisci = f; return this; }
        String nome() { return viste.get(Fase.PRIMA_CREATE); }
        E2ELauncher.Ispezione statoPrimaDelDrop() { return primaDelDrop; }

        @Override public void fase(Fase fase, String dettaglio) {
            viste.put(fase, dettaglio);
            if (fase == fallisci) { throw new Iniettato("guasto iniettato in " + fase); }
        }

        @Override public Connection connessione(Scopo s, Connection c) {
            if (s == Scopo.ELIMINA && launcher != null && primaDelDrop == null) {
                try { primaDelDrop = launcher.ispeziona(nome()); } catch (SQLException e) { throw new IllegalStateException(e); }
            }
            return s == scopo ? guasta(c, modo, eseguiti) : c;
        }
    }

    static Connection guasta(Connection reale, Modo modo, AtomicInteger eseguiti) {
        return proxy(Connection.class, (p, m, a) -> {
            if (m.getName().equals("close") && modo == Modo.CLOSE_FALLISCE) {
                reale.close();
                throw new SQLException("close() fallito dopo il DDL (guasto iniettato)");
            }
            Object r = invoca(reale, m, a);
            return r instanceof Statement s ? proxy(m.getReturnType(), statement(s, modo, eseguiti)) : r;
        });
    }

    private static InvocationHandler statement(Statement reale, Modo modo, AtomicInteger eseguiti) {
        return (p, m, a) -> {
            if (!m.getName().startsWith("execute")) { return invoca(reale, m, a); }
            if (modo == Modo.FALLISCI_SENZA_ESEGUIRE) {
                throw new SQLException("comunicazione interrotta prima dell'esecuzione (guasto iniettato)", "08S01");
            }
            Object r = invoca(reale, m, a);
            eseguiti.incrementAndGet();
            if (modo == Modo.ESEGUI_POI_FALLISCI) {
                throw new SQLException("risposta persa dopo l'esecuzione (guasto iniettato)", "08S01");
            }
            return r;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> tipo, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(GuastiE2EIntegrationTest.class.getClassLoader(), new Class<?>[]{tipo}, h);
    }

    private static Object invoca(Object target, Method m, Object[] a) throws Throwable {
        try { return m.invoke(target, a); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
