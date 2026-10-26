package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Privilegi effettivi minimi di launcher e runtime: verifica GRANT e operazioni negate con errori
 * MySQL di accesso, non errori di sintassi.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivilegiE2EIntegrationTest {
    private static final String RUN = "`medbook\\_test\\_e2e\\_r%`.*";
    private static final Set<Integer> ACCESSO_NEGATO = Set.of(1044, 1142, 1227);
    private static final Pattern GRANT = Pattern.compile("GRANT (.+) ON (\\S+) TO .+");
    private E2EConfigurazione.Completa cfg;
    private E2ELauncher launcher;
    private String catalogo;

    @BeforeAll void avvia() throws Exception {
        cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        launcher = new E2ELauncher(cfg);
        catalogo = launcher.creaCatalogo().nome();
    }

    @AfterAll void ferma() throws Exception {
        if (launcher != null && catalogo != null) { launcher.elimina(catalogo); }
    }

    @Test void launcherGrantsAreExactlyTheMinimalSet() throws Exception {
        try (Connection c = connetti(cfg.template(), cfg.launcherUser(), cfg.launcherPassword())) {
            assertEquals(Map.of(
                    "*.*", Set.of("USAGE"),
                    RUN, Set.of("SELECT", "INSERT", "CREATE", "DROP", "ALTER", "INDEX", "REFERENCES"),
                    "`" + cfg.template() + "`.*", Set.of("SELECT")), grant(c));
            assertNoRoles(c);
        }
    }

    @Test void runtimeGrantsAreExactlyDmlOnRunCatalogs() throws Exception {
        try (Connection c = connetti(catalogo, cfg.runtimeUser(), cfg.runtimePassword())) {
            assertEquals(Map.of("*.*", Set.of("USAGE"), RUN, Set.of("SELECT", "INSERT", "UPDATE", "DELETE")), grant(c));
            assertNoRoles(c);
        }
    }

    @Test void accountsSeeNoOtherCatalog() throws Exception {
        Set<String> comuni = Set.of("information_schema", "performance_schema");
        try (Connection c = connetti(cfg.template(), cfg.launcherUser(), cfg.launcherPassword())) {
            for (String db : catalogi(c)) {
                assertTrue(comuni.contains(db) || db.equals(cfg.template()) || db.startsWith("medbook_test_e2e_r"), "launcher vede " + db);
            }
        }
        try (Connection c = connetti(catalogo, cfg.runtimeUser(), cfg.runtimePassword())) {
            for (String db : catalogi(c)) {
                assertTrue(comuni.contains(db) || db.startsWith("medbook_test_e2e_r"), "runtime vede " + db);
            }
        }
    }

    @Test void launcherCannotTouchTheTemplateOrOtherCatalogsNorRewriteTheMarker() throws Exception {
        try (Connection c = connetti(cfg.template(), cfg.launcherUser(), cfg.launcherPassword()); Statement s = c.createStatement()) {
            negato(s, "CREATE DATABASE `medbook_e2e_non_consentito`");
            negato(s, "CREATE DATABASE `medbook`");
            negato(s, "DROP DATABASE `" + cfg.template() + "`");
            negato(s, "CREATE TABLE `" + cfg.template() + "`.e2e_non_consentita (id INT)");
            negato(s, "DROP TABLE `" + cfg.template() + "`.utente");
            negato(s, "ALTER TABLE `" + cfg.template() + "`.utente ADD COLUMN e2e INT");
            negato(s, "INSERT INTO `" + cfg.template() + "`.prestazione (nome) VALUES ('x')");
            negato(s, "DELETE FROM `" + cfg.template() + "`.utente");
            negato(s, "SELECT COUNT(*) FROM mysql.user");
            negato(s, "CREATE USER 'e2e_non_consentito'@'%'");
            /* Il marcatore di proprietà può essere letto e inserito, non riscritto. */
            negato(s, "UPDATE `" + catalogo + "`." + E2ELauncher.MARKER + " SET token = REPEAT('0', 32)");
            negato(s, "DELETE FROM `" + catalogo + "`." + E2ELauncher.MARKER);
            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM `" + catalogo + "`." + E2ELauncher.MARKER)) {
                r.next(); assertEquals(1, r.getLong(1));
            }
        }
    }

    @Test void runtimeHasNoDdlNoDropAndNoAccessOutsideRunCatalogs() throws Exception {
        try (Connection c = connetti(catalogo, cfg.runtimeUser(), cfg.runtimePassword()); Statement s = c.createStatement()) {
            negato(s, "CREATE DATABASE `medbook_test_e2e_r20000101000000_0000000000000002`");
            negato(s, "DROP DATABASE `" + catalogo + "`");
            negato(s, "CREATE TABLE e2e_non_consentita (id INT)");
            negato(s, "DROP TABLE utente");
            negato(s, "ALTER TABLE utente ADD COLUMN e2e INT");
            negato(s, "TRUNCATE TABLE utente");
            negato(s, "CREATE INDEX e2e_idx ON utente (nome)");
            negato(s, "SELECT 1 FROM `" + cfg.template() + "`.utente");
            negato(s, "SELECT COUNT(*) FROM mysql.user");
            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM utente")) { assertTrue(r.next()); }
        }
    }

    private Connection connetti(String db, String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(cfg.urlCatalogo(db), user, password);
        try { E2ELauncher.verificaServer(c, db, cfg.serverUuid()); return c; }
        catch (SQLException e) { c.close(); throw e; }
    }

    /* Privilegi dell'account corrente raggruppati per oggetto. */
    private static Map<String, Set<String>> grant(Connection c) throws SQLException {
        Map<String, Set<String>> grant = new TreeMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW GRANTS")) {
            while (r.next()) {
                Matcher m = GRANT.matcher(r.getString(1));
                assertTrue(m.matches(), "grant non riconosciuto: " + r.getString(1));
                assertFalse(m.group(1).contains("PROXY") || r.getString(1).contains("WITH GRANT OPTION"), r.getString(1));
                grant.computeIfAbsent(m.group(2), k -> new TreeSet<>()).addAll(
                        Arrays.stream(m.group(1).split(",")).map(String::trim).collect(Collectors.toSet()));
            }
        }
        return grant;
    }

    private static void assertNoRoles(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CURRENT_ROLE()")) {
            r.next(); assertEquals("NONE", r.getString(1));
        }
    }

    private static Set<String> catalogi(Connection c) throws SQLException {
        Set<String> nomi = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW DATABASES")) {
            while (r.next()) { nomi.add(r.getString(1)); }
        }
        return nomi;
    }

    private static void negato(Statement s, String sql) {
        SQLException e = assertThrows(SQLException.class, () -> s.execute(sql), sql);
        assertTrue(ACCESSO_NEGATO.contains(e.getErrorCode()), sql + " -> " + e.getErrorCode() + " " + e.getMessage());
    }
}
