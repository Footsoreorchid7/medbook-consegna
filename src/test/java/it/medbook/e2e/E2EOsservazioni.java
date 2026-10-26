package it.medbook.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/*
 * Raccoglie osservazioni HTTP con snapshot clinici prima/dopo; le asserzioni restano nei test.
 * Confronta cinque tabelle cliniche e SHA-256 dei file, più proiezioni non sensibili di utente e
 * medico. Esclude directory vuote, permessi, timestamp filesystem e sessioni in memoria. Il TSV non
 * contiene corpi, documenti, password, hash o dati personali: solo riepiloghi e nomi delle tabelle
 * cambiate. Errori di lettura o scrittura propagano senza entrare nel TSV. Una differenza inattesa
 * conserva la prima causa di contaminazione e blocca le verifiche dipendenti.
 */
final class E2EOsservazioni {
    static final List<String> INTESTAZIONE = List.of("classe", "test", "caso", "attore", "categoria", "metodo", "percorso",
            "richiesta", "status", "corpo", "stato");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* La risposta resta disponibile alle sole asserzioni, mai scritta nel TSV. */
    record Osservazione(String classe, String test, String caso, String attore, String categoria, String metodo,
                        String percorso, String richiesta, int status, String corpo, List<String> differenze,
                        E2EClient.Risposta risposta) {
        boolean invariato() { return differenze.isEmpty(); }
        List<String> colonne() {
            return List.of(classe, test, caso, attore, categoria, metodo, percorso, richiesta, Integer.toString(status), corpo,
                    invariato() ? "invariato" : "MODIFICATO " + String.join(",", differenze));
        }
        String riga() { return String.join("\t", colonne().stream().map(E2EOsservazioni::campo).toList()); }
    }

    private final E2EStato stato;
    private final String classe;
    private final Set<String> messaggiRegistrabili;
    private final List<Osservazione> registro = new ArrayList<>();
    private String test = "-";
    private String contaminazione;

    /* I soli messaggi fissi ammessi possono entrare nel riepilogo; ogni altro testo viene omesso. */
    E2EOsservazioni(E2EStato stato, Class<?> classe, Set<String> messaggiRegistrabili) {
        this.stato = Objects.requireNonNull(stato);
        this.classe = classe.getSimpleName();
        this.messaggiRegistrabili = Set.copyOf(messaggiRegistrabili);
    }

    void test(String nome) { this.test = Objects.requireNonNull(nome); }

    /* GET senza corpo con snapshot prima e dopo la richiesta. */
    Osservazione get(String caso, String attore, E2EClient client, String categoria, String percorso) throws Exception {
        E2EStato.Istantanea prima = istantanea();
        E2EClient.Risposta r = client.get(percorso);
        E2EStato.Istantanea dopo = istantanea();
        Osservazione o = new Osservazione(classe, test, caso, attore, categoria, "GET", percorso, "-", r.status(),
                riepilogo(r, messaggiRegistrabili), differenze(prima, dopo), r);
        registro.add(o);
        return o;
    }

    E2EStato.Istantanea istantanea() throws Exception {
        E2EStato.Istantanea base = stato.istantanea();
        Map<String, List<Map<String, Object>>> tabelle = new TreeMap<>(base.tabelle());
        tabelle.put("medico", stato.righe("SELECT id_utente, stato FROM medico ORDER BY id_utente"));
        tabelle.put("utente", stato.righe("SELECT id_utente, ruolo_gestionale, stato_accesso, generazione_autenticazione "
                + "FROM utente ORDER BY id_utente"));
        return new E2EStato.Istantanea(tabelle, base.file());
    }

    /* Solo nomi delle tabelle cambiate o storage: mai righe, valori o percorsi. */
    static List<String> differenze(E2EStato.Istantanea prima, E2EStato.Istantanea dopo) {
        Set<String> nomi = new TreeSet<>();
        Set<String> tabelle = new TreeSet<>(prima.tabelle().keySet());
        tabelle.addAll(dopo.tabelle().keySet());
        for (String t : tabelle) {
            if (!Objects.equals(prima.tabelle().get(t), dopo.tabelle().get(t))) { nomi.add(t); }
        }
        List<String> out = new ArrayList<>(nomi);
        if (!prima.file().equals(dopo.file())) { out.add("storage"); }
        return List.copyOf(out);
    }

    /*
     * Riepilogo non sensibile: PDF con dimensione e presenza disposizione; JSON con chiavi e
     * dimensione, messaggio solo se ammesso; corpo vuoto o altro tipo senza contenuti.
     */
    static String riepilogo(E2EClient.Risposta r, Set<String> messaggiRegistrabili) {
        String tipo = r.header("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
        int n = r.body().length;
        if (n == 0) { return "vuoto"; }
        if (tipo.startsWith("application/pdf")) {
            return "pdf byte=" + n + " disposition=" + (r.header("Content-Disposition").isPresent() ? "presente" : "assente");
        }
        if (tipo.startsWith("application/json")) {
            try {
                JsonNode j = MAPPER.readTree(r.body());
                if (j == null || !j.isObject()) { return "json non-oggetto byte=" + n; }
                Set<String> chiavi = new TreeSet<>();
                for (Iterator<String> it = j.fieldNames(); it.hasNext(); ) { chiavi.add(it.next()); }
                String base = "json chiavi=" + chiavi + " byte=" + n;
                JsonNode error = j.get("error");
                if (error != null && error.isTextual()) {
                    base += " error=" + (messaggiRegistrabili.contains(error.textValue()) ? error.textValue() : "<non registrato>");
                }
                return base;
            } catch (IOException e) {
                return "json non valido byte=" + n;
            }
        }
        return "tipo=" + (tipo.isEmpty() ? "-" : tipo.split(";")[0].trim()) + " byte=" + n;
    }

    /* Controlli C0, C1, DEL e separatori Unicode di riga/paragrafo diventano spazi. */
    static String campo(String valore) {
        return valore.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", " ");
    }

    void contamina(String causa) { if (contaminazione == null) { contaminazione = Objects.requireNonNull(causa); } }
    boolean contaminato() { return contaminazione != null; }
    String causaContaminazione() { return contaminazione; }

    List<Osservazione> registro() { return List.copyOf(registro); }

    /* Visibilità di package per le prove di formato senza infrastruttura. */
    void registra(Osservazione o) { registro.add(Objects.requireNonNull(o)); }

    /* Scrive il TSV della classe con intestazione nella directory esplicitamente indicata. */
    void scriviTsv(Path directory) throws IOException {
        Files.createDirectories(directory);
        StringBuilder testo = new StringBuilder(String.join("\t", INTESTAZIONE)).append('\n');
        for (Osservazione o : registro) {
            if (o.colonne().size() != INTESTAZIONE.size()) { throw new IllegalStateException("Colonne TSV incoerenti."); }
            testo.append(o.riga()).append('\n');
        }
        Files.writeString(directory.resolve(classe + ".tsv"), testo, StandardCharsets.UTF_8);
    }
}
