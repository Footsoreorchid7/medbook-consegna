package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import it.medbook.controller.DisponibilitaController;
import it.medbook.model.Disponibilita;
import it.medbook.service.DisponibilitaService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Server HTTP e MySQL reali isolati: 1000 slot ammessi, 1001 senza scritture; corpo configurazione
 * limitato a 8192 byte anche con trasferimento a blocchi, senza effetti per richieste rifiutate.
 */
class DisponibilitaLimitiIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final LocalDateTime INIZIO = LocalDateTime.of(2031, 6, 2, 0, 0);
    /* Orologio fisso: la regola dell'inizio futuro non dipende dall'ora reale. */
    private static final Clock ADESSO = Clock.fixed(Instant.parse("2026-10-14T10:15:30.250Z"), ZoneOffset.UTC);

    static long slotDelMedico(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, int medico) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM disponibilita WHERE id_medico = ?")) {
            s.setInt(1, medico);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
        }
    }
    static List<Object> configurazione(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, int slot) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT id_prestazione, id_stanza FROM disponibilita WHERE id_disponibilita = ?")) {
            s.setInt(1, slot);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); var v = new ArrayList<Object>(); for (int i = 1; i <= 2; i++) { Object o = r.getObject(i); v.add(o == null ? null : ((Number) o).longValue()); } return v; }
        }
    }
    static void pulisci(PrenotazioneIndisponibilitaIntegrationTest.Fixture f) throws SQLException {
        f.execute("DELETE FROM disponibilita WHERE id_medico IN (?, ?)", f.owner, f.effective);
    }

    @Test
    void oneThousandSlotsAreWrittenAndOneThousandAndOneWriteNothing() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            try {
                var service = new DisponibilitaService(new DisponibilitaRepository(f.factory, ADESSO), ADESSO);
                List<Disponibilita> creati = service.creaDisponibilita(f.owner, INIZIO, INIZIO.plusHours(1000));
                assertEquals(1000, creati.size());
                assertEquals(1000, slotDelMedico(f, f.owner));
                var e = assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(f.effective, INIZIO, INIZIO.plusHours(1001)));
                assertEquals("Intervallo troppo ampio: massimo 1000 slot per richiesta.", e.getMessage());
                assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(f.effective,
                        LocalDateTime.of(1000, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 0)));
                assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(f.effective,
                        LocalDateTime.of(999, 12, 31, 22, 0), LocalDateTime.of(999, 12, 31, 23, 0)));
                assertEquals(0, slotDelMedico(f, f.effective), "nessuna riga per i rifiuti");
                /* Le sovrapposizioni restano rifiutate dal repository. */
                assertThrows(IllegalArgumentException.class, () -> service.creaDisponibilita(f.owner, INIZIO.plusHours(999), INIZIO.plusHours(1001)));
                assertEquals(1000, slotDelMedico(f, f.owner));
            } finally { pulisci(f); }
        }
    }

    static Risposta post(int porta, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + porta + "/api/disponibilita").toURL().openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        byte[] body = json.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return new Risposta(status, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }

    /*
     * Confini DATETIME via HTTP: secondi interi e anni 1000–9999, con inizio strettamente futuro
     * rispetto all'orologio del servizio. La rappresentabilità SQL non basta per autorizzare la
     * creazione.
     */
    @Test
    void fractionalSecondsAreRefusedOverHttpAndDatetimeBoundariesArePersistedExactly() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                var service = new DisponibilitaService(new DisponibilitaRepository(f.factory, ADESSO), ADESSO);
                server.createContext("/api/disponibilita", new DisponibilitaController(service, mapper));
                server.start();
                int porta = server.getAddress().getPort();
                for (String[] frazioni : new String[][]{{"9999-12-31T22:59:59.999999", "9999-12-31T23:59:59.999999"},
                        {"2031-06-02T09:00:00.5", "2031-06-02T12:00:00.5"}, {"2031-06-02T09:00:00.25", "2031-06-02T10:00:00.75"}}) {
                    Risposta r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"" + frazioni[0] + "\",\"dataOraFine\":\"" + frazioni[1] + "\"}");
                    assertEquals(400, r.status(), String.join(" / ", frazioni));
                    assertEquals("{\"error\":\"Date non rappresentabili da MySQL DATETIME.\"}", r.body());
                }
                assertEquals(0, slotDelMedico(f, f.effective), "nessuna riga persistita");
                Risposta r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"9999-12-31T20:59:59\",\"dataOraFine\":\"9999-12-31T23:59:59\"}");
                assertEquals(201, r.status(), r.body());
                r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"1000-01-01T00:00:00\",\"dataOraFine\":\"1000-01-01T02:00:00\"}");
                assertEquals(400, r.status()); assertEquals("{\"error\":\"L'inizio della disponibilità deve essere futuro.\"}", r.body());
                r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"2026-10-14T10:15:30\",\"dataOraFine\":\"2026-10-14T11:15:30\"}");
                assertEquals(400, r.status(), "stesso secondo dell'istante corrente, ma precedente ai suoi millisecondi");
                assertEquals(3, slotDelMedico(f, f.effective), "nessuna riga per gli inizi non futuri");
                r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"2031-06-30T23:00:00\",\"dataOraFine\":\"2031-07-01T01:00:00\"}");
                assertEquals(201, r.status(), r.body());
                List<LocalDateTime> inizi = new ArrayList<>(), fini = new ArrayList<>();
                try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement(
                        "SELECT data_ora_inizio, data_ora_fine FROM disponibilita WHERE id_medico = ? ORDER BY data_ora_inizio")) {
                    s.setInt(1, f.effective);
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next()) { inizi.add(rs.getObject(1, LocalDateTime.class)); fini.add(rs.getObject(2, LocalDateTime.class)); }
                    }
                }
                assertEquals(List.of(LocalDateTime.parse("2031-06-30T23:00"), LocalDateTime.parse("2031-07-01T00:00"),
                        LocalDateTime.parse("9999-12-31T20:59:59"), LocalDateTime.parse("9999-12-31T21:59:59"), LocalDateTime.parse("9999-12-31T22:59:59")), inizi);
                assertEquals(LocalDateTime.parse("9999-12-31T23:59:59"), fini.getLast(), "nessun troncamento o arrotondamento");
                // Limiti invariati su HTTP: 1001 slot rifiutati senza scritture.
                r = post(porta, "{\"idMedico\":" + f.effective + ",\"dataOraInizio\":\"2032-01-01T00:00:00\",\"dataOraFine\":\"2032-02-11T17:00:00\"}");
                assertEquals(400, r.status()); assertEquals("{\"error\":\"Intervallo troppo ampio: massimo 1000 slot per richiesta.\"}", r.body());
                assertEquals(5, slotDelMedico(f, f.effective));
            } finally {
                server.stop(0);
                pulisci(f);
            }
        }
    }

    record Risposta(int status, String body) { }
    static Risposta put(int porta, int slot, byte[] body, boolean chunked) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + porta + "/api/disponibilita/" + slot + "/configurazione").toURL().openConnection();
        c.setRequestMethod("PUT"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        if (chunked) { c.setChunkedStreamingMode(1024); } else { c.setFixedLengthStreamingMode(body.length); }
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return new Risposta(status, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }
    /* Richiesta a blocchi senza buffering del client: il server deve fermarsi dopo 8193 byte decodificati. */
    static Risposta putChunkedGrezzo(int porta, int slot, String prefisso, int riempimento, char carattere) throws IOException {
        try (Socket s = new Socket("127.0.0.1", porta)) {
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            out.write(("PUT /api/disponibilita/" + slot + "/configurazione HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream corpo = new ByteArrayOutputStream();
            corpo.writeBytes(prefisso.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < riempimento; i++) { corpo.write(carattere); }
            byte[] tutto = corpo.toByteArray();
            try {
                for (int i = 0; i < tutto.length; i += 1000) {
                    int n = Math.min(1000, tutto.length - i);
                    out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(tutto, i, n); out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
            } catch (IOException chiusoDalServer) { /* Il server può rispondere e chiudere prima della fine. */ }
            String risposta = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int status = Integer.parseInt(risposta.substring(9, 12));
            return new Risposta(status, risposta.substring(risposta.indexOf("\r\n\r\n") + 4));
        }
    }

    @Test
    void configurationBodyIsLimitedOnARealHttpServerAlsoWhenChunked() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                var service = new DisponibilitaService(new DisponibilitaRepository(f.factory, ADESSO), ADESSO);
                server.createContext("/api/disponibilita", new DisponibilitaController(service, mapper));
                server.start();
                int porta = server.getAddress().getPort();
                List<Disponibilita> slot = service.creaDisponibilita(f.owner, INIZIO, INIZIO.plusHours(8));
                String json = "{\"idPrestazione\":" + f.prestazione + ",\"idStanza\":" + f.stanza + "}";
                String alLimite = json.substring(0, json.length() - 1) + " ".repeat(8192 - json.length()) + "}";
                String oltre = alLimite.substring(0, alLimite.length() - 1) + " }";
                assertEquals(8192, alLimite.getBytes(StandardCharsets.UTF_8).length);
                assertEquals(8193, oltre.getBytes(StandardCharsets.UTF_8).length);
                List<Object> configurato = List.of((long) f.prestazione, (long) f.stanza);
                List<Object> vuoto = java.util.Arrays.asList(null, null);

                assertEquals(200, put(porta, slot.get(0).getIdDisponibilita(), json.getBytes(StandardCharsets.UTF_8), false).status(), "body valido");
                assertEquals(200, put(porta, slot.get(1).getIdDisponibilita(), alLimite.getBytes(StandardCharsets.UTF_8), false).status(), "esattamente al limite");
                assertEquals(200, put(porta, slot.get(2).getIdDisponibilita(), json.getBytes(StandardCharsets.UTF_8), true).status(), "valido e chunked");
                for (int i = 0; i < 3; i++) { assertEquals(configurato, configurazione(f, slot.get(i).getIdDisponibilita())); }

                Risposta r = put(porta, slot.get(3).getIdDisponibilita(), oltre.getBytes(StandardCharsets.UTF_8), false);
                assertEquals(400, r.status()); assertEquals("{\"error\":\"Richiesta non valida.\"}", r.body());
                r = put(porta, slot.get(4).getIdDisponibilita(), oltre.getBytes(StandardCharsets.UTF_8), true);
                assertEquals(400, r.status(), "oltre il limite, chunked");
                /* Campo sconosciuto e spaziatura di 40000 byte, senza buffering del client. */
                r = putChunkedGrezzo(porta, slot.get(5).getIdDisponibilita(), json.substring(0, json.length() - 1) + ",\"x\":\"", 40_000, 'a');
                assertEquals(400, r.status()); assertEquals("{\"error\":\"Richiesta non valida.\"}", r.body());
                r = putChunkedGrezzo(porta, slot.get(6).getIdDisponibilita(), json.substring(0, json.length() - 1), 40_000, ' ');
                assertEquals(400, r.status(), "whitespace eccessivo, chunked");
                for (int i = 3; i < 7; i++) { assertEquals(vuoto, configurazione(f, slot.get(i).getIdDisponibilita()), "slot " + i + " non configurato"); }
            } finally {
                server.stop(0);
                pulisci(f);
            }
        }
    }
}
