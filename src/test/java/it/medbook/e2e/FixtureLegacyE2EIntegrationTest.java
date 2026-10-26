package it.medbook.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Fixture anomala dedicata: tre UPDATE SQL controllati devono produrre solo le combinazioni
 * richieste; il resto conserva T1 e i login passano dal limitatore reale.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixtureLegacyE2EIntegrationTest {
    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EFixtureLegacy.Legacy legacy;
    private E2EStato stato;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        stato = new E2EStato(ambiente);
        f = E2EFixtureClinica.preparaT0(ambiente, new E2EManifest());
        legacy = E2EFixtureLegacy.applica(f);
        f.passaAT1();
    }

    @AfterAll void chiudi() throws Exception {
        try { if (legacy != null) { legacy.close(); } if (f != null) { f.close(); } }
        finally { if (ambiente != null) { ambiente.close(); } }
    }

    @Test void legacyDoctorDeskAndDoctorAdministratorCombineTheirCapabilities() throws Exception {
        JsonNode md = legacy.medicoDesk().client().get("/api/auth/me").json();
        assertEquals(List.of(true, "OPERATORE_DESK", false), List.of(md.get("medico").booleanValue(), md.get("ruoloGestionale").asText(),
                md.get("paziente").booleanValue()));
        JsonNode am = legacy.medicoAmministratore().client().get("/api/auth/me").json();
        assertEquals(List.of(true, "AMMINISTRATORE", false), List.of(am.get("medico").booleanValue(), am.get("ruoloGestionale").asText(),
                am.get("paziente").booleanValue()));
        /* Capacità cumulative presenti nella sessione. */
        E2EClient lmd = legacy.medicoDesk().client(), lam = legacy.medicoAmministratore().client();
        assertEquals(200, lmd.get("/api/prenotazioni/agenda?medicoId=" + legacy.medicoDesk().id() + "&data=2031-02-05").status(), "MEDICO");
        assertEquals(200, lmd.get("/api/prenotazioni/richieste").status(), "DESK");
        assertEquals(200, lam.get("/api/prenotazioni/agenda?medicoId=" + legacy.medicoAmministratore().id() + "&data=2031-02-05").status(), "MEDICO");
        assertEquals(200, lam.get("/api/utenti").status(), "AMMINISTRATORE");
    }

    @Test void confirmedBookingWithoutEffectiveDoctorIsTheOnlyOne() throws Exception {
        var r = stato.riga("""
                SELECT pr.stato, pr.id_medico_effettivo, pr.id_paziente, pr.gestita_da, d.id_medico, d.data_ora_inizio
                FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita WHERE pr.id_prenotazione = ?""",
                legacy.prenotazioneNull());
        assertEquals("CONFERMATA", r.get("stato"));
        assertNull(r.get("id_medico_effettivo"), "L-NULL");
        assertEquals((long) f.attore("MT").id(), ((Number) r.get("id_medico")).longValue());
        assertEquals((long) f.attore("P1").id(), ((Number) r.get("id_paziente")).longValue());
        assertEquals((long) f.attore("DK").id(), ((Number) r.get("gestita_da")).longValue(), "confermata via API prima della modifica");
        assertEquals(E2EFixtureLegacy.INIZIO_S15, r.get("data_ora_inizio"));
        assertEquals(1, stato.conta("SELECT COUNT(*) FROM prenotazione WHERE stato = 'CONFERMATA' AND id_medico_effettivo IS NULL"));
        /* Le righe senza medico effettivo entrano nella query agenda solo per rilevare anomalie: */
        /* l'agenda del titolare per quel giorno viene rifiutata con 500 generico. */
        E2EClient.Risposta agenda = f.attore("MT").client().get("/api/prenotazioni/agenda?medicoId=" + f.attore("MT").id() + "&data=2031-02-05");
        assertEquals(500, agenda.status(), agenda.testo());
        assertEquals("{\"error\":\"Errore interno del server.\"}", agenda.testo(), "nessun dettaglio nel corpo");
        assertEquals(200, f.attore("MT").client().get("/api/prenotazioni/agenda?medicoId=" + f.attore("MT").id() + "&data=2031-01-20").status(),
                "gli altri giorni restano consultabili");
    }

    @Test void sqlWritesAreLimitedToTheThreeLegacyRows() throws Exception {
        assertEquals(Set.of(legacy.medicoDesk().id(), legacy.medicoAmministratore().id()), ids(
                "SELECT u.id_utente FROM utente u JOIN medico m ON m.id_utente = u.id_utente WHERE u.ruolo_gestionale IS NOT NULL"));
        assertEquals(1, stato.conta("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale = 'AMMINISTRATORE' AND id_utente <> ?", legacy.medicoAmministratore().id()),
                "il solo amministratore senza profili resta AD");
        for (E2EFixtureClinica.DefPrenotazione p : E2EFixtureClinica.PRENOTAZIONI) {
            var r = stato.riga("SELECT stato, id_medico_effettivo FROM prenotazione WHERE id_prenotazione = ?", f.prenotazione(p.codice()));
            assertEquals(p.statoT1(), r.get("stato"), p.codice());
            assertEquals(p.effettivoT1() == null ? null : (long) f.attore(p.effettivoT1()).id(),
                    r.get("id_medico_effettivo") == null ? null : ((Number) r.get("id_medico_effettivo")).longValue(), p.codice());
        }
        assertEquals(E2EFixtureClinica.PRENOTAZIONI.size() + 1, stato.conta("SELECT COUNT(*) FROM prenotazione"));
    }

    @Test void extraLoginsWentThroughTheRealThrottle() throws Exception {
        /*
         * Nove login fixture più due aggiuntivi superano la finestra da dieci: avanza il solo
         * orologio d'autenticazione.
         */
        assertTrue(f.accessi().avanzamenti() >= 1, "finestra del limite superata avanzando il Clock di autenticazione");
        assertEquals(E2EFixtureClinica.T1, ambiente.server().domainClock().instant(), "Clock di dominio non toccato dal limite");
        for (var a : f.attori().values()) { assertEquals(200, a.client().get("/api/auth/me").status(), a.codice() + ": sessione ancora valida"); }
    }

    private Set<Integer> ids(String select) throws Exception {
        Set<Integer> ids = new HashSet<>();
        stato.righe(select).forEach(r -> ids.add(((Number) r.values().iterator().next()).intValue()));
        return ids;
    }
}
