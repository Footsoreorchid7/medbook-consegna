package it.medbook.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PasswordHasher;
import it.medbook.service.BootstrapService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Fixture clinica costruita con bootstrap e API su catalogo esclusivo. I test leggono soltanto dati
 * e storage: la fixture condivisa non deve essere contaminata.
 */
@Tag("e2e-docker")
@ExtendWith(E2EAbilitazione.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixtureClinicaE2EIntegrationTest {
    private static final LocalDateTime T0 = LocalDateTime.ofInstant(E2EFixtureClinica.T0, ZoneOffset.UTC);
    private static final LocalDateTime T1 = LocalDateTime.ofInstant(E2EFixtureClinica.T1, ZoneOffset.UTC);
    private E2EAmbiente ambiente;
    private E2EFixtureClinica f;
    private E2EStato stato;
    private Map<String, String> statiT0;

    @BeforeAll void prepara() throws Exception {
        E2EConfigurazione.Completa cfg = E2EConfigurazione.perSetup(E2EConfigurazione.daAmbiente());
        if (cfg == null) { return; }
        ambiente = E2EAmbiente.avvia(cfg);
        stato = new E2EStato(ambiente);
        f = E2EFixtureClinica.preparaT0(ambiente, new E2EManifest());
        statiT0 = stati();
        f.passaAT1();
    }

    @AfterAll void chiudi() throws Exception {
        try { if (f != null) { f.close(); } }
        finally { if (ambiente != null) { ambiente.close(); } }
    }

    private Map<String, String> stati() throws Exception {
        Map<String, String> stati = new HashMap<>();
        for (var p : f.prenotazioni().entrySet()) {
            stati.put(p.getKey(), (String) stato.riga("SELECT stato FROM prenotazione WHERE id_prenotazione = ?", p.getValue()).get("stato"));
        }
        return stati;
    }

    private Integer idOrNull(String codice) { return codice == null ? null : f.attore(codice).id(); }



    @Test void firstAdministratorComesFromTheBootstrapServiceAndLogsInForReal() throws Exception {
        assertEquals(BootstrapService.CREATO, f.bootstrapEsito());
        E2EFixtureClinica.Attore ad = f.attore("AD");
        assertTrue(f.bootstrapOutput().stream().anyMatch(r -> r.contains(ambiente.catalogo().nome())), "destinazione mostrata");
        assertTrue(f.bootstrapOutput().stream().noneMatch(r -> r.contains(ad.password())), "password mai scritta");
        JsonNode me = ad.client().get("/api/auth/me").json();
        assertEquals("AMMINISTRATORE", me.get("ruoloGestionale").asText());
        assertFalse(me.get("paziente").booleanValue() || me.get("medico").booleanValue(), "AD senza profili");
        var riga = stato.riga("SELECT ruolo_gestionale, stato_accesso, modificato_da, modificato_il FROM utente WHERE id_utente = ?", ad.id());
        assertEquals("ATTIVO", riga.get("stato_accesso"));
        assertNull(riga.get("modificato_da"), "operazione di sistema, senza autore");
        assertEquals(T0, riga.get("modificato_il"), "istante del Clock di dominio");
        assertEquals(1, stato.conta("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale = 'AMMINISTRATORE'"));
        /* Il secondo bootstrap è rifiutato prima di leggere input: non crea un altro amministratore. */
        List<String> output = new ArrayList<>();
        int esito = new BootstrapService(new UtenteRepository(ambiente.catalogo().factory(), ambiente.server().domainClock()),
                new PasswordHasher()).esegui(new BootstrapService.Terminale() {
            @Override public String leggiRiga(String r) { throw new AssertionError("input letto"); }
            @Override public char[] leggiPassword(String r) { throw new AssertionError("password letta"); }
            @Override public void scrivi(String m) { output.add(m); }
        });
        assertEquals(BootstrapService.GIA_INIZIALIZZATO, esito);
    }

    @Test void actorsAreDistinctIdentitiesCreatedThroughTheApi() throws Exception {
        Map<String, E2EFixtureClinica.Attore> attori = f.attori();
        assertEquals(Set.copyOf(E2EFixtureClinica.ATTORI), attori.keySet());
        assertEquals(attori.size(), attori.values().stream().map(E2EFixtureClinica.Attore::id).distinct().count(), "id distinti");
        assertEquals(attori.size(), attori.values().stream().map(E2EFixtureClinica.Attore::email).distinct().count(), "email distinte");
        assertEquals(attori.size(), attori.values().stream().map(E2EFixtureClinica.Attore::password).distinct().count(), "credenziali distinte");
        assertEquals(attori.size(), attori.values().stream().map(a -> a.client().credenziali().orElseThrow().cookie()).distinct().count(),
                "una sessione per attore");
        /* Profili e ruolo come letti dall'applicazione, senza deduzioni aggiuntive. */
        Map<String, List<Object>> attesi = Map.of(
                "AD", List.of(false, false, "AMMINISTRATORE"), "DK", List.of(false, false, "OPERATORE_DESK"),
                "P1", List.of(true, false, "null"), "P2", List.of(true, false, "null"),
                "MT", List.of(false, true, "null"), "MS", List.of(false, true, "null"), "MX", List.of(false, true, "null"),
                "PM", List.of(true, true, "null"), "MI", List.of(true, true, "null"));
        for (var a : attori.values()) {
            JsonNode me = a.client().get("/api/auth/me").json();
            assertEquals(a.id(), me.get("idUtente").intValue(), a.codice());
            assertEquals(attesi.get(a.codice()), List.of(me.get("paziente").booleanValue(), me.get("medico").booleanValue(),
                    me.get("ruoloGestionale").asText()), a.codice());
        }
        /* Creato tramite API amministrative con autore tracciato, non con INSERT manuale. */
        for (String c : List.of("DK", "MT", "MS", "MX")) {
            assertEquals((long) f.attore("AD").id(), ((Number) stato.riga("SELECT modificato_da FROM utente WHERE id_utente = ?",
                    f.attore(c).id()).get("modificato_da")).longValue(), c);
        }
        assertEquals(401, f.anonimo().get("/api/auth/me").status(), "attore anonimo senza sessione");
        assertTrue(f.anonimo().credenziali().isEmpty());
    }

    @Test void dualProfilesAreOneAccountWithBothProfiles() throws Exception {
        for (String c : List.of("PM", "MI")) {
            E2EFixtureClinica.Attore a = f.attore(c);
            assertEquals(1, stato.conta("SELECT COUNT(*) FROM utente WHERE email = ?", a.email()), c + ": un solo account");
            assertEquals(1, stato.conta("SELECT COUNT(*) FROM paziente WHERE id_utente = ?", a.id()), c + ": profilo paziente");
            assertEquals("ATTIVO", stato.riga("SELECT stato FROM medico WHERE id_utente = ?", a.id()).get("stato"), c + ": profilo medico");
            assertNull(stato.riga("SELECT ruolo_gestionale FROM utente WHERE id_utente = ?", a.id()).get("ruolo_gestionale"));
            /* La stessa sessione dispone di entrambe le capacità. */
            assertEquals(200, a.client().get("/api/prenotazioni/paziente?idPaziente=" + a.id()).status(), c);
            assertEquals(200, a.client().get("/api/prenotazioni/agenda?medicoId=" + a.id() + "&data=2031-01-20").status(), c);
        }
        for (String c : List.of("P1", "P2")) { assertEquals(0, stato.conta("SELECT COUNT(*) FROM medico WHERE id_utente = ?", f.attore(c).id()), c); }
        for (String c : List.of("MT", "MS", "MX", "AD", "DK")) {
            assertEquals(0, stato.conta("SELECT COUNT(*) FROM paziente WHERE id_utente = ?", f.attore(c).id()), c);
        }
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM medico m JOIN utente u ON u.id_utente = m.id_utente WHERE u.ruolo_gestionale IS NOT NULL"),
                "nessuna combinazione medico-gestionale creata (solo F-LG, in un catalogo dedicato)");
    }

    // ---------------------------------------------------------------- catalog, slots, bookings

    @Test void servicesRoomsAndEnablementsFollowThePlan() throws Exception {
        var q1 = stato.riga("SELECT durata, prezzo FROM prestazione WHERE id_prestazione = ?", f.prestazione("Q1"));
        assertEquals(30, ((Number) q1.get("durata")).intValue());
        assertEquals(0, E2EFixtureClinica.PREZZO_Q1.compareTo((BigDecimal) q1.get("prezzo")));
        assertEquals(2, stato.conta("SELECT COUNT(*) FROM prestazione"));
        assertEquals(4, stato.conta("SELECT COUNT(*) FROM stanza"));
        Set<String> abilitazioni = new TreeSet<>();
        for (var r : stato.righe("SELECT id_medico, id_prestazione FROM medico_prestazione")) {
            abilitazioni.add(r.get("id_medico") + "/" + r.get("id_prestazione"));
        }
        assertEquals(E2EFixtureClinica.ABILITAZIONI.entrySet().stream()
                .map(e -> f.attore(e.getKey()).id() + "/" + f.prestazione(e.getValue())).collect(Collectors.toCollection(TreeSet::new)), abilitazioni);
    }

    @Test void slotsMatchTheTimetableWithoutOverlaps() throws Exception {
        assertEquals(E2EFixtureClinica.SLOT.size(), stato.conta("SELECT COUNT(*) FROM disponibilita"));
        for (E2EFixtureClinica.DefSlot s : E2EFixtureClinica.SLOT) {
            var r = stato.riga("SELECT id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza FROM disponibilita WHERE id_disponibilita = ?", f.slot(s.codice()));
            assertEquals((long) f.attore(s.titolare()).id(), ((Number) r.get("id_medico")).longValue(), s.codice());
            assertEquals(s.inizio(), r.get("data_ora_inizio"), s.codice());
            assertEquals(s.inizio().plusHours(1), r.get("data_ora_fine"), s.codice());
            if (s.stanza() == null) {
                assertNull(r.get("id_prestazione"), s.codice()); assertNull(r.get("id_stanza"), s.codice());
            } else {
                assertEquals((long) f.prestazione("Q1"), ((Number) r.get("id_prestazione")).longValue(), s.codice());
                assertEquals((long) f.stanza(s.stanza()), ((Number) r.get("id_stanza")).longValue(), s.codice());
            }
        }
        // Nessuna sovrapposizione per medico o stanza.
        for (var a : E2EFixtureClinica.SLOT) {
            for (var b : E2EFixtureClinica.SLOT) {
                if (a == b || !a.inizio().equals(b.inizio())) { continue; }
                assertNotEquals(a.titolare(), b.titolare(), a.codice() + "/" + b.codice());
                if (a.stanza() != null) { assertNotEquals(a.stanza(), b.stanza(), a.codice() + "/" + b.codice()); }
            }
        }
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM disponibilita d WHERE d.id_medico = ?", f.attore("MS").id()), "MS senza slot propri");
    }

    @Test void bookingsHaveTheExpectedPatientDoctorAndStateAtT0AndT1() throws Exception {
        assertEquals(E2EFixtureClinica.PRENOTAZIONI.size(), stato.conta("SELECT COUNT(*) FROM prenotazione"));
        Map<String, String> statiT1 = stati();
        for (E2EFixtureClinica.DefPrenotazione p : E2EFixtureClinica.PRENOTAZIONI) {
            assertEquals(p.statoT0(), statiT0.get(p.codice()), p.codice() + " a T0");
            assertEquals(p.statoT1(), statiT1.get(p.codice()), p.codice() + " a T1");
            var r = stato.riga("""
                    SELECT pr.id_paziente, pr.id_disponibilita, pr.id_medico_effettivo, pr.indisponibilita_segnalata, pr.gestita_da,
                           pr.data_ora_gestione, pr.data_ora_richiesta, d.id_medico AS titolare
                    FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita WHERE pr.id_prenotazione = ?""",
                    f.prenotazione(p.codice()));
            assertEquals((long) f.attore(p.paziente()).id(), ((Number) r.get("id_paziente")).longValue(), p.codice());
            assertEquals((long) f.slot(p.slot()), ((Number) r.get("id_disponibilita")).longValue(), p.codice());
            assertEquals((long) f.attore(E2EFixtureClinica.definizioneSlot(p.slot()).titolare()).id(), ((Number) r.get("titolare")).longValue());
            Integer effettivo = r.get("id_medico_effettivo") == null ? null : ((Number) r.get("id_medico_effettivo")).intValue();
            assertEquals(idOrNull(p.effettivoT1()), effettivo, p.codice() + ": medico effettivo");
            assertFalse((Boolean) r.get("indisponibilita_segnalata"), p.codice() + ": nessuna segnalazione pendente");
            assertEquals(T0, r.get("data_ora_richiesta"), p.codice());
            if (p.statoT0().equals("RICHIESTA")) {
                assertNull(r.get("gestita_da"), p.codice()); assertNull(r.get("data_ora_gestione"), p.codice());
            } else {
                assertEquals((long) f.attore("DK").id(), ((Number) r.get("gestita_da")).longValue(), p.codice());
                assertEquals(p.codice().equals("B-EC") ? T1 : T0, r.get("data_ora_gestione"), p.codice());
            }
        }
        /* L'elenco personale contiene esattamente le prenotazioni del proprietario. */
        for (String paziente : List.of("P1", "P2", "PM")) {
            JsonNode lista = f.attore(paziente).client().get("/api/prenotazioni/paziente?idPaziente=" + f.attore(paziente).id()
                    + "&dataDa=2031-01-20&dataA=2031-02-06").json();
            Set<Integer> visti = new HashSet<>();
            lista.forEach(n -> visti.add(n.get("idPrenotazione").intValue()));
            assertEquals(E2EFixtureClinica.PRENOTAZIONI.stream().filter(p -> p.paziente().equals(paziente))
                    .map(p -> f.prenotazione(p.codice())).collect(Collectors.toSet()), visti, paziente);
        }
    }

    @Test void substitutionsMovedTheBookingsToTheSubstitute() throws Exception {
        Set<Integer> agendaMs = agenda("MS", "2031-01-20"), agendaMt = agenda("MT", "2031-01-20");
        Set<Integer> sostituite = E2EFixtureClinica.PRENOTAZIONI.stream().filter(E2EFixtureClinica.DefPrenotazione::sostituita)
                .map(p -> f.prenotazione(p.codice())).collect(Collectors.toSet());
        assertEquals(Set.of(f.prenotazione("B-R1"), f.prenotazione("B-C1"), f.prenotazione("B-E1")), sostituite);
        assertEquals(sostituite, agendaMs, "agenda del sostituto");
        assertEquals(Set.of(f.prenotazione("B-R2"), f.prenotazione("B-EC")), agendaMt, "il titolare sostituito non vede più le sostituite");
        assertEquals(Set.of(f.prenotazione("B-PM")), agenda("PM", "2031-01-20"));
        assertEquals(Set.of(f.prenotazione("B-IN")), agenda("MI", "2031-01-20"));
        assertEquals(Set.of(), agenda("MX", "2031-01-20"), "medico estraneo");
        assertEquals("[]", f.attore("DK").client().get("/api/prenotazioni/segnalazioni-indisponibilita").testo(), "nessuna segnalazione pendente");
    }

    private Set<Integer> agenda(String medico, String data) throws Exception {
        E2EClient.Risposta r = f.attore(medico).client().get("/api/prenotazioni/agenda?medicoId=" + f.attore(medico).id() + "&data=" + data);
        assertEquals(200, r.status(), medico + ": " + r.testo());
        Set<Integer> ids = new HashSet<>();
        r.json().forEach(n -> ids.add(n.get("idPrenotazione").intValue()));
        return ids;
    }

    // ---------------------------------------------------------------- reports, storage, economics

    @Test void reportsAreStoredOnceWithTheExpectedBytesAndOwners() throws Exception {
        assertEquals(3, stato.conta("SELECT COUNT(*) FROM referto"));
        Map<String, String> file = stato.file();
        Set<String> hashDocumenti = file.entrySet().stream().filter(e -> e.getKey().startsWith("documents"))
                .map(Map.Entry::getValue).collect(Collectors.toSet());
        assertEquals(3, hashDocumenti.size(), file.keySet().toString());
        assertTrue(file.keySet().stream().allMatch(k -> k.startsWith("documents")), "staging e scratch vuoti: " + file.keySet());
        for (E2EFixtureClinica.DefPrenotazione p : E2EFixtureClinica.PRENOTAZIONI) {
            List<Map<String, Object>> righe = stato.righe("SELECT id_referto, id_medico_caricamento AS id_medico, caricato_il FROM referto WHERE id_prenotazione = ?",
                    f.prenotazione(p.codice()));
            if (p.referto() == null) { assertTrue(righe.isEmpty(), p.codice() + " senza referto"); continue; }
            var r = righe.getFirst();
            assertEquals((long) f.referto(p.codice()), ((Number) r.get("id_referto")).longValue());
            assertEquals((long) f.attore(p.visitaDa()).id(), ((Number) r.get("id_medico")).longValue(), p.codice() + ": autore");
            assertEquals(T1, r.get("caricato_il"), p.codice());
            byte[] atteso = f.documento(p.referto());
            assertTrue(hashDocumenti.contains(E2EStato.sha256(atteso)), p.codice() + ": file nello storage");
            E2EClient owner = f.attore(p.paziente()).client();
            assertEquals(200, owner.get("/api/prenotazioni/" + f.prenotazione(p.codice()) + "/referto").status(), p.codice());
            E2EClient.Risposta doc = owner.get("/api/prenotazioni/" + f.prenotazione(p.codice()) + "/referto/documento");
            assertEquals(200, doc.status(), p.codice());
            assertArrayEquals(atteso, doc.body(), p.codice() + ": byte del proprio referto");
        }
        assertNotEquals(E2EStato.sha256(f.documento("A")), E2EStato.sha256(f.documento("B")), "documenti distinguibili");
    }

    @Test void economicFixtureHasPaidOnlineNonDeliveredAndARequestedRefund() throws Exception {
        int ec = f.prenotazione("B-EC");
        var pagamento = stato.riga("SELECT id_pagamento, id_prenotazione, modalita, stato, importo, pagato_il FROM pagamento");
        assertEquals((long) f.pagamentoEC(), ((Number) pagamento.get("id_pagamento")).longValue());
        assertEquals((long) ec, ((Number) pagamento.get("id_prenotazione")).longValue());
        assertEquals(List.of("ONLINE", "PAGATO"), List.of(pagamento.get("modalita"), pagamento.get("stato")));
        assertEquals(0, E2EFixtureClinica.PREZZO_Q1.compareTo((BigDecimal) pagamento.get("importo")));
        assertEquals(T0, pagamento.get("pagato_il"), "pagamento online a T0");
        var rimborso = stato.riga("SELECT id_rimborso, id_pagamento, stato, importo, richiesto_il, verificato_da FROM rimborso");
        assertEquals((long) f.rimborsoEC(), ((Number) rimborso.get("id_rimborso")).longValue());
        assertEquals("RICHIESTO", rimborso.get("stato"), "decisione lasciata ai test (approva/respingi)");
        assertEquals(T1, rimborso.get("richiesto_il"));
        assertNull(rimborso.get("verificato_da"));
        JsonNode propri = f.attore("P2").client().get("/api/rimborsi/paziente").json();
        assertEquals(1, propri.size()); assertEquals((int) f.rimborsoEC(), propri.get(0).get("idRimborso").intValue());
        JsonNode desk = f.attore("DK").client().get("/api/rimborsi/richieste").json();
        assertEquals(1, desk.size()); assertEquals(ec, desk.get(0).get("idPrenotazione").intValue());
        assertEquals("[]", f.attore("P1").client().get("/api/rimborsi/paziente").testo(), "nessun rimborso per P1");
    }

    @Test void inactivationFixtureIsReachable() throws Exception {
        int mi = f.attore("MI").id();
        assertEquals("ATTIVO", stato.riga("SELECT stato FROM medico WHERE id_utente = ?", mi).get("stato"));
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita = ?", f.slot("s12")), "s12 libero");
        assertEquals(0, stato.conta("""
                SELECT COUNT(*) FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                WHERE (pr.stato = 'RICHIESTA' AND d.id_medico = ?) OR (pr.stato = 'CONFERMATA' AND pr.id_medico_effettivo = ?)""", mi, mi),
                "nessuna prenotazione bloccante: l'inattivazione via API è raggiungibile");
        assertEquals(0, stato.conta("SELECT COUNT(*) FROM referto WHERE id_prenotazione = ?", f.prenotazione("B-IN")));
    }

    // ---------------------------------------------------------------- clocks, sessions, contamination

    @Test void domainClockIsAtT1AndTheAuthClockIsSeparate() throws Exception {
        assertEquals(E2EFixtureClinica.T1, ambiente.server().domainClock().instant());
        assertEquals(E2EServer.AUTH_T0.plus(E2EAccessi.FINESTRA.plusSeconds(1).multipliedBy(f.accessi().avanzamenti())),
                ambiente.server().authClock().instant(), "il Clock di autenticazione avanza solo per il limite dei login");
        for (E2EFixtureClinica.DefSlot s : E2EFixtureClinica.SLOT) {
            boolean futuro = s.inizio().isAfter(T1);
            assertEquals(!s.inizio().toLocalDate().equals(T1.toLocalDate()), futuro, s.codice());
            if (!futuro) { assertFalse(s.inizio().plusHours(1).isAfter(T1), s.codice() + " terminato a T1"); }
        }
        for (var a : f.attori().values()) { assertEquals(200, a.client().get("/api/auth/me").status(), a.codice() + ": sessione valida a T1"); }
    }

    @Test void catalogContainsOnlyFixtureRows() throws Exception {
        Set<String> email = new HashSet<>();
        stato.righe("SELECT email FROM utente").forEach(r -> email.add((String) r.get("email")));
        assertEquals(f.manifest().email(), email, "solo identità del manifest, tutte create");
        Map<String, Long> attese = Map.of("utente", 9L, "medico", 5L, "paziente", 4L, "prestazione", 2L, "stanza", 4L,
                "disponibilita", 13L, "prenotazione", 10L, "referto", 3L, "pagamento", 1L, "rimborso", 1L);
        for (var t : attese.entrySet()) { assertEquals(t.getValue(), stato.conta("SELECT COUNT(*) FROM " + t.getKey()), t.getKey()); }
        assertTrue(E2ELauncher.CATALOGO.matcher(ambiente.catalogo().nome()).matches());
    }
}
