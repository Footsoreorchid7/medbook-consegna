package it.medbook.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/*
 * Invia una segnalazione con login, cookie, Origin e CSRF reali. Confronta prima e dopo dati
 * clinici e proiezioni account/medico; le sessioni in memoria non fanno parte dello snapshot. Il
 * TSV contiene riepiloghi, non corpi o segreti. Differenze inattese contaminano l'ambiente e
 * bloccano le verifiche dipendenti.
 */
final class E2ESegnalazioni {
    static final String PERCORSO = "/api/prenotazioni/segnala-indisponibilita";
    static final List<String> INTESTAZIONE = List.of("classe", "test", "caso", "attore", "categoria", "metodo", "percorso",
            "richiesta", "status", "corpo", "stato");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* Esito osservato: statoInvariato indica nessuna differenza persistente rilevata nello snapshot. */
    record Esito(String classe, String test, String caso, String attore, String categoria, String richiesta, int status,
                 String corpo, String contentType, String cacheControl, long contentLength, boolean statoInvariato,
                 E2EStato.Istantanea prima, E2EStato.Istantanea dopo) {
        List<String> colonne() {
            return List.of(classe, test, caso, attore, categoria, "POST", PERCORSO, richiesta, Integer.toString(status), corpo,
                    statoInvariato ? "invariato" : "MODIFICATO");
        }
        String riga() { return String.join("\t", colonne().stream().map(E2ESegnalazioni::campo).toList()); }
    }

    private final E2EStato stato;
    private final String classe;
    private final List<Esito> registro = new ArrayList<>();
    private String test = "-";

    E2ESegnalazioni(E2EStato stato, Class<?> classe) {
        this.stato = Objects.requireNonNull(stato);
        this.classe = classe.getSimpleName();
    }

    /* Nome del metodo di test registrato nelle osservazioni successive. */
    void test(String nome) { this.test = Objects.requireNonNull(nome); }

    Esito segnala(String caso, E2EFixtureClinica.Attore attore, String categoria, List<Integer> ids) throws Exception {
        return segnala(caso, attore.codice(), attore.client(), attore.id(), categoria, ids);
    }

    Esito segnala(String caso, String attore, E2EClient client, int medicoId, String categoria, List<Integer> ids) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("medicoId", medicoId);
        body.put("idPrenotazioni", ids);
        return invia(caso, attore, client, categoria, body);
    }

    /* Corpo anche malformato; la mappa deve conservare l'ordine dei campi serializzati. */
    Esito invia(String caso, String attore, E2EClient client, String categoria, LinkedHashMap<String, Object> body) throws Exception {
        return invia(caso, attore, client, categoria, (Map<String, Object>) body);
    }

    private Esito invia(String caso, String attore, E2EClient client, String categoria, Map<String, Object> body) throws Exception {
        String richiesta = MAPPER.writeValueAsString(body);
        E2EStato.Istantanea prima = istantanea();
        E2EClient.Risposta r = client.invia("POST", PERCORSO, richiesta, Map.of());
        E2EStato.Istantanea dopo = istantanea();
        Esito e = new Esito(classe, test, caso, attore, categoria, richiesta, r.status(), r.testo(),
                r.header("Content-Type").orElse(""), r.header("Cache-Control").orElse(""),
                r.header("Content-Length").map(Long::parseLong).orElse(-1L), prima.equals(dopo), prima, dopo);
        registro.add(e);
        return e;
    }

    /* Snapshot clinico con stato medico e accesso account, mai hash password. */
    E2EStato.Istantanea istantanea() throws Exception {
        E2EStato.Istantanea base = stato.istantanea();
        Map<String, List<Map<String, Object>>> tabelle = new java.util.TreeMap<>(base.tabelle());
        tabelle.put("medico", stato.righe("SELECT id_utente, stato FROM medico ORDER BY id_utente"));
        tabelle.put("utente", stato.righe("SELECT id_utente, ruolo_gestionale, stato_accesso, generazione_autenticazione "
                + "FROM utente ORDER BY id_utente"));
        return new E2EStato.Istantanea(tabelle, base.file());
    }

    List<Esito> registro() { return List.copyOf(registro); }

    /*
     * Scrive TSV con intestazione nella directory della proprietà medbook.e2e.osservazioni, per
     * default target/v9-osservazioni. L'operatore può copiarlo fuori per conservarlo dopo mvn
     * clean.
     */
    void scriviTsv() throws IOException {
        Path dir = Path.of(System.getProperty("medbook.e2e.osservazioni", "target/v9-osservazioni"));
        Files.createDirectories(dir);
        StringBuilder testo = new StringBuilder(String.join("\t", INTESTAZIONE)).append('\n');
        for (Esito e : registro) {
            if (e.colonne().size() != INTESTAZIONE.size()) { throw new IllegalStateException("Colonne TSV incoerenti."); }
            testo.append(e.riga()).append('\n');
        }
        Files.writeString(dir.resolve(classe + ".tsv"), testo, StandardCharsets.UTF_8);
    }

    /* Tabulazioni e ritorni a capo non devono comparire dentro un campo. */
    static String campo(String valore) { return valore.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }

    /** Bookings whose row differs between two snapshots, by id. */
    static List<Object> prenotazioniCambiate(E2EStato.Istantanea prima, E2EStato.Istantanea dopo) {
        List<Object> cambiate = new ArrayList<>();
        var a = prima.tabelle().get("prenotazione");
        var b = dopo.tabelle().get("prenotazione");
        if (a.size() != b.size()) { throw new IllegalStateException("Numero di prenotazioni cambiato."); }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) { cambiate.add(a.get(i).get("id_prenotazione")); }
        }
        return cambiate;
    }
}
