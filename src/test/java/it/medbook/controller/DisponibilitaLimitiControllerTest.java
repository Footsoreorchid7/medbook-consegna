package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.model.Disponibilita;
import it.medbook.model.Medico;
import it.medbook.model.Utente;
import it.medbook.repository.DisponibilitaRepository;
import it.medbook.service.DisponibilitaService;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Il controller limita la creazione a 1000 slot e legge al massimo 8193 byte prima del parsing
 * JSON; nessuna delega per corpi rifiutati.
 */
class DisponibilitaLimitiControllerTest {
    private static final String CONFIG = "{\"idPrestazione\":11,\"idStanza\":3}";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /* Servizio reale su repository contatore: la decisione sui limiti è quella applicativa. */
    private static final class Repository extends DisponibilitaRepository {
        int salvataggi;
        Repository() { super(null); }
        @Override public List<Disponibilita> saveAllAtomically(Integer idMedico, List<Disponibilita> slots) {
            salvataggi++;
            for (Disponibilita s : slots) { s.setMedico(medico(idMedico)); }
            return slots;
        }
    }
    private static final class Service extends DisponibilitaService {
        int configurazioni;
        Service() { super(null); }
        @Override public Disponibilita configuraDisponibilita(Integer id, Integer prestazione, Integer stanza) {
            configurazioni++;
            Disponibilita slot = new Disponibilita();
            slot.setIdDisponibilita(id); slot.setMedico(medico(7));
            slot.setDataOraInizio(LocalDateTime.of(2031, 1, 1, 9, 0)); slot.setDataOraFine(LocalDateTime.of(2031, 1, 1, 10, 0));
            return slot;
        }
    }
    static Medico medico(int id) {
        Utente utente = new Utente(); utente.setIdUtente(id);
        Medico medico = new Medico(); medico.setUtente(utente); return medico;
    }

    /* Corpo molto lungo o infinito generato progressivamente; conta i byte consumati davvero. */
    static final class Generato extends InputStream {
        final byte[] prefisso; final long lunghezza; final byte riempimento; long letti;
        Generato(String prefisso, long lunghezza, char riempimento) {
            this.prefisso = prefisso.getBytes(StandardCharsets.UTF_8); this.lunghezza = lunghezza; this.riempimento = (byte) riempimento;
        }
        @Override public int read() {
            if (letti >= lunghezza) { return -1; }
            int b = letti < prefisso.length ? prefisso[(int) letti] : riempimento; letti++; return b & 0xff;
        }
        @Override public int read(byte[] b, int off, int len) {
            if (len == 0) { return 0; }
            int n = 0;
            while (n < len) { int c = read(); if (c < 0) { break; } b[off + n++] = (byte) c; }
            return n == 0 ? -1 : n;
        }
    }

