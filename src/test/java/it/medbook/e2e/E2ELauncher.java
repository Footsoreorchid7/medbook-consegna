package it.medbook.e2e;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/*
 * Solo il launcher crea ed elimina cataloghi. Verifica UUID, nome casuale ammesso e assenza
 * iniziale; registra la proprietà solo dopo conferma CREATE DATABASE. Una creazione ambigua resta
 * incerta e non eliminabile. Il marcatore viene creato atomicamente con tabella e token, poi
 * riletto; lo schema è copiato dal template con chiavi esterne attive. Alla pulizia richiede nome
 * valido, registrazione locale, UUID e catalogo vuoto oppure marcatore unico col token proprio.
 * Stati diversi restano conservati per recupero manuale, senza segreti nei diagnostici. I privilegi
 * limitano inoltre il launcher ai cataloghi di esecuzione e alla sola lettura del template.
 */
public final class E2ELauncher {
    static final Pattern CATALOGO = Pattern.compile("medbook_test_e2e_r[0-9]{14}_[0-9a-f]{16}");
    static final String MARKER = "e2e_proprietario";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final E2EConfigurazione.Completa cfg;
    private final E2EGuasti guasti;
    private final String token = esadecimale(16);
    private final Set<String> creati = new LinkedHashSet<>();
    private final Map<String, String> incerti = new LinkedHashMap<>();

    /* Verifica server e template prima di creare qualunque risorsa. */
    public E2ELauncher(E2EConfigurazione.Completa cfg) throws SQLException { this(cfg, E2EGuasti.NESSUNO); }

