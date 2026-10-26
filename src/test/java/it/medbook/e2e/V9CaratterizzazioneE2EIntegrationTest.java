package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Caratterizzazione corrente delle segnalazioni di prenotazioni altrui. Ogni rifiuto deve lasciare
 * invariati dati e file. Lo snapshot non dimostra atomicità sotto guasti o concorrenza. I test con
 * tag specifici descrivono differenze informative del contratto attuale, non un requisito
 * desiderabile; quelli sugli input e sugli attori non autorizzati verificano rifiuti indipendenti
 * da tali differenze.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V9CaratterizzazioneE2EIntegrationTest {
    static final String INESISTENTE = "{\"error\":\"Prenotazione non trovata.\"}";
    static final String ESTRANEA_ATTIVA = "{\"error\":\"Prenotazione non trovata per il medico dichiarato.\"}";
    static final String NON_SEGNALABILE = "{\"error\":\"Prenotazione non segnalabile.\"}";
    static final String INCOERENTE = "{\"error\":\"Dati della prenotazione incoerenti.\"}";
    static final String GIA_INIZIATO = "{\"error\":\"L'appuntamento è già iniziato.\"}";
    static final String NON_AUTORIZZATA = "{\"error\":\"Operazione non autorizzata.\"}";
    static final String AUTENTICAZIONE = "{\"error\":\"Autenticazione richiesta.\"}";

    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EStato stato;
    private E2ESegnalazioni v9;
    private int inesistente;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        f = E2EFixtureClinica.prepara(ambiente, new E2EManifest());
        stato = new E2EStato(ambiente);
        v9 = new E2ESegnalazioni(stato, getClass());
        inesistente = (int) stato.conta("SELECT MAX(id_prenotazione) FROM prenotazione") + 1000;
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM prenotazione WHERE id_prenotazione = ?", inesistente));
        /* I casi dipendono dall'ordine crescente degli ID creati dalla fixture. */
        List<Integer> ids = E2EFixtureClinica.PRENOTAZIONI.stream().map(p -> f.prenotazione(p.codice())).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @BeforeEach void nome(TestInfo info) { if (v9 != null) { v9.test(info.getTestMethod().orElseThrow().getName()); } }

    @AfterAll void chiudi() throws Exception {
        try { if (v9 != null) { v9.scriviTsv(); } }
        finally { try { if (f != null) { f.close(); } } finally { if (ambiente != null) { ambiente.close(); } } }
    }

    private int id(String codice) { return f.prenotazione(codice); }

    static E2ESegnalazioni.Esito rifiutata(E2ESegnalazioni.Esito e, int status, String corpo) {
        assertEquals(status, e.status(), e.riga());
        assertEquals(corpo, e.corpo(), e.riga());
        assertTrue(e.statoInvariato(), "nessuna differenza persistente osservata nello snapshot: " + e.riga());
        assertEquals("application/json; charset=UTF-8", e.contentType(), e.riga());
        assertEquals("no-store", e.cacheControl(), e.riga());
        assertEquals(corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, e.contentLength(), e.riga());
        return e;
    }

    /* Ogni attore negativo prova ID inesistente, prenotazione altrui attiva e terminale. */
    private Map<String, Integer> categorie() {
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("inesistente", inesistente);
        c.put("estranea attiva (B-Q1, RICHIESTA)", id("B-Q1"));
        c.put("estranea terminale (B-R2, EFFETTUATA)", id("B-R2"));
        return c;
    }

    // ---------------------------------------------------------------- 1. single foreign selections (MX)

    @Tag("v9-da-invertire")
    @Test void foreignDoctorDistinguishesNonexistentFromForeignBookingsByStatusAndMessage() throws Exception {
        var mx = f.attore("MX");
        var a = rifiutata(v9.segnala("A", mx, "inesistente", List.of(inesistente)), 404, INESISTENTE);
        var b = rifiutata(v9.segnala("B", mx, "estranea terminale (EFFETTUATA)", List.of(id("B-R2"))), 409, NON_SEGNALABILE);
        var c = rifiutata(v9.segnala("C", mx, "estranea segnalabile (RICHIESTA futura)", List.of(id("B-Q1"))), 404, ESTRANEA_ATTIVA);
        /* Stesso status con messaggio diverso: il solo 404 non distingue l'esistenza, il corpo sì. */
        assertEquals(a.status(), c.status());
        assertNotEquals(a.corpo(), c.corpo(), "V9: inesistente e estranea attiva distinguibili dal corpo");
        assertNotEquals(a.status(), b.status(), "V9: estranea terminale distinguibile dallo stato HTTP");
        assertNotEquals(a.contentLength(), c.contentLength(), "anche la sola lunghezza del corpo distingue i casi");
    }

    @Tag("v9-da-invertire")
    @Test void responseRevealsTheStateCategoryNotTheExactState() throws Exception {
        var mx = f.attore("MX");
        /* EFFETTUATA con o senza referto e NON_EROGATA sono presenti e restituiscono la stessa risposta. */
        /* ANNULLATA, RIFIUTATA e PAZIENTE_ASSENTE non sono presenti in questa fixture e non vengono provati qui. */
        for (String codice : List.of("B-R1", "B-R2", "B-E1", "B-PM", "B-IN", "B-EC")) {
            rifiutata(v9.segnala("B/" + codice, mx, "estranea terminale (" + E2EFixtureClinica.definizione(codice).statoT1() + ")",
                    List.of(id(codice))), 409, NON_SEGNALABILE);
        }
        // RICHIESTA o CONFERMATA, futura o già iniziata: stessa risposta senza informazioni temporali.
        for (String codice : List.of("B-Q1", "B-Q2", "B-C2", "B-C1")) {
            rifiutata(v9.segnala("C/" + codice, mx, "estranea segnalabile (" + E2EFixtureClinica.definizione(codice).statoT1()
                    + (codice.equals("B-C1") ? ", slot terminato" : ", futura") + ")", List.of(id(codice))), 404, ESTRANEA_ATTIVA);
        }
    }

    // ---------------------------------------------------------------- 2. own selections refused (MT, MS)

    @Test void ownBookingsRefusedForStateOrTimeShowNoPersistentDifference() throws Exception {
        /* Prenotazione propria terminale: MT è medico effettivo di B-R2. */
        rifiutata(v9.segnala("E-/B-R2", f.attore("MT"), "propria terminale", List.of(id("B-R2"))), 409, NON_SEGNALABILE);
        /* Propria, coerente e di competenza, ma già iniziata: il controllo temporale viene dopo. */
        rifiutata(v9.segnala("E-/B-C1", f.attore("MS"), "propria segnalabile, slot terminato", List.of(id("B-C1"))), 409, GIA_INIZIATO);
    }

    // ---------------------------------------------------------------- 3. multiple selections

    @Tag("v9-da-invertire")
    @Test void foreignSelectionsAreProcessedInAscendingIdOrderWhateverTheJsonOrder() throws Exception {
        var mx = f.attore("MX");
        assertTrue(id("B-R2") < id("B-Q1") && id("B-Q1") < id("B-EC"));
        /* Decide l'ID minore che fallisce; invertire l'array non cambia l'esito. */
        for (List<Integer> sel : List.of(List.of(id("B-R2"), id("B-Q1")), List.of(id("B-Q1"), id("B-R2")))) {
            rifiutata(v9.segnala("M1", mx, "terminale + attiva", sel), 409, NON_SEGNALABILE);
        }
        for (List<Integer> sel : List.of(List.of(id("B-Q1"), id("B-EC")), List.of(id("B-EC"), id("B-Q1")))) {
            rifiutata(v9.segnala("M2", mx, "attiva + terminale", sel), 404, ESTRANEA_ATTIVA);
        }
        /* Si verifica l'esistenza di tutti gli ID prima degli stati; un ID inesistente determina la risposta. */
        for (List<Integer> sel : List.of(List.of(id("B-R2"), inesistente), List.of(inesistente, id("B-R2")),
                List.of(id("B-Q1"), inesistente))) {
            rifiutata(v9.segnala("M3", mx, "inesistente + estranea", sel), 404, INESISTENTE);
        }
    }

    /* Aspettative relative alle selezioni miste proprie e altrui nel contratto corrente. */
    @Tag("v9-da-rivedere")
    @Test void ownSignalableBookingsShowNoPersistentDifferenceAfterARefusedMixedSelection() throws Exception {
        var mt = f.attore("MT");
        int q2 = id("B-Q2"), c2 = id("B-C2");
        /* Prenotazione propria attiva insieme a terminale non più propria. */
        rifiutata(v9.segnala("5", mt, "propria + estranea terminale", List.of(q2, id("B-R1"))), 409, NON_SEGNALABILE);
        /* Prenotazione propria insieme a ID inesistente. */
        rifiutata(v9.segnala("6", mt, "propria + inesistente", List.of(q2, inesistente)), 404, INESISTENTE);
        /* Prenotazione propria con una segnalabile ora di MS e ID minore: decide quella altrui. */
        rifiutata(v9.segnala("M4", mt, "propria + estranea segnalabile", List.of(q2, id("B-C1"))), 404, ESTRANEA_ATTIVA);
        /* Due prenotazioni proprie superano i controlli, poi una altrui con ID maggiore viene rifiutata. */
        /* Lo snapshot resta invariato. Nel repository tutti i controlli precedono il primo */
        /* UPDATE e il rifiuto comporta rollback; ciò non dimostra atomicità per qualsiasi guasto o concorrenza. */
        assertTrue(c2 < q2 && q2 < id("B-PM") && q2 < id("B-EC"));
        rifiutata(v9.segnala("M5", mt, "due proprie + estranea terminale", List.of(id("B-PM"), q2, c2)), 409, NON_SEGNALABILE);
        rifiutata(v9.segnala("M6", mt, "due proprie + estranea NON_EROGATA", List.of(c2, q2, id("B-EC"))), 409, NON_SEGNALABILE);
        for (int b : List.of(q2, c2)) {
            assertFalse((Boolean) stato.riga("SELECT indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione = ?", b)
                    .get("indisponibilita_segnalata"), "flag invariato");
        }
        assertEquals("[]", f.attore("DK").client().get("/api/prenotazioni/segnalazioni-indisponibilita").testo());
    }



    /*
     * Rifiuti prima del repository per paziente, desk, amministratore, anonimo e ID medico
     * dichiarato diverso: stessa risposta per risorse inesistenti, altrui attive o terminali, senza
     * differenze persistenti.
     */
    @Test void negativeMatrixIsUniformWhenTheRefusalPrecedesTheRepository() throws Exception {
        record Negativo(String codice, E2EClient client, int medicoId, int status, String corpo) { }
        List<Negativo> attori = List.of(
                new Negativo("P1", f.attore("P1").client(), f.attore("P1").id(), 403, NON_AUTORIZZATA),
                new Negativo("DK", f.attore("DK").client(), f.attore("DK").id(), 403, NON_AUTORIZZATA),
                new Negativo("AD", f.attore("AD").client(), f.attore("AD").id(), 403, NON_AUTORIZZATA),
                new Negativo("Anon", f.anonimo(), f.attore("MX").id(), 401, AUTENTICAZIONE),
                new Negativo("MX-attore-MT", f.attore("MX").client(), f.attore("MT").id(), 403, NON_AUTORIZZATA));
        for (Negativo n : attori) {
            for (var c : categorie().entrySet()) {
                var e = v9.segnala("neg/" + n.codice(), n.codice(), n.client(), n.medicoId(), c.getKey(), List.of(c.getValue()));
                assertEquals(n.status(), e.status(), e.riga());
                assertEquals(n.corpo(), e.corpo(), e.riga());
                assertTrue(e.statoInvariato(), e.riga());
            }
        }
    }

    /* Il profilo misto raggiunge il repository come medico; si verifica la regola applicata alle selezioni miste. */
    @Tag("v9-da-rivedere")
    @Test void dualProfileDoctorGetsTheSameCategoryAnswersAsAForeignDoctor() throws Exception {
        var pm = f.attore("PM");
        rifiutata(v9.segnala("PM/A", pm, "inesistente", List.of(inesistente)), 404, INESISTENTE);
        rifiutata(v9.segnala("PM/C", pm, "estranea attiva (B-Q1)", List.of(id("B-Q1"))), 404, ESTRANEA_ATTIVA);
        rifiutata(v9.segnala("PM/B", pm, "estranea terminale (B-R2)", List.of(id("B-R2"))), 409, NON_SEGNALABILE);
    }

    @Test void malformedSelectionsAreRefusedBeforeAnyLookup() throws Exception {
        var mx = f.attore("MX");
        List<LinkedHashMap<String, Object>> corpi = List.of(
                corpo(mx.id(), List.of(id("B-Q1"), id("B-Q1")), false), corpo(mx.id(), List.of(0), false),
                corpo(mx.id(), List.of(-1), false), corpo(mx.id(), List.of(), false), corpo(mx.id(), List.of(id("B-Q1")), true));
        for (var c : corpi) {
            var e = v9.invia("8", "MX", mx.client(), "malformata", c);
            assertEquals(400, e.status(), e.riga());
            assertEquals("{\"error\":\"Richiesta non valida.\"}", e.corpo(), e.riga());
            assertTrue(e.statoInvariato(), e.riga());
        }
    }

    private static LinkedHashMap<String, Object> corpo(int medicoId, List<Integer> ids, boolean extra) {
        LinkedHashMap<String, Object> c = new LinkedHashMap<>();
        c.put("medicoId", medicoId);
        c.put("idPrenotazioni", ids);
        if (extra) { c.put("extra", 1); }
        return c;
    }
}
