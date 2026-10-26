package it.medbook.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/*
 * Vista in sola lettura del catalogo esclusivo e dello storage. Esegue soltanto SELECT tramite
 * connessioni riverificate per catalogo e UUID; non segue link nello storage.
 */
public final class E2EStato {
    /* Tabelle e chiavi dello snapshot clinico in ordine stabile. */
    static final Map<String, String> TABELLE = Map.of(
            "prenotazione", "id_prenotazione", "referto", "id_referto", "pagamento", "id_pagamento",
            "rimborso", "id_rimborso", "disponibilita", "id_disponibilita");

    private final E2ECatalogo catalogo;
    private final Path radiceStorage;

    public E2EStato(E2EAmbiente ambiente) { this(ambiente.catalogo(), ambiente.storage().radice()); }

    E2EStato(E2ECatalogo catalogo, Path radiceStorage) {
        this.catalogo = Objects.requireNonNull(catalogo);
        this.radiceStorage = Objects.requireNonNull(radiceStorage);
    }

    /** Righe SELECT come mappe ordinate per colonna; rifiuta qualunque istruzione diversa da una SELECT singola. */
    public List<Map<String, Object>> righe(String select, Object... parametri) throws SQLException {
        if (!select.stripLeading().regionMatches(true, 0, "SELECT", 0, 6) || select.contains(";")) {
            throw new IllegalArgumentException("Solo SELECT singole.");
        }
        try (Connection c = catalogo.factory().getConnection(); PreparedStatement s = c.prepareStatement(select)) {
            for (int i = 0; i < parametri.length; i++) { s.setObject(i + 1, parametri[i]); }
            try (ResultSet r = s.executeQuery()) {
                ResultSetMetaData m = r.getMetaData();
                List<Map<String, Object>> righe = new ArrayList<>();
                while (r.next()) {
                    Map<String, Object> riga = new LinkedHashMap<>();
                    for (int i = 1; i <= m.getColumnCount(); i++) { riga.put(m.getColumnLabel(i), r.getObject(i)); }
                    righe.add(riga);
                }
                return righe;
            }
        }
    }

    public Map<String, Object> riga(String select, Object... parametri) throws SQLException {
        List<Map<String, Object>> righe = righe(select, parametri);
        if (righe.size() != 1) { throw new IllegalStateException("Attesa una riga, trovate " + righe.size()); }
        return righe.getFirst();
    }

    public long conta(String select, Object... parametri) throws SQLException {
        return ((Number) riga(select, parametri).values().iterator().next()).longValue();
    }

    /* Tabelle cliniche e file confrontabili prima e dopo con equals. */
    public record Istantanea(Map<String, List<Map<String, Object>>> tabelle, Map<String, String> file) { }

    public Istantanea istantanea() throws SQLException, IOException {
        Map<String, List<Map<String, Object>>> tabelle = new TreeMap<>();
        for (var t : new TreeMap<>(TABELLE).entrySet()) {
            tabelle.put(t.getKey(), righe("SELECT * FROM " + t.getKey() + " ORDER BY " + t.getValue()));
        }
        return new Istantanea(tabelle, file());
    }

    /* Percorso relativo e SHA-256 di ogni file regolare nello storage. */
    public Map<String, String> file() throws IOException {
        Map<String, String> file = new TreeMap<>();
        try (Stream<Path> percorsi = Files.walk(radiceStorage)) {
            for (Path p : (Iterable<Path>) percorsi::iterator) {
                if (Files.isSymbolicLink(p)) { throw new IOException("Collegamento simbolico inatteso nello storage."); }
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    file.put(radiceStorage.relativize(p).toString(), sha256(Files.readAllBytes(p)));
                }
            }
        }
        return file;
    }

    public static String sha256(byte[] dati) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dati)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
