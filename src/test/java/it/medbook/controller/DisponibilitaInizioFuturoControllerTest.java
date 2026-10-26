package it.medbook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.model.Disponibilita;
import it.medbook.repository.DisponibilitaRepository;
import it.medbook.service.DisponibilitaService;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*
 * La creazione richiede inizio strettamente futuro secondo l'orologio del servizio, senza
 * troncamento. Intervalli multigiornalieri entro il limite slot; orologi fissi indipendenti da ora
 * reale o mezzanotte.
 */
class DisponibilitaInizioFuturoControllerTest {
    private static final String NON_FUTURO = "{\"error\":\"L'inizio della disponibilità deve essere futuro.\"}";
    private static final String FRAZIONI = "{\"error\":\"Date non rappresentabili da MySQL DATETIME.\"}";
    /* L'istante fisso ha 250 ms trascorsi nel secondo corrente. */
    private static final Clock ADESSO_CON_MILLIS = Clock.fixed(Instant.parse("2026-10-14T10:15:30.250Z"), ZoneOffset.UTC);
    private static final Clock ADESSO_SECONDO_INTERO = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final class Repository extends DisponibilitaRepository {
        int salvataggi;
        Repository() { super(null); }
        @Override public List<Disponibilita> saveAllAtomically(Integer idMedico, List<Disponibilita> slots) {
            salvataggi++;
            for (Disponibilita s : slots) { s.setMedico(DisponibilitaLimitiControllerTest.medico(idMedico)); }
            return slots;
        }
    }

    private final Repository repository = new Repository();

    private TestHttpExchange post(Clock clock, String inizio, String fine) throws IOException {
        var controller = new DisponibilitaController(new DisponibilitaService(repository, clock), mapper);
        TestHttpExchange exchange = new TestHttpExchange("POST", "/api/disponibilita",
                "{\"idMedico\":7,\"dataOraInizio\":\"" + inizio + "\",\"dataOraFine\":\"" + fine + "\"}");
        controller.handle(exchange);
        return exchange;
    }

    private void assertRifiutato(TestHttpExchange exchange, String errore) {
        assertEquals(400, exchange.status);
        assertEquals(errore, exchange.body());
        assertEquals(0, repository.salvataggi, "nessuna scrittura");
    }

    @Test
    void pastStartIsRefusedWithoutWriting() throws IOException {
        assertRifiutato(post(ADESSO_CON_MILLIS, "2026-10-14T09:00:00", "2026-10-14T11:00:00"), NON_FUTURO);
        assertRifiutato(post(ADESSO_CON_MILLIS, "2026-10-13T10:15:31", "2026-10-13T11:15:31"), NON_FUTURO);
    }

    @Test
    void startEqualToTheCurrentInstantIsRefused() throws IOException {
        assertRifiutato(post(ADESSO_SECONDO_INTERO, "2026-10-14T10:00:00", "2026-10-14T11:00:00"), NON_FUTURO);
    }

    @Test
    void startInTheCurrentSecondButBeforeItsMillisecondsIsRefused() throws IOException {
        /* Troncare l'orologio al secondo renderebbe l'inizio uguale ad adesso; in realtà è già passato e va rifiutato. */
        assertRifiutato(post(ADESSO_CON_MILLIS, "2026-10-14T10:15:30", "2026-10-14T11:15:30"), NON_FUTURO);
    }

    @Test
    void futureStartIsAccepted() throws IOException {
        TestHttpExchange primoSecondoUtile = post(ADESSO_CON_MILLIS, "2026-10-14T10:15:31", "2026-10-14T11:15:31");
        assertEquals(201, primoSecondoUtile.status, primoSecondoUtile.body());
        TestHttpExchange dopoUnSecondoIntero = post(ADESSO_SECONDO_INTERO, "2026-10-14T10:00:01", "2026-10-14T11:00:01");
        assertEquals(201, dopoUnSecondoIntero.status, dopoUnSecondoIntero.body());
        assertEquals(2, repository.salvataggi);
    }