    E2ELauncher(E2EConfigurazione.Completa cfg, E2EGuasti guasti) throws SQLException {
        this.cfg = Objects.requireNonNull(cfg);
        this.guasti = Objects.requireNonNull(guasti);
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) {
            if (tabelle(c, cfg.template()).isEmpty()) { throw new SQLException("Catalogo modello E2E senza tabelle."); }
        }
    }

    public E2EConfigurazione.Completa configurazione() { return cfg; }

    /* Crea un catalogo esclusivo; dopo un guasto ripulisce solo quello dimostrabilmente creato qui. */
    public E2ECatalogo creaCatalogo() throws SQLException {
        String nome = "medbook_test_e2e_r" + LocalDateTime.now(ZoneOffset.UTC).format(TS) + "_" + esadecimale(8);
        if (!CATALOGO.matcher(nome).matches()) { throw new IllegalStateException("Nome catalogo E2E non valido."); }
        guasti.fase(E2EGuasti.Fase.PRIMA_CREATE, nome);
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) {
            if (esiste(c, nome)) {
                throw new SQLException("Creazione rifiutata: il catalogo " + nome + " esiste già; nessuna proprietà acquisita.");
            }
        }
        try {
            crea(nome);
            guasti.fase(E2EGuasti.Fase.CATALOGO_CREATO, nome);
            scriviMarker(nome);
            guasti.fase(E2EGuasti.Fase.MARKER_SCRITTO, nome);
            try (Connection c = launcher(nome, E2EGuasti.Scopo.MODELLO)) { copiaSchema(c); }
            guasti.fase(E2EGuasti.Fase.SCHEMA_COPIATO, nome);
            return new E2ECatalogo(nome, cfg);
        } catch (SQLException | RuntimeException e) {
            if (creati.contains(nome)) {
                try { elimina(nome); } catch (SQLException | RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    private void crea(String nome) throws SQLException {
        SQLException esitoAmbiguo = null;
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.CREA); Statement s = c.createStatement()) {
            try {
                s.execute("CREATE DATABASE `" + nome + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            } catch (SQLException e) {
                esitoAmbiguo = e;
                throw e;
            }
            /* CREATE DATABASE confermato senza IF NOT EXISTS: il catalogo era assente. */
            creati.add(nome);
        } catch (SQLException e) {
            if (esitoAmbiguo == null) { throw e; } /* Guasto prima del DDL oppure durante close dopo creazione già registrata. */
            Ispezione stato;
            try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) { stato = ispeziona(c, nome); }
            catch (SQLException verifica) { e.addSuppressed(verifica); stato = null; }
            if (stato != null && !stato.esiste()) {
                throw new SQLException("CREATE DATABASE non riuscito; catalogo assente, nessuna risorsa da recuperare.", e);
            }
            String diagnostica = diagnostica(nome, "esito di CREATE DATABASE ambiguo, proprietà non acquisita", stato);
            incerti.put(nome, diagnostica);
            throw new SQLException(diagnostica, e);
        }
    }

    private void scriviMarker(String nome) throws SQLException {
        try (Connection c = launcher(nome, E2EGuasti.Scopo.MARKER); PreparedStatement p = c.prepareStatement(
                "CREATE TABLE " + MARKER + " (token CHAR(32) NOT NULL PRIMARY KEY, creato_il DATETIME NOT NULL) ENGINE=InnoDB "
                        + "SELECT ? AS token, UTC_TIMESTAMP() AS creato_il")) {
            p.setString(1, token);
            p.executeUpdate();
        }
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) {
            if (!ispeziona(c, nome).proprio()) { throw new SQLException("Marker di proprietà non verificato dopo la scrittura."); }
        }
    }

    /* Elimina solo dopo tutte le prove di proprietà e verifica l'assenza successiva. */
    public void elimina(String nome) throws SQLException {
        if (nome == null || !CATALOGO.matcher(nome).matches()) { throw new SQLException("Eliminazione rifiutata: nome non E2E."); }
        if (!creati.contains(nome)) { throw new SQLException("Eliminazione rifiutata: catalogo non creato da questo launcher."); }
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.ELIMINA)) {
            Ispezione stato = ispeziona(c, nome);
            if (stato.esiste()) {
                if (!stato.vuoto() && !stato.proprio()) {
                    String diagnostica = diagnostica(nome, "proprietà non verificabile, DROP rifiutato", stato);
                    incerti.put(nome, diagnostica);
                    throw new SQLException(diagnostica);
                }
                try (Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + nome + "`"); }
                if (esiste(c, nome)) { throw new SQLException("Catalogo E2E non eliminato: " + nome); }
            }
        }
        creati.remove(nome);
        incerti.remove(nome);
    }

    /* Cataloghi creati da questo launcher e non ancora eliminati. */
    public Set<String> catalogiAttivi() { return Set.copyOf(creati); }

    /* Cataloghi incerti conservati con diagnostico di recupero. */
    public Map<String, String> catalogiIncerti() { return Map.copyOf(incerti); }

    public boolean esiste(String nome) throws SQLException {
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) { return esiste(c, nome); }
    }

    /* Cataloghi di esecuzione presenti sul server, anche altrui, per controllare effetti collaterali. */
    public Set<String> catalogiE2ESulServer() throws SQLException {
        Set<String> nomi = new TreeSet<>();
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
                     + "WHERE SCHEMA_NAME LIKE 'medbook\\_test\\_e2e\\_r%'")) {
            while (r.next()) { nomi.add(r.getString(1)); }
        }
        return nomi;
    }

    /* Stato osservato dal protocollo di eliminazione; righeToken conta i marcatori uguali al token proprio. */
    record Ispezione(boolean esiste, int tabelle, boolean marker, long righeMarker, long righeToken) {
        boolean vuoto() { return esiste && tabelle == 0; }
        boolean proprio() { return esiste && marker && righeMarker == 1 && righeToken == 1; }
    }

    Ispezione ispeziona(String nome) throws SQLException {
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) { return ispeziona(c, nome); }
    }

    private Ispezione ispeziona(Connection c, String nome) throws SQLException {
        if (!esiste(c, nome)) { return new Ispezione(false, 0, false, 0, 0); }
        List<String> tabelle = tabelle(c, nome);
        if (!tabelle.contains(MARKER)) { return new Ispezione(true, tabelle.size(), false, 0, 0); }
        try (PreparedStatement p = c.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(token = ?), 0) FROM `" + nome + "`." + MARKER)) {
            p.setString(1, token);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new Ispezione(true, tabelle.size(), true, r.getLong(1), r.getLong(2));
            }
        }
    }

    /* Diagnostica senza segreti: destinazione, stato osservato e recupero manuale sicuro. */
    private String diagnostica(String nome, String motivo, Ispezione stato) {
        String osservato = stato == null ? "stato non verificabile (connessione fallita)"
                : !stato.esiste() ? "catalogo assente"
                : "tabelle=" + stato.tabelle() + ", marker=" + (stato.marker() ? "presente" : "assente")
                  + (stato.marker() ? ", righe marker=" + stato.righeMarker()
                     + ", token=" + (stato.righeToken() == 1 ? "corrispondente" : "assente o diverso") : "");
        return "Catalogo E2E " + nome + " su " + cfg.host() + ":" + cfg.porta() + " (server_uuid " + cfg.serverUuid()
                + "): " + motivo + "; " + osservato + ". Recupero manuale: verificare con l'account launcher, su questo "
                + "server, che il catalogo non contenga dati da conservare, poi eseguire DROP DATABASE `" + nome
                + "`; in alternativa eliminare l'intero container dedicato con scripts/e2e/mysql-e2e.sh elimina.";
    }

    private static String esadecimale(int bytes) {
        byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return HexFormat.of().formatHex(b);
    }

    private void copiaSchema(Connection destinazione) throws SQLException {
        Map<String, Set<String>> dipendenze = new LinkedHashMap<>();
        try (Connection c = launcher(cfg.template(), E2EGuasti.Scopo.VERIFICA)) {
            for (String t : tabelle(c, cfg.template())) { dipendenze.put(t, new LinkedHashSet<>()); }
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                    WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME <> TABLE_NAME""")) {
                p.setString(1, cfg.template()); p.setString(2, cfg.template());
                try (ResultSet r = p.executeQuery()) { while (r.next()) { dipendenze.get(r.getString(1)).add(r.getString(2)); } }
            }
            List<String> ordine = new ArrayList<>();
            while (ordine.size() < dipendenze.size()) {
                boolean progresso = false;
                for (var e : dipendenze.entrySet()) {
                    if (!ordine.contains(e.getKey()) && ordine.containsAll(e.getValue())) { ordine.add(e.getKey()); progresso = true; }
                }
                if (!progresso) { throw new SQLException("Dipendenze cicliche tra le tabelle del modello."); }
            }
            for (String t : ordine) {
                String ddl;
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW CREATE TABLE `" + cfg.template() + "`.`" + t + "`")) {
                    r.next(); ddl = r.getString(2).replaceAll(" AUTO_INCREMENT=[0-9]+", "");
                }
                try (Statement s = destinazione.createStatement()) { s.execute(ddl); }
            }
            if (tabelle(destinazione, destinazione.getCatalog()).size() != ordine.size() + 1) {
                throw new SQLException("Copia dello schema incompleta.");
            }
        }
    }

    private Connection launcher(String catalogo, E2EGuasti.Scopo scopo) throws SQLException {
        Connection c = DriverManager.getConnection(cfg.urlCatalogo(catalogo), cfg.launcherUser(), cfg.launcherPassword());
        try {
            verificaServer(c, catalogo, cfg.serverUuid());
            return guasti.connessione(scopo, c);
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    static void verificaServer(Connection c, String catalogo, String uuid) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DATABASE(), @@server_uuid, @@version")) {
            if (!r.next() || !catalogo.equals(r.getString(1)) || !uuid.equals(r.getString(2))) {
                throw new SQLException("Destinazione E2E incompatibile (catalogo o server UUID).");
            }
            if (!r.getString(3).startsWith("8.4.")) { throw new SQLException("Richiesto MySQL 8.4 per i test E2E."); }
        }
    }

    private static boolean esiste(Connection c, String nome) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            p.setString(1, nome);
            try (ResultSet r = p.executeQuery()) { r.next(); return r.getLong(1) == 1; }
        }
    }

    private static List<String> tabelle(Connection c, String schema) throws SQLException {
        List<String> nomi = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME")) {
            p.setString(1, schema);
            try (ResultSet r = p.executeQuery()) { while (r.next()) { nomi.add(r.getString(1)); } }
        }
        return nomi;
    }
}