    TestHttpExchange put(DisponibilitaController controller, InputStream body) throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("PUT", "/api/disponibilita/5/configurazione", "");
        exchange.setStreams(body, null);
        controller.handle(exchange);
        return exchange;
    }
    static String pad(String json, int bytes) {
        String s = json.substring(0, json.length() - 1) + " ".repeat(bytes - json.length()) + "}";
        assertEquals(bytes, s.getBytes(StandardCharsets.UTF_8).length);
        return s;
    }
    static InputStream of(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }

    // ---- SEC-02 ----

    @Test
    void validBodyAndBodyExactlyAtTheLimitAreConfigured() throws IOException {
        Service service = new Service(); var controller = new DisponibilitaController(service, mapper);
        assertEquals(8192, DisponibilitaController.MAX_BODY);
        assertEquals(200, put(controller, of(CONFIG)).status);
        assertEquals(200, put(controller, of(pad(CONFIG, 8192))).status, "esattamente 8192 byte");
        assertEquals(2, service.configurazioni);
    }

    @Test
    void oversizedBodiesAreRefusedAfterReadingAtMostOneByteMoreThanTheLimit() throws IOException {
        Service service = new Service(); var controller = new DisponibilitaController(service, mapper);
        List<Generato> corpi = List.of(
                new Generato(pad(CONFIG, 8193), 8193, ' '),                                  // un byte oltre il limite
                new Generato("{\"idPrestazione\":11,\"idStanza\":3,\"x\":\"", 1_000_000L, 'a'), // campo sconosciuto enorme
                new Generato("{\"idPrestazione\":11,\"idStanza\":3", 1_000_000L, ' '),       // whitespace eccessivo
                new Generato("", Long.MAX_VALUE, ' '),                                         // flusso senza fine
                new Generato("[", Long.MAX_VALUE, '['));                                       // annidamento profondo
        for (Generato corpo : corpi) {
            TestHttpExchange exchange = put(controller, corpo);
            assertEquals(400, exchange.status);
            assertEquals("{\"error\":\"Richiesta non valida.\"}", exchange.body());
            assertTrue(corpo.letti <= 8193, "letti " + corpo.letti + " byte");
        }
        assertEquals(0, service.configurazioni, "nessuna delega al Service");
    }

    @Test
    void theDeclaredContentLengthIsNotTrusted() throws IOException {
        Service service = new Service(); var controller = new DisponibilitaController(service, mapper);
        for (String dichiarata : List.of("10", "8192", "0")) {
            Generato corpo = new Generato(pad(CONFIG, 8193), 1_000_000, ' ');
            TestHttpExchange exchange = new TestHttpExchange("PUT", "/api/disponibilita/5/configurazione", "");
            exchange.requestHeaders.set("Content-Length", dichiarata);
            exchange.setStreams(corpo, null);
            controller.handle(exchange);
            assertEquals(400, exchange.status); assertTrue(corpo.letti <= 8193);
        }
        assertEquals(0, service.configurazioni);
    }

    @Test
    void smallInvalidBodiesKeepTheirExistingAnswer() throws IOException {
        Service service = new Service(); var controller = new DisponibilitaController(service, mapper);
        for (String body : List.of("", "{\"idPrestazione\":11,\"idStanza\":3,\"x\":1}", "{bad", CONFIG + " {}")) {
            assertEquals(400, put(controller, of(body)).status, body);
        }
        assertEquals(0, service.configurazioni);
    }

    // ---- SEC-01 ----

    TestHttpExchange post(DisponibilitaController controller, String inizio, String fine) throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("POST", "/api/disponibilita",
                "{\"idMedico\":7,\"dataOraInizio\":\"" + inizio + "\",\"dataOraFine\":\"" + fine + "\"}");
        controller.handle(exchange);
        return exchange;
    }

    @Test
    void postAcceptsOneThousandSlotsAndRefusesOneThousandAndOneWithoutRepository() throws IOException {
        Repository repository = new Repository();
        var controller = new DisponibilitaController(new DisponibilitaService(repository,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-14T10:15:30.250Z"), java.time.ZoneOffset.UTC)), mapper);
        LocalDateTime inizio = LocalDateTime.of(2031, 3, 1, 8, 0);
        TestHttpExchange mille = post(controller, inizio.toString() + ":00", inizio.plusHours(1000) + ":00");
        assertEquals(201, mille.status);
        assertEquals(1000, mapper.readTree(mille.body()).size());
        assertEquals(1, repository.salvataggi);
        TestHttpExchange troppi = post(controller, inizio + ":00", inizio.plusHours(1001) + ":00");
        assertEquals(400, troppi.status);
        assertEquals("{\"error\":\"Intervallo troppo ampio: massimo 1000 slot per richiesta.\"}", troppi.body());
        for (String[] estremi : new String[][]{{"0999-12-31T23:00:00", "1000-01-01T00:00:00"},
                {"9999-12-31T23:00:00", "+10000-01-01T00:00:00"}, {"1000-01-01T00:00:00", "9999-12-31T23:00:00"}}) {
            TestHttpExchange e = post(controller, estremi[0], estremi[1]);
            assertEquals(400, e.status, String.join(" / ", estremi));
        }
        assertEquals(1, repository.salvataggi, "nessuna chiamata al repository sui rifiuti");
        for (String[] frazioni : new String[][]{{"9999-12-31T22:59:59.999999", "9999-12-31T23:59:59.999999"},
                {"2031-03-01T08:00:00.5", "2031-03-01T09:00:00.5"}, {"2031-03-01T08:00:00", "2031-03-01T09:00:00.000000001"}}) {
            TestHttpExchange e = post(controller, frazioni[0], frazioni[1]);
            assertEquals(400, e.status, String.join(" / ", frazioni));
            assertEquals("{\"error\":\"Date non rappresentabili da MySQL DATETIME.\"}", e.body());
        }
        assertEquals(1, repository.salvataggi, "frazioni di secondo rifiutate prima del repository");
    }
}