    @Test
    void theCurrentInstantIsReadInTheClockZone() throws IOException {
        // 08:00Z corrisponde alle 10:00 di Roma in ora estiva: 09:30 è passato nella zona anche se futuro in UTC.
        Clock roma = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneId.of("Europe/Rome"));
        assertRifiutato(post(roma, "2026-10-14T09:30:00", "2026-10-14T10:30:00"), NON_FUTURO);
        assertRifiutato(post(roma, "2026-10-14T10:00:00", "2026-10-14T11:00:00"), NON_FUTURO);
        assertEquals(201, post(roma, "2026-10-14T10:00:01", "2026-10-14T11:00:01").status);
    }

    @Test
    void intervalAcrossMidnightIsAccepted() throws IOException {
        TestHttpExchange exchange = post(ADESSO_CON_MILLIS, "2026-10-14T23:00:00", "2026-10-15T02:00:00");
        assertEquals(201, exchange.status, exchange.body());
        JsonNode slots = mapper.readTree(exchange.body());
        assertEquals(3, slots.size());
        assertEquals("2026-10-14T23:00:00", slots.get(0).get("dataOraInizio").asText());
        assertEquals("2026-10-15T00:00:00", slots.get(1).get("dataOraInizio").asText());
        assertEquals("2026-10-15T02:00:00", slots.get(2).get("dataOraFine").asText());
    }

    @Test
    void intervalOverSeveralDaysIsAccepted() throws IOException {
        TestHttpExchange exchange = post(ADESSO_CON_MILLIS, "2026-10-15T08:00:00", "2026-10-18T08:00:00");
        assertEquals(201, exchange.status, exchange.body());
        JsonNode slots = mapper.readTree(exchange.body());
        assertEquals(72, slots.size());
        assertEquals("2026-10-15T08:00:00", slots.get(0).get("dataOraInizio").asText());
        assertEquals("2026-10-18T08:00:00", slots.get(71).get("dataOraFine").asText());
        assertEquals(1, repository.salvataggi, "una sola scrittura atomica");
    }

    @Test
    void exactlyOneThousandFutureSlotsAreAcceptedAndOneThousandAndOneRefused() throws IOException {
        /*
         * Aritmetica LocalDateTime: 1000 ore dal 15 ottobre terminano il 25 novembre alle 16, senza
         * conversioni di fuso.
         */
        TestHttpExchange mille = post(ADESSO_CON_MILLIS, "2026-10-15T00:00:00", "2026-11-25T16:00:00");
        assertEquals(201, mille.status, mille.body());
        assertEquals(1000, mapper.readTree(mille.body()).size());
        assertEquals(1, repository.salvataggi);
        TestHttpExchange troppi = post(ADESSO_CON_MILLIS, "2026-10-15T00:00:00", "2026-11-25T17:00:00");
        assertEquals(400, troppi.status);
        assertEquals("{\"error\":\"Intervallo troppo ampio: massimo 1000 slot per richiesta.\"}", troppi.body());
        assertEquals(1, repository.salvataggi, "nessuna scrittura oltre il limite");
    }

    @Test
    void fractionsOfASecondAreAlwaysRefused() throws IOException {
        for (String[] estremi : new String[][]{
                {"2026-10-15T08:00:00.5", "2026-10-15T09:00:00.5"},        // futuro
                {"2026-10-14T10:15:30.250", "2026-10-14T11:15:30.250"},    // uguale all'istante corrente
                {"2026-10-14T10:15:30.999", "2026-10-14T11:15:30.999"},    // futuro nello stesso secondo
                {"2026-10-13T08:00:00.1", "2026-10-13T09:00:00.1"},        // passato
                {"2026-10-15T08:00:00", "2026-10-15T09:00:00.000000001"}}) {
            assertRifiutato(post(ADESSO_CON_MILLIS, estremi[0], estremi[1]), FRAZIONI);
        }
    }
}
