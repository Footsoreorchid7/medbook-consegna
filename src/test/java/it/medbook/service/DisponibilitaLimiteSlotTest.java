package it.medbook.service;

import it.medbook.model.Disponibilita;
import it.medbook.repository.DisponibilitaRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/* Al massimo 1000 slot orari, conteggiati prima di allocarli. */
class DisponibilitaLimiteSlotTest {
    private static final LocalDateTime INIZIO = LocalDateTime.of(2031, 3, 1, 8, 0);
    /* Orologio fisso precedente all'inizio: indipendenza dall'ora reale. */
    private static final Clock ADESSO = Clock.fixed(Instant.parse("2026-10-14T10:15:30.250Z"), ZoneOffset.UTC);

    private static final class Repository extends DisponibilitaRepository {
        int chiamate;
        Repository() { super(null); }
        @Override public List<Disponibilita> saveAllAtomically(Integer idMedico, List<Disponibilita> slots) {
            chiamate++; return slots;
        }
    }

    private final Repository repository = new Repository();
    private final DisponibilitaService service = new DisponibilitaService(repository, ADESSO);

    @Test
    void exactlyTheMaximumIsMaterializedAsConsecutiveHourSlots() throws SQLException {
        assertEquals(1000, DisponibilitaService.MAX_SLOT_PER_RICHIESTA);
        List<Disponibilita> slots = service.creaDisponibilita(7, INIZIO, INIZIO.plusHours(1000));
        assertEquals(1, repository.chiamate);
        assertEquals(1000, slots.size());
        assertEquals(INIZIO, slots.getFirst().getDataOraInizio());
        assertEquals(INIZIO.plusHours(1000), slots.getLast().getDataOraFine());
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(INIZIO.plusHours(i), slots.get(i).getDataOraInizio());
            assertEquals(Duration.ofHours(1), Duration.between(slots.get(i).getDataOraInizio(), slots.get(i).getDataOraFine()));
        }
    }

    @Test
    void oneSlotMoreIsRefusedWithoutCallingTheRepository() {
        for (long ore : new long[]{1001, 1002, 24L * 365, 24L * 365 * 8999}) {
            var e = assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, INIZIO.withYear(1000), INIZIO.withYear(1000).plusHours(ore)));
            assertEquals("Intervallo troppo ampio: massimo 1000 slot per richiesta.", e.getMessage(), ore + " ore");
        }
        assertEquals(0, repository.chiamate);
    }

    @Test
    void theWholeDatetimeRangeIsRefusedQuickly() {
        /* Novemila anni di ore: rifiuto aritmetico senza iterazione o allocazione. */
        LocalDateTime minimo = LocalDateTime.of(1000, 1, 1, 0, 0), massimo = LocalDateTime.of(9999, 12, 31, 23, 0);
        long inizio = System.nanoTime();
        assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, minimo, massimo));
        assertTrue(System.nanoTime() - inizio < 1_000_000_000L, "rifiuto senza iterazioni proporzionali");
        assertEquals(0, repository.chiamate);
    }

    @Test
    void extremeOrUnrepresentableDatesAreRefusedBeforeAnyArithmetic() {
        List<LocalDateTime[]> casi = new ArrayList<>();
        casi.add(new LocalDateTime[]{LocalDateTime.MIN, LocalDateTime.MAX});
        casi.add(new LocalDateTime[]{LocalDateTime.MIN, LocalDateTime.MIN.plusHours(1)});
        casi.add(new LocalDateTime[]{LocalDateTime.MAX.minusHours(1).withNano(0), LocalDateTime.MAX.withNano(0)});
        casi.add(new LocalDateTime[]{LocalDateTime.of(999, 12, 31, 23, 0), LocalDateTime.of(1000, 1, 1, 0, 0)});
        casi.add(new LocalDateTime[]{LocalDateTime.of(9999, 12, 31, 23, 0), LocalDateTime.of(10000, 1, 1, 0, 0)});
        casi.add(new LocalDateTime[]{LocalDateTime.of(-5, 1, 1, 0, 0), LocalDateTime.of(-5, 1, 1, 1, 0)});
        for (LocalDateTime[] caso : casi) {
            var e = assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, caso[0], caso[1]));
            assertEquals("Date non rappresentabili da MySQL DATETIME.", e.getMessage(), caso[0] + " / " + caso[1]);
        }
        assertEquals(0, repository.chiamate);
    }

    @Test
    void fractionsOfASecondAreRefusedAndNeverTruncated() {
        List<LocalDateTime[]> casi = List.of(
                new LocalDateTime[]{INIZIO.plusNanos(500_000_000), INIZIO.plusHours(3).plusNanos(500_000_000)},   // frazioni identiche
                new LocalDateTime[]{INIZIO.plusNanos(1), INIZIO.plusHours(1).plusNanos(1)},                       // un nanosecondo
                new LocalDateTime[]{INIZIO.plusNanos(250_000_000), INIZIO.plusHours(1).plusNanos(750_000_000)},   // frazioni diverse
                new LocalDateTime[]{INIZIO.plusNanos(999_999_999), INIZIO.plusHours(1)},                          // solo l'inizio
                new LocalDateTime[]{INIZIO, INIZIO.plusHours(1).plusNanos(1_000)},                                // solo la fine
                new LocalDateTime[]{LocalDateTime.parse("9999-12-31T22:59:59.999999"), LocalDateTime.parse("9999-12-31T23:59:59.999999")},
                new LocalDateTime[]{INIZIO.plusNanos(1), INIZIO.plusHours(1001).plusNanos(1)});                   // anche oltre il limite
        for (LocalDateTime[] caso : casi) {
            var e = assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, caso[0], caso[1]));
            assertEquals("Date non rappresentabili da MySQL DATETIME.", e.getMessage(), caso[0] + " / " + caso[1]);
        }
        assertEquals(0, repository.chiamate, "nessun accesso al repository");
    }

    @Test
    void wholeSecondsKeepThePreviousBehaviour() throws SQLException {
        /* Secondi interi non nulli ammessi; sono rifiutate soltanto le frazioni. */
        List<Disponibilita> slots = service.creaDisponibilita(7, INIZIO.plusSeconds(30), INIZIO.plusHours(2).plusSeconds(30));
        assertEquals(2, slots.size());
        assertEquals(INIZIO.plusSeconds(30), slots.getFirst().getDataOraInizio());
        service.creaDisponibilita(7, LocalDateTime.parse("9999-12-31T22:59:59"), LocalDateTime.parse("9999-12-31T23:59:59"));
        assertEquals(1000, service.creaDisponibilita(7, INIZIO, INIZIO.plusHours(1000)).size());
        assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, INIZIO, INIZIO.plusHours(1001)));
        assertEquals(3, repository.chiamate);
    }

    @Test
    void theUpperDatetimeBoundaryStaysAcceptedAndTheLowerOneIsRefusedAsPast() throws SQLException {
        /* L'anno 1000 è rappresentabile in DATETIME ma passato rispetto all'orologio della creazione. */
        var e = assertThrows(IllegalArgumentException.class,
                () -> service.creaDisponibilita(7, LocalDateTime.of(1000, 1, 1, 0, 0), LocalDateTime.of(1000, 1, 1, 3, 0)));
        assertEquals("L'inizio della disponibilità deve essere futuro.", e.getMessage());
        assertEquals(0, repository.chiamate);
        service.creaDisponibilita(7, LocalDateTime.of(9999, 12, 31, 20, 0), LocalDateTime.of(9999, 12, 31, 23, 0));
        assertEquals(1, repository.chiamate);
    }

    @Test
    void existingTemporalRulesAreUnchangedAndPrecedeTheCount() {
        record Caso(LocalDateTime inizio, LocalDateTime fine, String messaggio) { }
        for (Caso caso : List.of(
                new Caso(null, INIZIO, "Data e ora di inizio e fine sono obbligatorie."),
                new Caso(INIZIO, INIZIO, "La fine deve essere successiva all'inizio."),
                new Caso(INIZIO, INIZIO.minusHours(2000), "La fine deve essere successiva all'inizio."),
                new Caso(INIZIO, INIZIO.plusMinutes(90), "L'intervallo deve essere un multiplo esatto di 60 minuti."),
                new Caso(INIZIO, INIZIO.plusHours(2000).plusMinutes(30), "L'intervallo deve essere un multiplo esatto di 60 minuti."),
                new Caso(INIZIO.plusSeconds(30), INIZIO.plusMinutes(90).plusSeconds(30), "L'intervallo deve essere un multiplo esatto di 60 minuti."))) {
            var e = assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(7, caso.inizio(), caso.fine()));
            assertEquals(caso.messaggio(), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(0, INIZIO, INIZIO.plusHours(5000)), "ID prima di tutto");
        assertEquals(0, repository.chiamate);
    }
}
