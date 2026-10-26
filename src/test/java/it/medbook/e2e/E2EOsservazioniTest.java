package it.medbook.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/* Verifiche senza server o database su formato, riepiloghi, diagnostica e pulizia delle osservazioni. */
class E2EOsservazioniTest {
    private static final Set<String> CONSENTITI = Set.of("Referto non trovato.");

    static E2EClient.Risposta risposta(int status, Map<String, String> header, byte[] corpo) {
        Map<String, List<String>> h = new java.util.HashMap<>();
        header.forEach((k, v) -> h.put(k, List.of(v)));
        return new E2EClient.Risposta(status, HttpHeaders.of(h, (a, b) -> true), corpo);
    }

    private static E2EOsservazioni.Osservazione osservazione(String caso, String corpo, List<String> differenze) {
        return new E2EOsservazioni.Osservazione("Classe", "test", caso, "P1", "categoria", "GET", "/api/x", "-", 200,
                corpo, differenze, risposta(200, Map.of(), new byte[0]));
    }

    private static E2EOsservazioni nuovo() {
        return new E2EOsservazioni(new E2EStato(E2ETeardownTest.catalogoSintetico(), Path.of("non-usato")),
                E2EOsservazioniTest.class, CONSENTITI);
    }

    @Test void everyLineHasElevenColumnsAndTheFileIsDeterministic(@TempDir Path dir) throws IOException {
        E2EOsservazioni o = nuovo();
        o.registra(osservazione("a", "vuoto", List.of()));
        o.registra(osservazione("b", "json chiavi=[error] byte=10", List.of("referto", "storage")));
        o.scriviTsv(dir.resolve("uno"));
        o.scriviTsv(dir.resolve("due"));
        byte[] uno = Files.readAllBytes(dir.resolve("uno").resolve("E2EOsservazioniTest.tsv"));
        assertArrayEquals(uno, Files.readAllBytes(dir.resolve("due").resolve("E2EOsservazioniTest.tsv")), "stesso contenuto");
        List<String> righe = new String(uno, StandardCharsets.UTF_8).lines().toList();
        assertEquals(String.join("\t", E2EOsservazioni.INTESTAZIONE), righe.getFirst());
        assertEquals(3, righe.size());
        for (String r : righe) { assertEquals(11, r.split("\t", -1).length, r); }
        assertTrue(righe.get(1).endsWith("\tinvariato"));
        assertTrue(righe.get(2).endsWith("\tMODIFICATO referto,storage"));
    }

    @Test void controlCharactersAreNeutralized() {
        String sporco = "a\tb\nc\rd\u0000e\u001bf\u007fg\u0085h i j";
        String pulito = E2EOsservazioni.campo(sporco);
        assertEquals("a b c d e f g h i j", pulito);
        var riga = osservazione("caso\tcon\ntab", "corpo\r\n", List.of()).riga();
        assertEquals(11, riga.split("\t", -1).length, "un tab nei dati non crea colonne");
        assertFalse(riga.contains("\n") || riga.contains("\r"));
    }

    @Test void pdfAndJsonSummariesNeverCopyContents() {
        byte[] pdf = FixturePdfValidator.documento("RIEPILOGO-PDF");
        String r = E2EOsservazioni.riepilogo(risposta(200, Map.of("Content-Type", "application/pdf",
                "Content-Disposition", "attachment"), pdf), CONSENTITI);
        assertEquals("pdf byte=" + pdf.length + " disposition=presente", r);
        assertFalse(r.contains("%PDF") || r.contains(E2EStato.sha256(pdf)), "né byte né hash del documento");

        byte[] json = "{\"idReferto\":7,\"nomeFile\":\"riservato.pdf\"}".getBytes(StandardCharsets.UTF_8);
        String j = E2EOsservazioni.riepilogo(risposta(200, Map.of("Content-Type", "application/json; charset=UTF-8"), json), CONSENTITI);
        assertEquals("json chiavi=[idReferto, nomeFile] byte=" + json.length, j);
        assertFalse(j.contains("riservato") || j.contains("7,"), "nessun valore copiato");

        byte[] consentito = "{\"error\":\"Referto non trovato.\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("json chiavi=[error] byte=" + consentito.length + " error=Referto non trovato.",
                E2EOsservazioni.riepilogo(risposta(404, Map.of("Content-Type", "application/json"), consentito), CONSENTITI));
        byte[] altro = "{\"error\":\"dettaglio interno /var/x SQLState 42000\"}".getBytes(StandardCharsets.UTF_8);
        String a = E2EOsservazioni.riepilogo(risposta(500, Map.of("Content-Type", "application/json"), altro), CONSENTITI);
        assertTrue(a.endsWith("error=<non registrato>"), a);
        assertFalse(a.contains("SQLState") || a.contains("/var"));

        assertEquals("vuoto", E2EOsservazioni.riepilogo(risposta(204, Map.of(), new byte[0]), CONSENTITI));
        assertEquals("tipo=text/plain byte=3", E2EOsservazioni.riepilogo(
                risposta(200, Map.of("Content-Type", "text/plain; charset=UTF-8"), "abc".getBytes(StandardCharsets.UTF_8)), CONSENTITI));
        assertEquals("json non valido byte=2", E2EOsservazioni.riepilogo(
                risposta(200, Map.of("Content-Type", "application/json"), "{x".getBytes(StandardCharsets.UTF_8)), CONSENTITI));
    }

    @Test void differencesNameTablesAndStorageOnlyNeverRowsOrPaths() {
        Map<String, Object> riga = Map.of("id_referto", 1L, "percorso_file", "documents/chiave-riservata");
        var prima = new E2EStato.Istantanea(Map.of("referto", List.of(riga), "prenotazione", List.of()),
                Map.of("documents/chiave-riservata", "aaa"));
        var dopo = new E2EStato.Istantanea(Map.of("referto", List.of(), "prenotazione", List.of()),
                Map.of("documents/chiave-riservata", "bbb"));
        List<String> d = E2EOsservazioni.differenze(prima, dopo);
        assertEquals(List.of("referto", "storage"), d);
        assertFalse(String.join(",", d).contains("chiave-riservata"));
        assertEquals(List.of(), E2EOsservazioni.differenze(prima, prima));
    }

    @Test void contaminationKeepsTheFirstCause() {
        E2EOsservazioni o = nuovo();
        assertFalse(o.contaminato());
        o.contamina("P8/B-R1 da P2");
        o.contamina("seconda causa");
        assertTrue(o.contaminato());
        assertEquals("P8/B-R1 da P2", o.causaContaminazione());
    }

    @Test void aFailingTsvWriteKeepsTheFirstThrowableWithE2ETeardown(@TempDir Path dir) throws IOException {
        E2EOsservazioni o = nuovo();
        o.registra(osservazione("a", "vuoto", List.of()));
        Path file = Files.writeString(dir.resolve("non-una-directory"), "x");
        List<String> eseguiti = new ArrayList<>();
        AssertionError chiusura = new AssertionError("chiusura (iniettata)");
        IOException e = assertThrows(IOException.class, () -> E2ETeardown.esegui(
                () -> { eseguiti.add("tsv"); o.scriviTsv(file); },
                () -> { eseguiti.add("fixture"); throw chiusura; },
                () -> eseguiti.add("ambiente")));
        assertEquals(List.of("tsv", "fixture", "ambiente"), eseguiti);
        assertEquals(1, e.getSuppressed().length);
        assertSame(chiusura, e.getSuppressed()[0]);
        assertEquals("x", Files.readString(file), "nessuna scrittura al posto del file");
    }
}
