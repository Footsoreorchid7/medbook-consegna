package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.dto.CaricamentoRefertoResponse;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.Disponibilita;
import it.medbook.service.DisponibilitaService;
import it.medbook.service.RefertoService;
import it.medbook.support.AuthenticatedTestExchange;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/* Perdita del permesso medico nella transazione: 403; i conflitti di dominio conservano i propri codici. */
class MedicoNonOperativoControllerTest {
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String CREA = "{\"idMedico\":7,\"dataOraInizio\":\"2031-01-15T09:00:00\",\"dataOraFine\":\"2031-01-15T10:00:00\"}";
    static final String ORARIO = "{\"idMedico\":7,\"dataOraInizio\":\"2031-01-15T09:00:00\",\"dataOraFine\":\"2031-01-15T10:00:00\"}";

    static class Disponibilita403 extends DisponibilitaService {
        RuntimeException failure; int calls;
        Disponibilita403() { super(null); }
        @Override public List<Disponibilita> creaDisponibilita(Integer m, LocalDateTime a, LocalDateTime b) { calls++; throw failure; }
        @Override public Disponibilita modificaOrario(Integer id, Integer m, LocalDateTime a, LocalDateTime b) { calls++; throw failure; }
        @Override public void eliminaDisponibilita(Integer id, Integer m) { calls++; throw failure; }
    }
    static class Referto403 extends RefertoService {
        RuntimeException failure; int calls;
        Referto403() { super(null, null, null); }
        @Override public CaricamentoRefertoResponse upload(Integer b, Integer d, InputStream input, Long length) { calls++; throw failure; }
    }

    AuthenticatedTestExchange call(DisponibilitaService service, String method, String path, String body) throws Exception {
        var e = new AuthenticatedTestExchange(method, path, body, 7); new DisponibilitaController(service, mapper).handle(e); return e;
    }

    @Test void uc19WritesMapLostMedicoPermissionTo403() throws Exception {
        var service = new Disponibilita403(); service.failure = new OperazioneNonAutorizzataException("Medico non operativo.");
        for (var e : List.of(call(service, "POST", "/api/disponibilita", CREA), call(service, "PUT", "/api/disponibilita/1/orario", ORARIO),
                call(service, "DELETE", "/api/disponibilita/1?medicoId=7", ""))) {
            assertEquals(403, e.exchange.status); assertEquals("{\"error\":\"Medico non operativo.\"}", e.exchange.body()); assertTrue(e.exchange.closed);
        }
        assertEquals(3, service.calls);
    }

    @Test void uc19DomainOutcomesAreUnchanged() throws Exception {
        var service = new Disponibilita403(); service.failure = new ConflittoDisponibilitaException("Disponibilità con prenotazioni associate.");
        assertEquals(409, call(service, "PUT", "/api/disponibilita/1/orario", ORARIO).exchange.status);
        assertEquals(409, call(service, "DELETE", "/api/disponibilita/1?medicoId=7", "").exchange.status);
        service.failure = new IllegalArgumentException("Il medico indicato non esiste.");
        assertEquals(400, call(service, "POST", "/api/disponibilita", CREA).exchange.status);
        service.failure = new IllegalStateException("inatteso");
        assertEquals(500, call(service, "PUT", "/api/disponibilita/1/orario", ORARIO).exchange.status);
    }

    AuthenticatedTestExchange upload(RefertoService service) throws Exception {
        var e = new AuthenticatedTestExchange("POST", "/api/prenotazioni/197/referto", "binary", 7);
        e.getRequestHeaders().set("Content-Type", "application/pdf"); new RefertoController(service, mapper).handle(e); return e;
    }

    @Test void uc22UploadMapsLostMedicoPermissionTo403AndKeepsConflicts() throws Exception {
        var service = new Referto403(); service.failure = new OperazioneNonAutorizzataException("Medico non operativo.");
        var e = upload(service); assertEquals(403, e.exchange.status); assertEquals("{\"error\":\"Medico non operativo.\"}", e.exchange.body());
        service.failure = new ConflittoPrenotazioneException("Referto già presente.");
        assertEquals(409, upload(service).exchange.status);
        service.failure = new IllegalStateException("inatteso");
        assertEquals(500, upload(service).exchange.status);
        assertEquals(3, service.calls);
    }
}
