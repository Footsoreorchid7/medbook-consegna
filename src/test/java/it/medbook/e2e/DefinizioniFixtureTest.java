package it.medbook.e2e;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/* Coerenza delle tabelle sintetiche della fixture, senza server o database. */
class DefinizioniFixtureTest {
    private static final LocalDateTime T0 = LocalDateTime.ofInstant(E2EFixtureClinica.T0, ZoneOffset.UTC);
    private static final LocalDateTime T1 = LocalDateTime.ofInstant(E2EFixtureClinica.T1, ZoneOffset.UTC);

    @Test void chronologyIsForwardAndMatchesThePlan() {
        assertEquals(LocalDateTime.parse("2031-01-10T08:00"), T0);
        assertEquals(LocalDateTime.parse("2031-01-20T13:30"), T1);
        assertTrue(T1.isAfter(T0));
        for (var s : E2EFixtureClinica.SLOT) {
            assertTrue(s.inizio().isAfter(T0), s.codice() + ": creabile a T0 (inizio futuro)");
            boolean del20 = s.inizio().toLocalDate().equals(T1.toLocalDate());
            assertEquals(del20, !s.inizio().plusHours(1).isAfter(T1), s.codice() + ": terminato a T1 solo se del 20/01");
        }
    }

    @Test void slotsHaveNoOverlapsAndOnlyThePlannedUnconfiguredOnes() {
        Set<String> medicoOra = new HashSet<>(), stanzaOra = new HashSet<>(), codici = new HashSet<>();
        for (var s : E2EFixtureClinica.SLOT) {
            assertTrue(codici.add(s.codice()), s.codice());
            assertTrue(medicoOra.add(s.titolare() + s.inizio()), "sovrapposizione per medico: " + s.codice());
            if (s.stanza() != null) { assertTrue(stanzaOra.add(s.stanza() + s.inizio()), "sovrapposizione per stanza: " + s.codice()); }
            assertTrue(Set.of("MT", "PM", "MI").contains(s.titolare()), "MS e MX senza slot propri");
        }
        assertEquals(Set.of("s9", "s10", "s12"), E2EFixtureClinica.SLOT.stream().filter(s -> s.stanza() == null)
                .map(E2EFixtureClinica.DefSlot::codice).collect(java.util.stream.Collectors.toSet()));
        assertFalse(codici.contains("s11"), "s11 non è definito dal piano");
    }

    @Test void bookingsAreIndependentAndConsistent() {
        Set<String> slotPrenotati = new HashSet<>();
        Set<String> pazienti = Set.of("P1", "P2", "PM");
        for (var p : E2EFixtureClinica.PRENOTAZIONI) {
            assertTrue(slotPrenotati.add(p.slot()), p.codice() + ": uno slot per prenotazione");
            var slot = E2EFixtureClinica.definizioneSlot(p.slot());
            assertNotNull(slot.stanza(), p.codice() + ": slot configurato");
            assertTrue(pazienti.contains(p.paziente()) || (p.codice().equals("B-IN") && p.paziente().equals("P1")), p.codice());
            if (p.sostituita()) { assertEquals("MT", slot.titolare()); assertEquals("MS", p.effettivoT1()); }
            else if (!p.statoT0().equals("RICHIESTA")) { assertEquals(slot.titolare(), p.effettivoT1(), p.codice()); }
            if (p.statoT0().equals("RICHIESTA")) { assertNull(p.effettivoT1()); assertEquals("RICHIESTA", p.statoT1()); }
            if (p.visitaDa() != null) { assertEquals(p.effettivoT1(), p.visitaDa(), p.codice() + ": visita dal medico effettivo"); }
            if (p.referto() != null) { assertNotNull(p.visitaDa(), p.codice()); assertTrue(E2EFixtureClinica.DOCUMENTI.contains(p.referto())); }
            boolean futura = E2EFixtureClinica.definizioneSlot(p.slot()).inizio().isAfter(T1);
            if (futura) { assertNull(p.visitaDa(), p.codice() + ": nessuna visita su slot futuro"); }
        }
        /* Le prenotazioni di un paziente non si sovrappongono fra loro. */
        Set<String> pazienteOra = new HashSet<>();
        for (var p : E2EFixtureClinica.PRENOTAZIONI) {
            assertTrue(pazienteOra.add(p.paziente() + E2EFixtureClinica.definizioneSlot(p.slot()).inizio()), p.codice());
        }
        assertEquals(List.of("B-R1", "B-C1", "B-E1"), E2EFixtureClinica.PRENOTAZIONI.stream()
                .filter(E2EFixtureClinica.DefPrenotazione::sostituita).map(E2EFixtureClinica.DefPrenotazione::codice).toList());
        /* Il sostituto non ha impegni sovrapposti alle prenotazioni sostituite. */
        Set<LocalDateTime> orari = new HashSet<>();
        for (var p : E2EFixtureClinica.PRENOTAZIONI) {
            if ("MS".equals(p.effettivoT1())) { assertTrue(orari.add(E2EFixtureClinica.definizioneSlot(p.slot()).inizio()), p.codice()); }
        }
    }

    @Test void identifiersAndDocumentsAreSynthetic() {
        Pattern ruolo = Pattern.compile("[a-z]{1,12}");
        E2EFixtureClinica.RUOLO_EMAIL.values().forEach(r -> assertTrue(ruolo.matcher(r).matches(), r));
        assertEquals(Set.copyOf(E2EFixtureClinica.ATTORI), E2EFixtureClinica.RUOLO_EMAIL.keySet());
        E2EManifest m = new E2EManifest();
        String a = m.nuovoIdentificativo("ordine"), b = m.nuovoIdentificativo("ordine");
        assertNotEquals(a, b);
        assertEquals(Set.of(a, b), m.identificativi());
        assertThrows(IllegalArgumentException.class, () -> m.nuovoIdentificativo("con spazio"));
        assertTrue(m.nuovaEmail("doppio").endsWith("@example.invalid"));
        Set<String> hash = new HashSet<>();
        for (String d : E2EFixtureClinica.DOCUMENTI) { assertTrue(hash.add(E2EStato.sha256(FixturePdfValidator.documento("SEC-CLIN-01-" + d))), d); }
    }
}
