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
 * Prima segnalazione di due prenotazioni proprie, una RICHIESTA del titolare e una CONFERMATA
 * dell'effettivo, entrambe senza flag, su fixture dedicata. Transizione valida da preservare.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V9SegnalazioneMultiplaE2EIntegrationTest {
    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EStato stato;
    private E2ESegnalazioni v9;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        f = E2EFixtureClinica.prepara(ambiente, new E2EManifest());
        stato = new E2EStato(ambiente);
        v9 = new E2ESegnalazioni(stato, getClass());
    }

    @BeforeEach void nome(TestInfo info) { if (v9 != null) { v9.test(info.getTestMethod().orElseThrow().getName()); } }

    @AfterAll void chiudi() throws Exception {
        try { if (v9 != null) { v9.scriviTsv(); } }
        finally { try { if (f != null) { f.close(); } } finally { if (ambiente != null) { ambiente.close(); } } }
    }

    private boolean flag(int prenotazione) throws Exception {
        return (Boolean) stato.riga("SELECT indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione = ?", prenotazione)
                .get("indisponibilita_segnalata");
    }

    @Test void firstReportOfTwoOwnBookingsSetsBothFlagsAndNothingElse() throws Exception {
        int q2 = f.prenotazione("B-Q2"), c2 = f.prenotazione("B-C2");
        assertFalse(flag(q2) || flag(c2), "flag inizialmente FALSE");
        var e = v9.segnala("multipla", f.attore("MT"), "due proprie segnalabili, flag FALSE", List.of(q2, c2));
        assertEquals(200, e.status(), e.riga());
        assertEquals("{\"idPrenotazioni\":[" + q2 + "," + c2 + "],\"indisponibilitaSegnalata\":true}", e.corpo());
        assertTrue(flag(q2) && flag(c2), "entrambi i flag TRUE");

        /* Cambiano esattamente due righe e solo il flag FALSE verso TRUE. */
        assertEquals(List.of((long) c2, (long) q2), E2ESegnalazioni.prenotazioniCambiate(e.prima(), e.dopo()));
        var prima = e.prima().tabelle().get("prenotazione");
        var dopo = e.dopo().tabelle().get("prenotazione");
        for (int i = 0; i < prima.size(); i++) {
            if (prima.get(i).equals(dopo.get(i))) { continue; }
            Map<String, Object> atteso = new LinkedHashMap<>(prima.get(i));
            assertEquals(Boolean.FALSE, atteso.put("indisponibilita_segnalata", Boolean.TRUE));
            assertEquals(atteso, dopo.get(i), "nessun'altra colonna cambia");
        }
        for (String t : e.prima().tabelle().keySet()) {
            if (!t.equals("prenotazione")) { assertEquals(e.prima().tabelle().get(t), e.dopo().tabelle().get(t), t); }
        }
        assertEquals(e.prima().file(), e.dopo().file(), "storage invariato");

        /* Ripetizione anche in ordine inverso: 200 senza differenze persistenti nello snapshot. */
        for (List<Integer> sel : List.of(List.of(q2, c2), List.of(c2, q2))) {
            var r = v9.segnala("multipla/ripetuta", f.attore("MT"), "due proprie già segnalate", sel);
            assertEquals(200, r.status(), r.riga());
            assertTrue(r.statoInvariato(), r.riga());
        }
    }
}
