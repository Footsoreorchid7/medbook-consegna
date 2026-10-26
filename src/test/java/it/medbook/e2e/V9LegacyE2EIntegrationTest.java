package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.ESTRANEA_ATTIVA;
import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.INCOERENTE;
import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.INESISTENTE;
import static it.medbook.e2e.V9CaratterizzazioneE2EIntegrationTest.NON_SEGNALABILE;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Prenotazione confermata senza medico effettivo nella sola fixture anomala dedicata. È uno stato
 * non creabile dalle API; i test caratterizzano la risposta corrente senza normalizzarlo.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V9LegacyE2EIntegrationTest {
    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EFixtureLegacy.Legacy legacy;
    private E2EStato stato;
    private E2ESegnalazioni v9;
    private int lnull, inesistente;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        f = E2EFixtureClinica.preparaT0(ambiente, new E2EManifest());
        legacy = E2EFixtureLegacy.applica(f);
        f.passaAT1();
        stato = new E2EStato(ambiente);
        v9 = new E2ESegnalazioni(stato, getClass());
        lnull = legacy.prenotazioneNull();
        inesistente = (int) stato.conta("SELECT MAX(id_prenotazione) FROM prenotazione") + 1000;
        assertTrue(f.prenotazione("B-Q1") < lnull && f.prenotazione("B-R2") < lnull, "L-NULL creata dopo le prenotazioni ordinarie");
    }

    @BeforeEach void nome(TestInfo info) { if (v9 != null) { v9.test(info.getTestMethod().orElseThrow().getName()); } }

    /* Prima TSV, poi agenti aggiuntivi, client e ambiente; l'errore TSV non evita la pulizia. */
    @AfterAll void chiudi() throws Exception {
        E2ETeardown.esegui(
                () -> { if (v9 != null) { v9.scriviTsv(); } },
                () -> { if (legacy != null) { legacy.close(); } },
                () -> { if (f != null) { f.close(); } },
                () -> { if (ambiente != null) { ambiente.close(); } });
    }

    private void rifiutata(E2ESegnalazioni.Esito e, int status, String corpo) {
        assertEquals(status, e.status(), e.riga());
        assertEquals(corpo, e.corpo(), e.riga());
        assertTrue(e.statoInvariato(), e.riga());
    }

    @Tag("v9-da-invertire")
    @Test void foreignDoctorSeesTheLegacyInconsistencyAsAFourthCategory() throws Exception {
        rifiutata(v9.segnala("D", f.attore("MX"), "estranea con incoerenza legacy (CONFERMATA, effettivo NULL)", List.of(lnull)), 409, INCOERENTE);
        /* L'account storico medico-desk, come medico, osserva le stesse categorie. */
        rifiutata(v9.segnala("D/L-MD", legacy.medicoDesk(), "incoerenza legacy", List.of(lnull)), 409, INCOERENTE);
        rifiutata(v9.segnala("C/L-MD", legacy.medicoDesk(), "estranea segnalabile", List.of(f.prenotazione("B-Q1"))), 404, ESTRANEA_ATTIVA);
    }

    /* Il titolare della prenotazione senza effettivo viene verificato secondo il comportamento corrente. */
    @Tag("v9-da-rivedere")
    @Test void holderOfTheLegacyBookingGetsTheCoherenceRefusal() throws Exception {
        /* Il controllo di coerenza precede quello di competenza anche per il titolare. */
        rifiutata(v9.segnala("D/MT", f.attore("MT"), "titolare, incoerenza legacy", List.of(lnull)), 409, INCOERENTE);
    }

    @Tag("v9-da-invertire")
    @Test void legacyBookingInMultipleSelectionsFollowsTheSameOrder() throws Exception {
        var mx = f.attore("MX");
        int q1 = f.prenotazione("B-Q1"), r2 = f.prenotazione("B-R2");
        for (List<Integer> sel : List.of(List.of(lnull, inesistente), List.of(inesistente, lnull))) {
            rifiutata(v9.segnala("D+A", mx, "legacy + inesistente", sel), 404, INESISTENTE);
        }
        for (List<Integer> sel : List.of(List.of(q1, lnull), List.of(lnull, q1))) {
            rifiutata(v9.segnala("C+D", mx, "estranea attiva (id minore) + legacy", sel), 404, ESTRANEA_ATTIVA);
        }
        for (List<Integer> sel : List.of(List.of(r2, lnull), List.of(lnull, r2))) {
            rifiutata(v9.segnala("B+D", mx, "estranea terminale (id minore) + legacy", sel), 409, NON_SEGNALABILE);
        }
        /* Le proprie prenotazioni con ID minore passano; quella incoerente successiva fallisce senza scritture. */
        int c2 = f.prenotazione("B-C2"), q2 = f.prenotazione("B-Q2");
        rifiutata(v9.segnala("E+D", f.attore("MT"), "due proprie + legacy", List.of(lnull, q2, c2)), 409, INCOERENTE);
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM prenotazione WHERE indisponibilita_segnalata = TRUE"));
    }

    @Tag("v9-da-invertire")
    @Test void enumerationInTheLegacyCatalogAlsoRevealsInconsistentBookings() throws Exception {
        E2EStato.Istantanea prima = v9.istantanea();
        int massimo = (int) stato.conta("SELECT MAX(id_prenotazione) FROM prenotazione");
        Map<Integer, String> dedotte = new LinkedHashMap<>(), reali = new LinkedHashMap<>();
        for (int id = 1; id <= massimo + 2; id++) {
            String corpo = v9.segnala("enum-legacy/" + id, f.attore("MX"), "enumerazione", List.of(id)).corpo();
            dedotte.put(id, switch (corpo) {
                case INESISTENTE -> "inesistente";
                case NON_SEGNALABILE -> "terminale";
                case INCOERENTE -> "incoerente";
                case ESTRANEA_ATTIVA -> "attiva";
                default -> "altro: " + corpo;
            });
            var righe = stato.righe("SELECT stato, id_medico_effettivo, gestita_da, data_ora_gestione FROM prenotazione WHERE id_prenotazione = ?", id);
            if (righe.isEmpty()) { reali.put(id, "inesistente"); continue; }
            var r = righe.getFirst();
            String s = (String) r.get("stato");
            boolean incoerente = (s.equals("RICHIESTA") && (r.get("id_medico_effettivo") != null || r.get("gestita_da") != null))
                    || (s.equals("CONFERMATA") && (r.get("id_medico_effettivo") == null || r.get("gestita_da") == null || r.get("data_ora_gestione") == null));
            reali.put(id, !Set.of("RICHIESTA", "CONFERMATA").contains(s) ? "terminale" : incoerente ? "incoerente" : "attiva");
        }
        assertEquals(reali, dedotte);
        assertEquals("incoerente", dedotte.get(lnull));
        assertEquals(prima, v9.istantanea());
    }
}
