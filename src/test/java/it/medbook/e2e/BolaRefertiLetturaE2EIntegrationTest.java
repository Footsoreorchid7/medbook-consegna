package it.medbook.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Metadati e download referto in sola lettura: proprietari, pazienti altrui, medici e ID
 * inesistente. Ogni richiesta confronta snapshot prima/dopo; una differenza inattesa fallisce e
 * contamina l'ambiente. Non copre directory vuote, permessi, timestamp filesystem o sessioni in
 * memoria.
 */
@Tag("e2e-docker")
@Tag("bola-contratto")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BolaRefertiLetturaE2EIntegrationTest {
    static final Path OSSERVAZIONI = Path.of("target", "bola41-osservazioni");
    static final String REFERTO_NON_TROVATO = "Referto non trovato.";
    static final String NON_AUTORIZZATA = "Operazione non autorizzata.";

    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EOsservazioni oss;
    private int inesistente;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        f = E2EFixtureClinica.prepara(ambiente, new E2EManifest());
        E2EStato stato = new E2EStato(ambiente);
        oss = new E2EOsservazioni(stato, getClass(), Set.of(REFERTO_NON_TROVATO, NON_AUTORIZZATA));
        inesistente = (int) stato.conta("SELECT MAX(id_prenotazione) FROM prenotazione") + 1000;
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM prenotazione WHERE id_prenotazione = ?", inesistente));
        /* I tre referti esistono e appartengono ai pazienti sintetici previsti. */
        for (String b : List.of("B-R1", "B-R2", "B-PM")) {
            assertNotNull(f.referto(b), b);
            assertEquals((long) f.attore(E2EFixtureClinica.definizione(b).paziente()).id(), ((Number) stato.riga(
                    "SELECT id_paziente FROM prenotazione WHERE id_prenotazione = ?", f.prenotazione(b)).get("id_paziente")).longValue());
        }
    }

    @BeforeEach void inizio(TestInfo info) {
        if (oss == null) { return; }
        oss.test(info.getTestMethod().orElseThrow().getName());
        assertFalse(oss.contaminato(), "ambiente contaminato, nessuna evidenza valida: " + oss.causaContaminazione());
    }

    @AfterAll void chiudi() throws Exception {
        E2ETeardown.esegui(
                () -> { if (oss != null) { oss.scriviTsv(OSSERVAZIONI); } },
                () -> { if (f != null) { f.close(); } },
                () -> { if (ambiente != null) { ambiente.close(); } });
    }



    private static String p8(int prenotazione) { return "/api/prenotazioni/" + prenotazione + "/referto"; }
    private static String p9(int prenotazione) { return "/api/prenotazioni/" + prenotazione + "/referto/documento"; }

    private E2EOsservazioni.Osservazione osserva(String caso, String attore, E2EClient client, String categoria, String percorso)
            throws Exception {
        E2EOsservazioni.Osservazione o = oss.get(caso, attore, client, categoria, percorso);
        if (!o.invariato()) { oss.contamina(caso + " " + attore + " " + percorso + ": " + o.differenze()); }
        assertTrue(o.invariato(), "differenza persistente osservata: " + o.differenze());
        return o;
    }

    private E2EOsservazioni.Osservazione osserva(String caso, String attore, String categoria, String percorso) throws Exception {
        return osserva(caso, attore, f.attore(attore).client(), categoria, percorso);
    }

    /* Rifiuto con JSON esatto, no-store, senza contenuti referto o header PDF. */
    private static void negazione(E2EOsservazioni.Osservazione o, int status, String messaggio) throws Exception {
        E2EClient.Risposta r = o.risposta();
        assertEquals(status, r.status(), o.caso());
        if (messaggio != null) { assertEquals("{\"error\":\"" + messaggio + "\"}", r.testo(), o.caso()); }
        else {
            var j = r.json();
            assertTrue(j.isObject() && j.size() == 1 && j.get("error").isTextual(), "corpo {\"error\":…}: " + o.caso());
        }
        assertEquals("no-store", r.header("Cache-Control").orElse(null), o.caso());
        assertTrue(r.header("Content-Disposition").isEmpty(), o.caso());
        assertFalse(r.header("Content-Type").orElse("").toLowerCase().startsWith("application/pdf"), o.caso());
    }

    // ---------------------------------------------------------------- owners (§3, §6.2, §6.3 case 4)

    @Test void ownersReadTheMetadataOfTheirOwnReport() throws Exception {
        for (String[] c : new String[][]{{"P1", "B-R1"}, {"P2", "B-R2"}, {"PM", "B-PM"}}) {
            var o = osserva("P8/" + c[1], c[0], "proprietario", p8(f.prenotazione(c[1])));
            assertEquals(200, o.risposta().status(), o.caso());
            assertEquals("no-store", o.risposta().header("Cache-Control").orElse(null), o.caso());
        }
    }

    @Test void ownersDownloadExactlyTheirOwnDocument() throws Exception {
        for (String[] c : new String[][]{{"P1", "B-R1", "A"}, {"P2", "B-R2", "B"}, {"PM", "B-PM", "R-PM"}}) {
            var o = osserva("P9/" + c[1], c[0], "proprietario", p9(f.prenotazione(c[1])));
            E2EClient.Risposta r = o.risposta();
            assertEquals(200, r.status(), o.caso());
            byte[] atteso = f.documento(c[2]);
            assertEquals(atteso.length, r.body().length, o.caso() + ": dimensione");
            assertEquals(Long.toString(atteso.length), r.header("Content-Length").orElse(null), o.caso());
            assertArrayEquals(atteso, r.body(), o.caso() + ": byte del documento atteso");
            assertEquals(E2EStato.sha256(atteso), E2EStato.sha256(r.body()), o.caso() + ": SHA-256");
            assertEquals("no-store", r.header("Cache-Control").orElse(null), o.caso());
        }
        /* Il proprietario riceve i byte del proprio documento, mai quelli dell'altro paziente. */
        assertFalse(java.util.Arrays.equals(f.documento("A"), f.documento("B")));
    }



    @Test void foreignPatientsGetTheSame404AsANonexistentReport() throws Exception {
        String[][] casi = {{"P2", "B-R1"}, {"PM", "B-R1"}, {"P1", "B-PM"}, {"P2", "B-PM"}};
        for (String[] c : casi) {
            for (boolean documento : new boolean[]{false, true}) {
                String endpoint = documento ? "P9" : "P8";
                int altrui = f.prenotazione(c[1]);
                var estranea = osserva(endpoint + "/" + c[1], c[0], "paziente estraneo", documento ? p9(altrui) : p8(altrui));
                var assente = osserva(endpoint + "/inesistente", c[0], "id inesistente", documento ? p9(inesistente) : p8(inesistente));
                negazione(estranea, 404, REFERTO_NON_TROVATO);
                negazione(assente, 404, REFERTO_NON_TROVATO);
                /* Stesso endpoint, attore, parser e fase di rifiuto: risposte identiche. */
                assertEquals(assente.risposta().testo(), estranea.risposta().testo(), estranea.caso() + " " + c[0]);
            }
        }
    }

    // ---------------------------------------------------------------- capability (§3 note 1, §6.3 case 2)

    @Test void theDoctorCapabilityDoesNotGrantReportReading() throws Exception {
        /* MS è effettivo e autore del caricamento R1; MT titolare originario, MX estraneo. */
        for (String medico : List.of("MT", "MS", "MX")) {
            for (String b : List.of("B-R1", "B-PM")) {
                int id = f.prenotazione(b);
                negazione(osserva("P8/" + b, medico, "medico", p8(id)), 403, NON_AUTORIZZATA);
                negazione(osserva("P9/" + b, medico, "medico", p9(id)), 403, NON_AUTORIZZATA);
            }
        }
    }

    @Test void deskAndAdministratorHaveNoPatientCapability() throws Exception {
        for (String attore : List.of("DK", "AD")) {
            for (String b : List.of("B-R1", "B-PM")) {
                int id = f.prenotazione(b);
                negazione(osserva("P8/" + b, attore, "gestionale", p8(id)), 403, NON_AUTORIZZATA);
                negazione(osserva("P9/" + b, attore, "gestionale", p9(id)), 403, NON_AUTORIZZATA);
            }
        }
    }

    @Test void anonymousRequestsAreUnauthenticated() throws Exception {
        for (String b : List.of("B-R1", "B-PM")) {
            int id = f.prenotazione(b);
            for (String percorso : List.of(p8(id), p9(id))) {
                var o = osserva((percorso.endsWith("documento") ? "P9/" : "P8/") + b, "Anon", f.anonimo(), "anonimo", percorso);
                /* Per il 401 si verifica la forma JSON dell'errore, non un testo fissato dal contratto. */
                negazione(o, 401, null);
                assertTrue(o.risposta().header("Set-Cookie").orElse("").contains("Max-Age=0"), o.caso());
            }
        }
    }
}
