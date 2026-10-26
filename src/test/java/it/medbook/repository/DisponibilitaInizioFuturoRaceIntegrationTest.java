package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import it.medbook.controller.DisponibilitaController;
import it.medbook.service.DisponibilitaService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/*
 * L'inizio supera il controllo del servizio, poi attende il lock medico; nel frattempo l'orologio
 * raggiunge l'inizio. Il repository deve ricontrollare prima di scrivere.
 */
class DisponibilitaInizioFuturoRaceIntegrationTest {
    private static final LocalDateTime INIZIO = LocalDateTime.of(2031, 6, 2, 9, 0);
    private static final Instant ISTANTE_INIZIO = INIZIO.toInstant(ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /* Stessa istanza di orologio per servizio e repository, spostata dal thread del test. */
    static final class OrologioManuale extends Clock {
        private volatile Instant adesso;
        OrologioManuale(Instant adesso) { this.adesso = adesso; }
        void imposta(Instant istante) { adesso = istante; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return adesso; }
    }

    /* Nel catalogo solo le due transazioni della prova toccano MEDICO: l'attesa osservata è pertinente. */
    static void attendiAttesaMedico(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, Future<?> inAttesa) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.OBJECT_SCHEMA = DATABASE() AND r.OBJECT_NAME = 'medico'""")) {
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { return; } }
                assertFalse(inAttesa.isDone(), "la richiesta non deve concludersi prima del rilascio del lock");
                assertTrue(System.nanoTime() < deadline, "nessuna attesa InnoDB osservata sulla riga medico");
                Thread.sleep(10); /* Attesa tramite polling. */
            }
        }
    }

    /* Esegue il caso e restituisce la risposta HTTP; l'orologio avanza mentre la richiesta attende. */
    private DisponibilitaLimitiIntegrationTest.Risposta scenario(PrenotazioneIndisponibilitaIntegrationTest.Fixture f,
            Instant dopoAttesa) throws Exception {
        OrologioManuale orologio = new OrologioManuale(ISTANTE_INIZIO.minusSeconds(3600));
        var service = new DisponibilitaService(new DisponibilitaRepository(f.factory, orologio), orologio);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection trattiene = f.factory.getConnection()) {
            server.createContext("/api/disponibilita", new DisponibilitaController(service, mapper));
            server.start();
            int porta = server.getAddress().getPort();
            trattiene.setAutoCommit(false);
            try (PreparedStatement s = trattiene.prepareStatement("SELECT stato FROM medico WHERE id_utente = ? FOR UPDATE")) {
                s.setInt(1, f.owner);
                try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); }
            }
            Future<DisponibilitaLimitiIntegrationTest.Risposta> richiesta = pool.submit(() -> DisponibilitaLimitiIntegrationTest.post(porta,
                    "{\"idMedico\":" + f.owner + ",\"dataOraInizio\":\"" + INIZIO + ":00\",\"dataOraFine\":\"" + INIZIO.plusHours(2) + ":00\"}"));
            try {
                /* L'attesa su MEDICO dimostra che il controllo temporale del servizio è già passato. */
                attendiAttesaMedico(f, richiesta);
                orologio.imposta(dopoAttesa);
            } finally {
                trattiene.rollback();
            }
            return richiesta.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            server.stop(0);
        }
    }

    private static long righe(PrenotazioneIndisponibilitaIntegrationTest.Fixture f) throws SQLException {
        return DisponibilitaLimitiIntegrationTest.slotDelMedico(f, f.owner);
    }

    @Test
    void startReachedWhileWaitingOnTheDoctorLockIsRefusedAndWritesNothing() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            try {
                assertEquals(0, righe(f));
                var r = scenario(f, ISTANTE_INIZIO);
                assertEquals(400, r.status(), r.body());
                assertEquals("{\"error\":\"L'inizio della disponibilità deve essere futuro.\"}", r.body());
                assertEquals(0, righe(f), "nessuna riga dopo il rifiuto");
            } finally { DisponibilitaLimitiIntegrationTest.pulisci(f); }
        }
    }

    @Test
    void startPassedByOneNanosecondWhileWaitingIsRefusedAndWritesNothing() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            try {
                var r = scenario(f, ISTANTE_INIZIO.plusNanos(1));
                assertEquals(400, r.status(), r.body());
                assertEquals("{\"error\":\"L'inizio della disponibilità deve essere futuro.\"}", r.body());
                assertEquals(0, righe(f), "nessuna riga dopo il rifiuto");
            } finally { DisponibilitaLimitiIntegrationTest.pulisci(f); }
        }
    }

    @Test
    void startStillFutureAfterTheWaitIsCreated() throws Exception {
        try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            try {
                var r = scenario(f, ISTANTE_INIZIO.minusNanos(1));
                assertEquals(201, r.status(), r.body());
                assertEquals(2, mapper.readTree(r.body()).size());
                assertEquals(2, righe(f));
            } finally { DisponibilitaLimitiIntegrationTest.pulisci(f); }
        }
    }
}
