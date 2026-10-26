package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.ESTRANEA_ATTIVA;
import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.NON_SEGNALABILE;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Segnalazioni proprie e medico attivo su fixture dedicata. Le richieste modificano dati: ordine
 * fisso e verifica delle sole righe e colonne attese.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9SegnalazionePropriaE2EIntegrationTest {
    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EStato stato;
    private E2ESegnalazioni v9;
    private String foreignPrima;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        f = E2EFixtureClinica.prepara(ambiente, new E2EManifest());
        stato = new E2EStato(ambiente);
        v9 = new E2ESegnalazioni(stato, getClass());
    }

    @BeforeEach void nome(TestInfo info) { if (v9 != null) { v9.test(info.getTestMethod().orElseThrow().getName()); } }

    /* Prima TSV, poi client e ambiente; un errore TSV non salta la pulizia. */
    @AfterAll void chiudi() throws Exception {
        E2ETeardown.esegui(
                () -> { if (v9 != null) { v9.scriviTsv(); } },
                () -> { if (f != null) { f.close(); } },
                () -> { if (ambiente != null) { ambiente.close(); } });
    }

    private int id(String codice) { return f.prenotazione(codice); }

    private boolean flag(int prenotazione) throws Exception {
        return (Boolean) stato.riga("SELECT indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione = ?", prenotazione)
                .get("indisponibilita_segnalata");
    }

    /* Solo il flag delle prenotazioni indicate cambia da FALSE a TRUE. */
    private static void soloFlag(E2ESegnalazioni.Esito e, List<Integer> attese) {
        assertEquals(attese.stream().map(Long::valueOf).toList(), E2ESegnalazioni.prenotazioniCambiate(e.prima(), e.dopo()), e.riga());
        var prima = e.prima().tabelle().get("prenotazione");
        var dopo = e.dopo().tabelle().get("prenotazione");
        for (int i = 0; i < prima.size(); i++) {
            if (prima.get(i).equals(dopo.get(i))) { continue; }
            Map<String, Object> atteso = new java.util.LinkedHashMap<>(prima.get(i));
            assertEquals(Boolean.FALSE, atteso.put("indisponibilita_segnalata", Boolean.TRUE));
            assertEquals(atteso, dopo.get(i), "solo il flag cambia: " + e.riga());
        }
        for (String t : e.prima().tabelle().keySet()) {
            if (!t.equals("prenotazione")) { assertEquals(e.prima().tabelle().get(t), e.dopo().tabelle().get(t), t); }
        }
        assertEquals(e.prima().file(), e.dopo().file(), "storage invariato");
    }

    @Tag("v9-da-rivedere")
    @Order(1) @Test void foreignViewOfTheBookingBeforeItIsReported() throws Exception {
        var e = v9.segnala("C-prima", f.attore("MX"), "estranea segnalabile, flag FALSE", List.of(id("B-Q2")));
        assertEquals(404, e.status());
        assertEquals(ESTRANEA_ATTIVA, e.corpo());
        assertTrue(e.statoInvariato());
        foreignPrima = e.corpo();
    }

    @Order(2) @Test void holderReportsItsOwnRequestedBooking() throws Exception {
        int q2 = id("B-Q2");
        var e = v9.segnala("4", f.attore("MT"), "propria segnalabile (RICHIESTA da titolare)", List.of(q2));
        assertEquals(200, e.status(), e.riga());
        assertEquals("{\"idPrenotazioni\":[" + q2 + "],\"indisponibilitaSegnalata\":true}", e.corpo());
        soloFlag(e, List.of(q2));
        assertTrue(flag(q2));
    }

    @Order(3) @Test void repeatingTheReportShowsNoPersistentDifference() throws Exception {
        int q2 = id("B-Q2");
        var e = v9.segnala("7", f.attore("MT"), "propria già segnalata", List.of(q2));
        assertEquals(200, e.status(), e.riga());
        assertEquals("{\"idPrenotazioni\":[" + q2 + "],\"indisponibilitaSegnalata\":true}", e.corpo());
        /* Lo snapshot non mostra differenze persistenti; il repository esclude le prenotazioni già segnalate */
        /* dall'UPDATE. Lo snapshot non distingue un UPDATE senza effetti da nessuna istruzione. */
        assertTrue(e.statoInvariato(), "nessuna differenza persistente osservata nello snapshot");
    }

    @Order(4) @Test void effectiveDoctorReportsItsOwnConfirmedBooking() throws Exception {
        int c2 = id("B-C2");
        var e = v9.segnala("E/B-C2", f.attore("MT"), "propria segnalabile (CONFERMATA da effettivo)", List.of(c2));
        assertEquals(200, e.status(), e.riga());
        soloFlag(e, List.of(c2));
        /* Entrambe compaiono tra le segnalazioni desk pendenti. */
        var pendenti = new java.util.HashSet<Integer>();
        f.attore("DK").client().get("/api/prenotazioni/segnalazioni-indisponibilita").json()
                .forEach(n -> pendenti.add(n.get("idPrenotazione").intValue()));
        assertEquals(java.util.Set.of(id("B-Q2"), c2), pendenti);
        /* La risposta conserva l'ordine richiesto; il repository lavora in ordine crescente. */
        var echo = v9.segnala("E/eco", f.attore("MT"), "proprie già segnalate, ordine inverso", List.of(id("B-Q2"), c2));
        assertEquals("{\"idPrenotazioni\":[" + id("B-Q2") + "," + c2 + "],\"indisponibilitaSegnalata\":true}", echo.corpo());
        assertTrue(echo.statoInvariato());
    }

    @Tag("v9-da-rivedere")
    @Order(5) @Test void flaggedOwnBookingInARefusedSelectionStaysAsItIs() throws Exception {
        var e = v9.segnala("5-dopo", f.attore("MT"), "propria segnalata + estranea terminale", List.of(id("B-Q2"), id("B-R1")));
        assertEquals(409, e.status());
        assertEquals(NON_SEGNALABILE, e.corpo());
        assertTrue(e.statoInvariato());
    }

    @Tag("v9-da-rivedere")
    @Order(6) @Test void theFlagOfAForeignBookingIsNotObservable() throws Exception {
        var e = v9.segnala("C-dopo", f.attore("MX"), "estranea segnalabile, flag TRUE", List.of(id("B-Q2")));
        assertEquals(foreignPrima, e.corpo(), "stessa risposta prima e dopo la segnalazione del titolare");
        assertTrue(e.statoInvariato());
    }

    @Order(7) @Test void anInactiveDoctorNoLongerReachesTheOracle() throws Exception {
        var ad = f.attore("AD").client();
        var mx = f.attore("MX");
        int inesistente = (int) stato.conta("SELECT MAX(id_prenotazione) FROM prenotazione") + 1000;
        assertEquals(200, ad.putJson("/api/medici/" + mx.id() + "/stato", Map.of("stato", "INATTIVO")).status());
        try {
            /* Rifiuto prima del repository: stessa risposta per le categorie e nessuna differenza persistente. */
            Map<String, Integer> categorie = new java.util.LinkedHashMap<>();
            categorie.put("medico inattivo, inesistente", inesistente);
            categorie.put("medico inattivo, estranea attiva", id("B-Q1"));
            categorie.put("medico inattivo, estranea terminale", id("B-R2"));
            for (var c : categorie.entrySet()) {
                var e = v9.segnala("pre/MX-inattivo", mx, c.getKey(), List.of(c.getValue()));
                assertEquals(403, e.status(), e.riga());
                assertEquals("{\"error\":\"Operazione non autorizzata.\"}", e.corpo(), e.riga());
                assertTrue(e.statoInvariato(), e.riga());
            }
            assertEquals(200, mx.client().get("/api/auth/me").status(), "la sessione resta valida");
        } finally {
            assertEquals(200, ad.putJson("/api/medici/" + mx.id() + "/stato", Map.of("stato", "ATTIVO")).status());
        }
        /* Dopo riattivazione torna la risposta prevista per l'account operativo. */
        assertEquals(ESTRANEA_ATTIVA, v9.segnala("pre/MX-riattivato", mx, "medico riattivato", List.of(id("B-Q1"))).corpo());
    }
}
