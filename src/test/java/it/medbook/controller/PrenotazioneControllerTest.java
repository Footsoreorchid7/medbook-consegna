package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.*;
import it.medbook.service.PrenotazioneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.exception.OperazioneNonAutorizzataException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import it.medbook.dto.RichiestaPrenotazioneResponse;
import it.medbook.dto.AppuntamentoAgendaResponse;
import it.medbook.dto.AppuntamentoPazienteResponse;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PrenotazioneControllerTest {
    private static final String BODY = "{\"idPaziente\":197,\"idDisponibilita\":3}";
    private final FakeService service = new FakeService();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final PrenotazioneController controller = new PrenotazioneController(service, mapper);

    @Test
    void validPostReturnsSafeResponseWithSnapshotPriceAndSlotOwner() throws IOException {
        service.result = booking();
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", BODY);
        assertResponse(exchange, 201);
        assertTrue(exchange.inputClosed);
        assertEquals(1, service.calls);
        assertEquals(List.of(197, 3), service.ids);
        JsonNode json = mapper.readTree(exchange.body());
        assertEquals(12, json.size());
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato", "dataOraRichiesta", "prezzo", "idDisponibilita",
                "dataOraInizio", "dataOraFine", "idPrestazione", "nomePrestazione", "idMedico",
                "nomeMedico", "cognomeMedico"), names);
        assertEquals(42, json.get("idPrenotazione").asInt());
        assertEquals("RICHIESTA", json.get("stato").asText());
        assertEquals("2030-01-15T08:00:00", json.get("dataOraRichiesta").asText());
        assertEquals(0, new BigDecimal("50.00").compareTo(json.get("prezzo").decimalValue()));
        assertEquals(3, json.get("idDisponibilita").asInt());
        assertEquals("2030-01-15T09:00:00", json.get("dataOraInizio").asText());
        assertEquals("2030-01-15T10:00:00", json.get("dataOraFine").asText());
        assertEquals(11, json.get("idPrestazione").asInt());
        assertEquals("Visita", json.get("nomePrestazione").asText());
        assertEquals(7, json.get("idMedico").asInt());
        assertEquals("Mario", json.get("nomeMedico").asText());
        assertEquals("Rossi", json.get("cognomeMedico").asText());
        for (String forbidden : List.of("passwordHash", "hash-fittizio", "email", "segreto@example.invalid",
                "codiceFiscale", "medicoEffettivo", "gestitaDa", "stanza", "utente", "paziente")) {
            assertFalse(exchange.body().contains(forbidden), forbidden);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bad", "null", "[]", "{}", "true",
            "{\"idPaziente\":197}", "{\"idDisponibilita\":3}",
            "{\"idPaziente\":null,\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":null}",
            "{\"idPaziente\":\"197\",\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":\"3\"}",
            "{\"idPaziente\":197.5,\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":3.0}",
            "{\"idPaziente\":true,\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":false}",
            "{\"idPaziente\":2147483648,\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":2147483648}",
            "{\"idPaziente\":0,\"idDisponibilita\":3}",
            "{\"idPaziente\":-1,\"idDisponibilita\":3}",
            "{\"idPaziente\":197,\"idDisponibilita\":0}",
            "{\"idPaziente\":197,\"idDisponibilita\":-1}",
            "{\"idPaziente\":197,\"idDisponibilita\":3,\"unknown\":1}",
            "{\"idPaziente\":197,\"idDisponibilita\":3,\"prezzo\":1}",
            BODY + " {}", BODY + " trailing"})
    void rejectsInvalidBodyWithoutCallingService(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", body);
        assertResponse(exchange, 400);
        assertTrue(exchange.inputClosed);
        assertError(exchange, "Richiesta non valida.");
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Paziente non trovato.", "Disponibilità non trovata."})
    void mapsMissingResourceTo404(String message) throws IOException {
        service.error = new RisorsaNonTrovataException(message);
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", BODY);
        assertResponse(exchange, 404);
        assertError(exchange, message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Disponibilità non configurata.", "La disponibilità non è più futura.",
            "Disponibilità già occupata."})
    void mapsConflictTo409(String message) throws IOException {
        service.error = new ConflittoPrenotazioneException(message);
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", BODY);
        assertResponse(exchange, 409);
        assertError(exchange, message);
    }

    @Test
    void mapsServiceValidationTo400() throws IOException {
        service.error = new IllegalArgumentException("ID non valido.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", BODY);
        assertResponse(exchange, 400);
        assertError(exchange, "ID non valido.");
    }

    @Test
    void sqlErrorReturnsGeneric500() throws IOException {
        service.sqlError = new SQLException("Credenziali e query riservate.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni", BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
    void allowsOnlyPost(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni", BODY);
        assertResponse(exchange, 405);
        assertEquals("POST", exchange.responseHeaders.getFirst("Allow"));
        assertError(exchange, "Metodo non consentito.");
        assertEquals(0, service.calls);
    }

    @Test
    void unknownPathReturns404WithoutServiceCall() throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/3", BODY), 404);
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @CsvSource(value = {"'',null,null,null,null", "?medicoId=7,7,null,null,null",
            "?prestazioneId=11,null,11,null,null", "?data=2030-01-15,null,null,2030-01-15,2030-01-15",
            "?dataDa=2030-01-15,null,null,2030-01-15,null", "?dataA=2030-01-20,null,null,null,2030-01-20",
            "?medicoId=7&prestazioneId=11&dataDa=2030-01-15&dataA=2030-01-20,7,11,2030-01-15,2030-01-20",
            "?medicoId=7&prestazioneId=11&data=2030-01-15,7,11,2030-01-15,2030-01-15",
            "?%6DedicoId=%37,7,null,null,null"}, nullValues = "null")
    void uc10AcceptsFiltersAndReturnsEmptyArray(String query, Integer medico, Integer prestazione,
                                               LocalDate da, LocalDate a) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/richieste" + query, "");
        assertResponse(exchange, 200);
        assertEquals("[]", exchange.body());
        assertEquals(medico, service.medico);
        assertEquals(prestazione, service.prestazione);
        assertEquals(da, service.da);
        assertEquals(a, service.a);
        assertEquals(1, service.searchCalls);
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stato=INESISTENTE", "stato=", "stato=RICHIESTA&st%61to=RIFIUTATA", "sort=data", "unknown=1", "medicoId=7&medicoId=7",
            "medicoId=7&%6DedicoId=8", "prestazioneId=11&prestazioneId=11", "data=2030-01-15&data=2030-01-15",
            "dataDa=2030-01-15&dataDa=2030-01-15", "dataA=2030-01-15&dataA=2030-01-15",
            "medicoId=", "prestazioneId=", "data=", "dataDa=", "dataA=", "medicoId",
            "medicoId=abc", "prestazioneId=1.5", "medicoId=2147483648", "prestazioneId=2147483648",
            "medicoId=0", "medicoId=-1", "prestazioneId=0", "prestazioneId=-1", "medicoId=%207",
            "medicoId=%2B7", "data=2030-02-30", "data=2030-2-03", "data=15-01-2030",
            "dataDa=2030-02-30", "dataA=bad", "data=2030-01-15T09:00:00", "medicoId=7&",
            "data=2030-01-15&dataDa=2030-01-15", "data=2030-01-15&dataA=2030-01-20",
            "dataDa=2030-01-20&dataA=2030-01-15"})
    void uc10RejectsInvalidFiltersWithoutServiceCall(String query) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/richieste?" + query, "");
        assertResponse(exchange, 400);
        assertError(exchange, "Parametri di ricerca non validi.");
        assertEquals(0, service.searchCalls);
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void uc10ReturnsOnlyDeskDtoFieldsAndSnapshotPrice(boolean pending) throws IOException {
        RichiestaPrenotazioneResponse dto = new RichiestaPrenotazioneResponse();
        dto.setIdPrenotazione(42);
        dto.setIndisponibilitaSegnalata(pending);
        dto.setStato(StatoPrenotazione.RICHIESTA);
        dto.setDataOraRichiesta(LocalDateTime.of(2030, 1, 14, 8, 0));
        dto.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0));
        dto.setDataOraFine(LocalDateTime.of(2030, 1, 15, 10, 0));
        dto.setPrezzo(new BigDecimal("50.00"));
        dto.setIdPaziente(197);
        dto.setNomePaziente("Anna");
        dto.setCognomePaziente("Bianchi");
        dto.setIdMedico(7);
        dto.setNomeMedico("Mario");
        dto.setCognomeMedico("Rossi");
        dto.setIdPrestazione(11);
        dto.setNomePrestazione("Visita");
        dto.setDurataPrestazione(30);
        dto.setIdStanza(4);
        dto.setNomeStanza("Ambulatorio 4");
        service.richieste = List.of(dto);
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/richieste", "");
        assertResponse(exchange, 200);
        JsonNode array = mapper.readTree(exchange.body());
        assertEquals(1, array.size());
        JsonNode item = array.get(0);
        assertEquals(18, item.size());
        assertEquals(pending, item.get("indisponibilitaSegnalata").asBoolean());
        assertEquals(42, item.get("idPrenotazione").asInt());
        assertEquals("RICHIESTA", item.get("stato").asText());
        assertEquals("2030-01-14T08:00:00", item.get("dataOraRichiesta").asText());
        assertEquals("2030-01-15T09:00:00", item.get("dataOraInizio").asText());
        assertEquals("2030-01-15T10:00:00", item.get("dataOraFine").asText());
        assertEquals(0, new BigDecimal("50.00").compareTo(item.get("prezzo").decimalValue()));
        assertEquals(197, item.get("idPaziente").asInt());
        assertEquals("Anna", item.get("nomePaziente").asText());
        assertEquals("Bianchi", item.get("cognomePaziente").asText());
        assertEquals(7, item.get("idMedico").asInt());
        assertEquals("Mario", item.get("nomeMedico").asText());
        assertEquals("Rossi", item.get("cognomeMedico").asText());
        assertEquals(11, item.get("idPrestazione").asInt());
        assertEquals("Visita", item.get("nomePrestazione").asText());
        assertEquals(30, item.get("durataPrestazione").asInt());
        assertEquals(4, item.get("idStanza").asInt());
        assertEquals("Ambulatorio 4", item.get("nomeStanza").asText());
        for (String forbidden : List.of("passwordHash", "codiceFiscale", "dataNascita", "telefono", "email",
                "utente", "paziente", "medico", "prestazione", "stanza", "medicoEffettivo", "gestitaDa")) {
            assertFalse(item.has(forbidden));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
    void uc10AllowsOnlyGet(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/richieste", "");
        assertResponse(exchange, 405);
        assertEquals("GET", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.searchCalls);
        assertEquals(0, service.calls);
    }

    @Test
    void uc10SqlErrorIsGeneric() throws IOException {
        service.sqlError = new SQLException("Dettaglio DB riservato.");
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/richieste", "");
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @Test
    void uc10MapsServiceValidationTo400() throws IOException {
        service.error = new IllegalArgumentException("Intervallo non valido.");
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/richieste", "");
        assertResponse(exchange, 400);
        assertError(exchange, "Intervallo non valido.");
    }

    private static final String CONFIRM_BODY = "{\"idOperatore\":123}";

    @Test
    void uc11ReturnsSafeConfirmationDtoAndAssignedDoctor() throws IOException {
        ConfermaPrenotazioneResponse dto = new ConfermaPrenotazioneResponse();
        dto.setIdPrenotazione(3);
        dto.setStato(StatoPrenotazione.CONFERMATA);
        dto.setIdOperatore(123);
        dto.setDataOraGestione(LocalDateTime.of(2030, 1, 15, 8, 0));
        dto.setIdMedicoEffettivo(7);
        service.confirmed = dto;
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/conferma", CONFIRM_BODY);
        assertResponse(exchange, 200);
        assertTrue(exchange.inputClosed);
        assertEquals(1, service.confirmCalls);
        assertEquals(List.of(3, 123), service.confirmIds);
        JsonNode json = mapper.readTree(exchange.body());
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato", "idOperatore", "dataOraGestione", "idMedicoEffettivo"), names);
        assertEquals(3, json.get("idPrenotazione").asInt());
        assertEquals("CONFERMATA", json.get("stato").asText());
        assertEquals(123, json.get("idOperatore").asInt());
        assertEquals("2030-01-15T08:00:00", json.get("dataOraGestione").asText());
        assertEquals(7, json.get("idMedicoEffettivo").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bad", "null", "[]", "{}", "true", "{\"idOperatore\":null}",
            "{\"idOperatore\":\"123\"}", "{\"idOperatore\":123.0}", "{\"idOperatore\":true}",
            "{\"idOperatore\":2147483648}", "{\"idOperatore\":0}", "{\"idOperatore\":-1}",
            "{\"idOperatore\":123,\"unknown\":1}", "{\"idOperatore\":123,\"idMedicoEffettivo\":7}",
            CONFIRM_BODY + " {}", CONFIRM_BODY + " trailing"})
    void uc11RejectsInvalidBodies(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/conferma", body);
        assertResponse(exchange, 400);
        assertTrue(exchange.inputClosed);
        assertEquals(0, service.confirmCalls);
        assertError(exchange, "Richiesta non valida.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "2147483648", "%33", "+3", "3.0"})
    void uc11RejectsInvalidPathIds(String id) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/" + id + "/conferma", CONFIRM_BODY), 400);
        assertEquals(0, service.confirmCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc11AllowsOnlyPost(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/3/conferma", CONFIRM_BODY);
        assertResponse(exchange, 405);
        assertEquals("POST", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.confirmCalls);
    }

    @Test
    void uc11MapsApplicationErrorsAndGenericSqlError() throws IOException {
        RuntimeException[] errors = {new OperazioneNonAutorizzataException("Ruolo non consentito."),
                new RisorsaNonTrovataException("Operatore assente."), new RisorsaNonTrovataException("Prenotazione assente."),
                new ConflittoPrenotazioneException("Non confermabile."), new IllegalArgumentException("ID non valido.")};
        int[] statuses = {403, 404, 404, 409, 400};
        for (int i = 0; i < errors.length; i++) {
            service.error = errors[i];
            FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/conferma", CONFIRM_BODY);
            assertResponse(exchange, statuses[i]);
            assertError(exchange, errors[i].getMessage());
        }
        service.error = null;
        service.sqlError = new SQLException("Query riservata.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/conferma", CONFIRM_BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @Test
    void uc12ReturnsSafeConfirmationDto() throws IOException {
        RifiutoPrenotazioneResponse dto = new RifiutoPrenotazioneResponse();
        dto.setIdPrenotazione(3);
        dto.setStato(StatoPrenotazione.RIFIUTATA);
        dto.setIdOperatore(123);
        dto.setDataOraGestione(LocalDateTime.of(2030, 1, 15, 8, 0));
        service.rejected = dto;
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/rifiuta", CONFIRM_BODY);
        assertResponse(exchange, 200);
        assertTrue(exchange.inputClosed);
        assertEquals(1, service.rejectCalls);
        assertEquals(List.of(3, 123), service.rejectIds);
        JsonNode json = mapper.readTree(exchange.body());
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato", "idOperatore", "dataOraGestione"), names);
        assertEquals(3, json.get("idPrenotazione").asInt());
        assertEquals("RIFIUTATA", json.get("stato").asText());
        assertEquals(123, json.get("idOperatore").asInt());
        assertEquals("2030-01-15T08:00:00", json.get("dataOraGestione").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bad", "null", "[]", "{}", "true", "{\"idOperatore\":null}",
            "{\"idOperatore\":\"123\"}", "{\"idOperatore\":123.0}", "{\"idOperatore\":true}",
            "{\"idOperatore\":2147483648}", "{\"idOperatore\":0}", "{\"idOperatore\":-1}",
            "{\"idOperatore\":123,\"unknown\":1}", "{\"idOperatore\":123,\"idMedicoEffettivo\":7}",
            CONFIRM_BODY + " {}", CONFIRM_BODY + " trailing"})
    void uc12RejectsInvalidBodies(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/rifiuta", body);
        assertResponse(exchange, 400);
        assertTrue(exchange.inputClosed);
        assertEquals(0, service.rejectCalls);
        assertError(exchange, "Richiesta non valida.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "2147483648", "%33", "+3", "3.0"})
    void uc12RejectsInvalidPathIds(String id) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/" + id + "/rifiuta", CONFIRM_BODY), 400);
        assertEquals(0, service.rejectCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc12AllowsOnlyPost(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/3/rifiuta", CONFIRM_BODY);
        assertResponse(exchange, 405);
        assertEquals("POST", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.rejectCalls);
    }

    @Test
    void uc12MapsApplicationErrorsAndGenericSqlError() throws IOException {
        RuntimeException[] errors = {new OperazioneNonAutorizzataException("Ruolo non consentito."),
                new RisorsaNonTrovataException("Operatore assente."), new RisorsaNonTrovataException("Prenotazione assente."),
                new ConflittoPrenotazioneException("Non confermabile."), new IllegalArgumentException("ID non valido.")};
        int[] statuses = {403, 404, 404, 409, 400};
        for (int i = 0; i < errors.length; i++) {
            service.error = errors[i];
            FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/rifiuta", CONFIRM_BODY);
            assertResponse(exchange, statuses[i]);
            assertError(exchange, errors[i].getMessage());
        }
        service.error = null;
        service.sqlError = new SQLException("Query riservata.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/rifiuta", CONFIRM_BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    private static final String CANCEL_BODY = "{\"idOperatore\":123,\"statoAtteso\":\"RICHIESTA\"}";

    @Test
    void uc13ReturnsSafeCancellationDto() throws IOException {
        AnnullamentoPrenotazioneResponse dto = new AnnullamentoPrenotazioneResponse();
        dto.setIdPrenotazione(3);
        dto.setStato(StatoPrenotazione.ANNULLATA);
        dto.setIdOperatore(123);
        dto.setDataOraGestione(LocalDateTime.of(2030, 1, 15, 8, 0));
        service.cancelled = dto;
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla", CANCEL_BODY);
        assertResponse(exchange, 200);
        assertTrue(exchange.inputClosed);
        assertEquals(1, service.cancelCalls);
        assertEquals(List.of(3, 123), service.cancelIds);
        JsonNode json = mapper.readTree(exchange.body());
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato", "idOperatore", "dataOraGestione"), names);
        assertEquals(3, json.get("idPrenotazione").asInt());
        assertEquals("ANNULLATA", json.get("stato").asText());
        assertEquals(123, json.get("idOperatore").asInt());
        assertEquals("2030-01-15T08:00:00", json.get("dataOraGestione").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bad", "null", "[]", "{}", "true", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":null}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":\"123\"}", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":123.0}", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":true}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":2147483648}", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":0}", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":-1}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":123,\"unknown\":1}", "{\"statoAtteso\":\"RICHIESTA\",\"idOperatore\":123,\"idMedicoEffettivo\":7}",
            CANCEL_BODY + " {}", CANCEL_BODY + " trailing"})
    void uc13RejectsInvalidBodies(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla", body);
        assertResponse(exchange, 400);
        assertTrue(exchange.inputClosed);
        assertEquals(0, service.cancelCalls);
        assertError(exchange, "Richiesta non valida.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "2147483648", "%33", "+3", "3.0"})
    void uc13RejectsInvalidPathIds(String id) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/" + id + "/annulla", CANCEL_BODY), 400);
        assertEquals(0, service.cancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc13AllowsOnlyPost(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/3/annulla", CANCEL_BODY);
        assertResponse(exchange, 405);
        assertEquals("POST", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.cancelCalls);
    }

    @Test
    void uc13MapsApplicationErrorsAndGenericSqlError() throws IOException {
        RuntimeException[] errors = {new OperazioneNonAutorizzataException("Ruolo non consentito."),
                new RisorsaNonTrovataException("Operatore assente."), new RisorsaNonTrovataException("Prenotazione assente."),
                new ConflittoPrenotazioneException("Non confermabile."), new IllegalArgumentException("ID non valido.")};
        int[] statuses = {403, 404, 404, 409, 400};
        for (int i = 0; i < errors.length; i++) {
            service.error = errors[i];
            FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla", CANCEL_BODY);
            assertResponse(exchange, statuses[i]);
            assertError(exchange, errors[i].getMessage());
        }
        service.error = null;
        service.sqlError = new SQLException("Query riservata.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla", CANCEL_BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"idOperatore\":123}", "{\"idOperatore\":123,\"statoAtteso\":null}",
            "{\"idOperatore\":123,\"statoAtteso\":1}", "{\"idOperatore\":123,\"statoAtteso\":true}",
            "{\"idOperatore\":123,\"statoAtteso\":\"ANNULLATA\"}",
            "{\"idOperatore\":123,\"statoAtteso\":\"richiesta\"}",
            "{\"idOperatore\":123,\"statoAtteso\":\"RICHIESTA\",\"statoAtteso\":\"CONFERMATA\"}"})
    void uc13RejectsInvalidExpectedState(String body) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/3/annulla", body), 400);
        assertEquals(0, service.cancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RICHIESTA", "CONFERMATA"})
    void uc13PassesExpectedStateToService(String expected) throws IOException {
        service.cancelled = new AnnullamentoPrenotazioneResponse();
        assertResponse(request("POST", "/api/prenotazioni/3/annulla",
                "{\"idOperatore\":123,\"statoAtteso\":\"" + expected + "\"}"), 200);
        assertEquals(StatoPrenotazione.valueOf(expected), service.expected);
        assertEquals(1, service.cancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/prenotazioni/3/annulla/extra", "/api/prenotazioni/3/annulla/",
            "/api/prenotazioni/annulla", "/api/prenotazioni//annulla"})
    void uc13RejectsMalformedRoutingWithoutCallingService(String path) throws IOException {
        assertResponse(request("POST", path, CANCEL_BODY), 404);
        assertEquals(0, service.cancelCalls);
        assertEquals(0, service.calls);
    }

    @Test
    void uc13RejectsDuplicateOperatorBeforeCallingService() throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/3/annulla",
                "{\"idOperatore\":123,\"idOperatore\":124,\"statoAtteso\":\"RICHIESTA\"}"), 400);
        assertEquals(0, service.cancelCalls);
    }

    private static final String PATIENT_CANCEL_BODY = "{\"idPaziente\":123,\"statoAtteso\":\"RICHIESTA\"}";

    @Test
    void uc07ReturnsSafeCancellationDto() throws IOException {
        AnnullamentoPazienteResponse dto = new AnnullamentoPazienteResponse();
        dto.setIdPrenotazione(3);
        dto.setStato(StatoPrenotazione.ANNULLATA);
        service.patientCancelled = dto;
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla-paziente", PATIENT_CANCEL_BODY);
        assertResponse(exchange, 200);
        assertTrue(exchange.inputClosed);
        assertEquals(1, service.patientCancelCalls);
        assertEquals(List.of(3, 123), service.patientCancelIds);
        JsonNode json = mapper.readTree(exchange.body());
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato"), names);
        assertEquals(3, json.get("idPrenotazione").asInt());
        assertEquals("ANNULLATA", json.get("stato").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{bad", "null", "[]", "{}", "true", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":null}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":\"123\"}", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":123.0}", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":true}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":2147483648}", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":0}", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":-1}",
            "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":123,\"unknown\":1}", "{\"statoAtteso\":\"RICHIESTA\",\"idPaziente\":123,\"idMedicoEffettivo\":7}",
            PATIENT_CANCEL_BODY + " {}", PATIENT_CANCEL_BODY + " trailing"})
    void uc07RejectsInvalidBodies(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla-paziente", body);
        assertResponse(exchange, 400);
        assertTrue(exchange.inputClosed);
        assertEquals(0, service.patientCancelCalls);
        assertError(exchange, "Richiesta non valida.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "2147483648", "%33", "+3", "3.0"})
    void uc07RejectsInvalidPathIds(String id) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/" + id + "/annulla-paziente", PATIENT_CANCEL_BODY), 400);
        assertEquals(0, service.patientCancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc07AllowsOnlyPost(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/3/annulla-paziente", PATIENT_CANCEL_BODY);
        assertResponse(exchange, 405);
        assertEquals("POST", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.patientCancelCalls);
    }

    @Test
    void uc07MapsApplicationErrorsAndGenericSqlError() throws IOException {
        RuntimeException[] errors = {new RisorsaNonTrovataException("Paziente assente."), new RisorsaNonTrovataException("Prenotazione assente."),
                new ConflittoPrenotazioneException("Non annullabile."), new IllegalArgumentException("ID non valido.")};
        int[] statuses = {404, 404, 409, 400};
        for (int i = 0; i < errors.length; i++) {
            service.error = errors[i];
            FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla-paziente", PATIENT_CANCEL_BODY);
            assertResponse(exchange, statuses[i]);
            assertError(exchange, errors[i].getMessage());
        }
        service.error = null;
        service.sqlError = new SQLException("Query riservata.");
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/3/annulla-paziente", PATIENT_CANCEL_BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"idPaziente\":123}", "{\"idPaziente\":123,\"statoAtteso\":null}",
            "{\"idPaziente\":123,\"statoAtteso\":1}", "{\"idPaziente\":123,\"statoAtteso\":true}",
            "{\"idPaziente\":123,\"statoAtteso\":\"ANNULLATA\"}",
            "{\"idPaziente\":123,\"statoAtteso\":\"richiesta\"}",
            "{\"idPaziente\":123,\"statoAtteso\":\"RICHIESTA\",\"statoAtteso\":\"CONFERMATA\"}"})
    void uc07RejectsInvalidExpectedState(String body) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/3/annulla-paziente", body), 400);
        assertEquals(0, service.patientCancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RICHIESTA", "CONFERMATA"})
    void uc07PassesExpectedStateToService(String expected) throws IOException {
        service.patientCancelled = new AnnullamentoPazienteResponse();
        assertResponse(request("POST", "/api/prenotazioni/3/annulla-paziente",
                "{\"idPaziente\":123,\"statoAtteso\":\"" + expected + "\"}"), 200);
        assertEquals(StatoPrenotazione.valueOf(expected), service.patientExpected);
        assertEquals(1, service.patientCancelCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/prenotazioni/3/annulla-paziente/extra", "/api/prenotazioni/3/annulla-paziente/",
            "/api/prenotazioni/annulla-paziente", "/api/prenotazioni//annulla-paziente"})
    void uc07RejectsMalformedRoutingWithoutCallingService(String path) throws IOException {
        assertResponse(request("POST", path, PATIENT_CANCEL_BODY), 404);
        assertEquals(0, service.patientCancelCalls);
        assertEquals(0, service.calls);
    }

    @Test
    void uc07RejectsDuplicateOperatorBeforeCallingService() throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/3/annulla-paziente",
                "{\"idPaziente\":123,\"idPaziente\":124,\"statoAtteso\":\"RICHIESTA\"}"), 400);
        assertEquals(0, service.patientCancelCalls);
    }

    @Test
    void uc18ReturnsSafeDtoWithIsoDatesAndNullableFields() throws IOException {
        AppuntamentoAgendaResponse dto = new AppuntamentoAgendaResponse();
        dto.setIdPrenotazione(42); dto.setIdDisponibilita(3); dto.setStato(StatoPrenotazione.RICHIESTA);
        dto.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0));
        dto.setDataOraFine(LocalDateTime.of(2030, 1, 15, 10, 0));
        dto.setIdMedicoTitolare(7); dto.setIdPaziente(197);
        dto.setNomePaziente("Anna"); dto.setCognomePaziente("Rossi");
        service.agenda = List.of(dto);
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?medicoId=7&data=2030-01-15", "");
        assertResponse(exchange, 200);
        JsonNode row = mapper.readTree(exchange.body()).get(0);
        Set<String> names = new java.util.HashSet<>(); row.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "idDisponibilita", "stato", "indisponibilitaSegnalata", "refertoPresente", "dataOraInizio", "dataOraFine",
                "idMedicoTitolare", "idMedicoEffettivo", "idPrestazione", "nomePrestazione", "durataPrestazione",
                "idStanza", "nomeStanza", "idPaziente", "nomePaziente", "cognomePaziente"), names);
        assertEquals("2030-01-15T09:00:00", row.get("dataOraInizio").asText());
        assertEquals("2030-01-15T10:00:00", row.get("dataOraFine").asText());
        assertEquals(197, row.get("idPaziente").asInt());
        assertEquals("Anna", row.get("nomePaziente").asText());
        assertTrue(row.get("idMedicoEffettivo").isNull());
        assertFalse(row.get("indisponibilitaSegnalata").asBoolean());
        assertTrue(row.get("idPrestazione").isNull());
        assertTrue(row.get("idStanza").isNull());
        assertEquals(1, service.agendaCalls);
        assertEquals(0, service.calls); assertEquals(0, service.searchCalls);
    }

    @Test
    void agendaExposesOnlyPresenceForExistingReport() throws IOException {
        AppuntamentoAgendaResponse appointment = new AppuntamentoAgendaResponse();
        appointment.setRefertoPresente(true);
        service.agenda = List.of(appointment);
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?medicoId=7", "");
        assertResponse(exchange, 200);
        JsonNode row = mapper.readTree(exchange.body()).get(0);
        assertTrue(row.get("refertoPresente").asBoolean());
        assertFalse(row.has("idReferto")); assertFalse(row.has("nomeFile"));
        assertFalse(row.has("percorsoFile")); assertFalse(row.has("contenuto"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "medicoId=7,null,null,null",
            "medicoId=7&data=2030-01-15,2030-01-15,null,null",
            "medicoId=7&dataDa=2030-01-01&dataA=2030-01-31,null,2030-01-01,2030-01-31"}, nullValues = "null")
    void uc18PassesValidFiltersAndReturnsEmptyArray(String query, LocalDate data, LocalDate da, LocalDate a) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?" + query, "");
        assertResponse(exchange, 200); assertEquals("[]", exchange.body());
        assertEquals(7, service.agendaMedico); assertEquals(data, service.agendaData);
        assertEquals(da, service.agendaDa); assertEquals(a, service.agendaA);
        assertEquals(1, service.agendaCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"medicoId=7&data=2030-02-30", "medicoId=7&data=2030-1-15",
            "medicoId=7&data=2030-01-15&dataA=2030-01-15",
            "medicoId=7&dataDa=2030-01-31&dataA=2030-01-01",
            "medicoId=7&data=2030-01-15&%64ata=2030-01-15", "medicoId=7&data=%25ZZ"})
    void uc18RejectsBadDatesAndDecodedDuplicates(String query) throws IOException {
        assertResponse(request("GET", "/api/prenotazioni/agenda?" + query, ""), 400);
        assertEquals(0, service.agendaCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc18OnlyAllowsGet(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/agenda?medicoId=7", "");
        assertResponse(exchange, 405); assertEquals("GET", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.agendaCalls);
    }

    @Test
    void uc18MapsNotFoundInvalidAndGenericSqlErrors() throws IOException {
        service.error = new RisorsaNonTrovataException("Medico non trovato.");
        assertResponse(request("GET", "/api/prenotazioni/agenda?medicoId=7", ""), 404);
        service.error = new IllegalArgumentException("Periodo.");
        assertResponse(request("GET", "/api/prenotazioni/agenda?medicoId=7", ""), 400);
        service.error = null; service.sqlError = new SQLException("Informazioni riservate.");
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?medicoId=7", "");
        assertResponse(exchange, 500); assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "data=2030-01-15", "medicoId=7&dataDa=2030-01-01",
            "medicoId=7&dataA=2030-01-31", "medicoId=7&dataDa=2030-01-01&dataA=2030-02-01", "stato=RICHIESTA", "sort=data", "unknown=1", "medicoId=7&medicoId=7",
            "medicoId=7&%6DedicoId=8", "prestazioneId=11&prestazioneId=11", "data=2030-01-15&data=2030-01-15",
            "dataDa=2030-01-15&dataDa=2030-01-15", "dataA=2030-01-15&dataA=2030-01-15",
            "medicoId=", "prestazioneId=", "data=", "dataDa=", "dataA=", "medicoId",
            "medicoId=abc", "prestazioneId=1.5", "medicoId=2147483648", "prestazioneId=2147483648",
            "medicoId=0", "medicoId=-1", "prestazioneId=0", "prestazioneId=-1", "medicoId=%207",
            "medicoId=%2B7", "data=2030-02-30", "data=2030-2-03", "data=15-01-2030",
            "dataDa=2030-02-30", "dataA=bad", "data=2030-01-15T09:00:00", "medicoId=7&",
            "data=2030-01-15&dataDa=2030-01-15", "data=2030-01-15&dataA=2030-01-20",
            "dataDa=2030-01-20&dataA=2030-01-15"})
    void uc18RejectsInvalidFiltersWithoutServiceCall(String query) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?" + query, "");
        assertResponse(exchange, 400);
        assertError(exchange, "Parametri di ricerca non validi.");
        assertEquals(0, service.agendaCalls);
        assertEquals(0, service.calls);
    }

    @Test
    void uc18UnexpectedErrorRemainsGeneric() throws IOException {
        service.error = new IllegalStateException("Dettagli interni.");
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/agenda?medicoId=7", "");
        assertResponse(exchange, 500); assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.error.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/prenotazioni/agenda/", "/api/prenotazioni/agenda/extra",
            "/api/prenotazioni/7/agenda", "/api/prenotazioni/agende"})
    void uc18MalformedRouteDoesNotCallService(String path) throws IOException {
        assertResponse(request("GET", path + "?medicoId=7", ""), 404);
        assertEquals(0, service.agendaCalls);
    }

    @Test
    void uc06SafeDtoIncludesSnapshotPriceIsoDatesAndNullableDoctor() throws IOException {
        AppuntamentoPazienteResponse dto = new AppuntamentoPazienteResponse();
        dto.setIdPrenotazione(42); dto.setStato(StatoPrenotazione.ANNULLATA);
        dto.setDataOraRichiesta(LocalDateTime.of(2030, 1, 14, 8, 0));
        dto.setPrezzo(new BigDecimal("50.25")); dto.setIdDisponibilita(3);
        dto.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0)); dto.setDataOraFine(LocalDateTime.of(2030, 1, 15, 10, 0));
        dto.setIdMedicoTitolare(7); dto.setNomeMedicoTitolare("Mario"); dto.setCognomeMedicoTitolare("Rossi");
        service.patientList = List.of(dto);
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/paziente?idPaziente=197", "");
        assertResponse(exchange, 200);
        JsonNode row = mapper.readTree(exchange.body()).get(0);
        Set<String> names = new java.util.HashSet<>(); row.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("idPrenotazione", "stato", "dataOraRichiesta", "prezzo", "idDisponibilita",
                "dataOraInizio", "dataOraFine", "idPrestazione", "nomePrestazione", "durataPrestazione",
                "idMedicoTitolare", "nomeMedicoTitolare", "cognomeMedicoTitolare", "idMedicoEffettivo",
                "nomeMedicoEffettivo", "cognomeMedicoEffettivo", "idStanza", "nomeStanza"), names);
        assertEquals("2030-01-15T09:00:00", row.get("dataOraInizio").asText());
        assertEquals("2030-01-15T10:00:00", row.get("dataOraFine").asText());
        assertEquals("2030-01-14T08:00:00", row.get("dataOraRichiesta").asText());
        assertEquals("ANNULLATA", row.get("stato").asText());
        assertEquals(0, new BigDecimal("50.25").compareTo(row.get("prezzo").decimalValue()));
        assertEquals("Mario", row.get("nomeMedicoTitolare").asText());
        for (String name : List.of("idMedicoEffettivo", "nomeMedicoEffettivo", "cognomeMedicoEffettivo",
                "idPrestazione", "nomePrestazione", "durataPrestazione", "idStanza", "nomeStanza")) {
            assertTrue(row.get(name).isNull());
        }
        assertEquals(1, service.patientListCalls); assertEquals(0, service.calls); assertEquals(0, service.agendaCalls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "idPaziente=197,null,null,null,null",
            "idPaziente=197&stato=CONFERMATA,CONFERMATA,null,null,null",
            "idPaziente=197&data=2030-01-15,null,2030-01-15,null,null",
            "idPaziente=197&stato=ANNULLATA&dataDa=2020-01-01&dataA=2030-12-31,ANNULLATA,null,2020-01-01,2030-12-31"}, nullValues = "null")
    void uc06ValidFiltersReturnEmptyArray(String query, StatoPrenotazione state, LocalDate data, LocalDate da, LocalDate a) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/paziente?" + query, "");
        assertResponse(exchange, 200); assertEquals("[]", exchange.body());
        assertEquals(197, service.patientListId); assertEquals(state, service.patientListState);
        assertEquals(data, service.patientListData); assertEquals(da, service.patientListDa); assertEquals(a, service.patientListA);
        assertEquals(1, service.patientListCalls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(StatoPrenotazione.class)
    void uc06AllSevenStateFiltersAreAccepted(StatoPrenotazione state) throws IOException {
        assertResponse(request("GET", "/api/prenotazioni/paziente?idPaziente=197&stato=" + state.name(), ""), 200);
        assertEquals(state, service.patientListState);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "stato=RICHIESTA", "idPaziente=", "idPaziente", "idPaziente=0", "idPaziente=-1",
            "idPaziente=abc", "idPaziente=1.0", "idPaziente=%2B1", "idPaziente=%201", "idPaziente=2147483648",
            "idPaziente=197&unknown=1", "idPaziente=197&medicoId=7", "idPaziente=197&sort=data",
            "idPaziente=197&idPaziente=197", "idPaziente=197&%69dPaziente=198", "idPaziente=197&",
            "idPaziente=197&stato=", "idPaziente=197&stato=confermata", "idPaziente=197&stato=INVALIDO",
            "idPaziente=197&stato=RICHIESTA&%73tato=CONFERMATA", "idPaziente=197&data=",
            "idPaziente=197&data=2030-02-30", "idPaziente=197&data=2030-1-15", "idPaziente=197&data=2030-01-15T09:00:00",
            "idPaziente=197&data=2030-01-15&%64ata=2030-01-15", "idPaziente=197&data=%25ZZ",
            "idPaziente=197&dataDa=2030-01-01", "idPaziente=197&dataA=2030-01-31",
            "idPaziente=197&dataDa=2030-02-01&dataA=2030-01-01", "idPaziente=197&dataDa=bad&dataA=2030-01-01",
            "idPaziente=197&dataDa=2030-01-01&dataA=", "idPaziente=197&data=2030-01-01&dataA=2030-01-01",
            "idPaziente=197&data=2030-01-01&dataDa=2030-01-01", "idPaziente=197&dataA=2030-01-01&dataA=2030-01-01"})
    void uc06RejectsInvalidQueryWithoutCallingService(String query) throws IOException {
        FakeHttpExchange exchange = request("GET", "/api/prenotazioni/paziente?" + query, "");
        assertResponse(exchange, 400); assertError(exchange, "Parametri di ricerca non validi.");
        assertEquals(0, service.patientListCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc06OnlyGetAllowed(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/api/prenotazioni/paziente?idPaziente=197", "");
        assertResponse(exchange, 405); assertEquals("GET", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.patientListCalls);
    }

    @Test
    void uc06MapsNotFoundInvalidSqlAndUnexpectedErrors() throws IOException {
        service.error = new RisorsaNonTrovataException("Paziente non trovato.");
        assertResponse(request("GET", "/api/prenotazioni/paziente?idPaziente=197", ""), 404);
        service.error = new IllegalArgumentException("Periodo estremo.");
        assertResponse(request("GET", "/api/prenotazioni/paziente?idPaziente=197&data=9999-12-31", ""), 400);
        service.error = new IllegalStateException("Segreto.");
        FakeHttpExchange unexpected = request("GET", "/api/prenotazioni/paziente?idPaziente=197", "");
        assertResponse(unexpected, 500); assertError(unexpected, "Errore interno del server.");
        assertFalse(unexpected.body().contains(service.error.getMessage()));
        service.error = null; service.sqlError = new SQLException("Query riservata.");
        FakeHttpExchange sql = request("GET", "/api/prenotazioni/paziente?idPaziente=197", "");
        assertResponse(sql, 500); assertError(sql, "Errore interno del server.");
        assertFalse(sql.body().contains(service.sqlError.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/prenotazioni/paziente/", "/api/prenotazioni/paziente/extra", "/api/prenotazioni/197/paziente"})
    void uc06BadRoutingDoesNotCallService(String path) throws IOException {
        assertResponse(request("GET", path + "?idPaziente=197", ""), 404);
        assertEquals(0, service.patientListCalls);
    }

    private FakeHttpExchange request(String method, String path, String body) throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange(method, path, body);
        controller.handle(exchange);
        return exchange;
    }

    private void assertResponse(FakeHttpExchange exchange, int status) {
        assertEquals(status, exchange.status);
        assertEquals("application/json; charset=UTF-8", exchange.responseHeaders.getFirst("Content-Type"));
        assertTrue(exchange.closed);
        assertTrue(exchange.outputClosed);
        assertEquals(exchange.responseBody.size(), exchange.responseLength);
    }

    private void assertError(FakeHttpExchange exchange, String message) throws IOException {
        assertEquals(mapper.valueToTree(Map.of("error", message)), mapper.readTree(exchange.body()));
    }

    private Prenotazione booking() {
        Utente owner = new Utente();
        owner.setIdUtente(7);
        owner.setNome("Mario");
        owner.setCognome("Rossi");
        owner.setEmail("segreto@example.invalid");
        owner.setPasswordHash("hash-fittizio");
        Medico medico = new Medico();
        medico.setUtente(owner);
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(11);
        prestazione.setNome("Visita");
        prestazione.setPrezzo(new BigDecimal("60.00"));
        Stanza stanza = new Stanza();
        stanza.setIdStanza(4);
        stanza.setNome("Interna");
        Disponibilita slot = new Disponibilita();
        slot.setIdDisponibilita(3);
        slot.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0));
        slot.setDataOraFine(slot.getDataOraInizio().plusHours(1));
        slot.setMedico(medico);
        slot.configura(prestazione, stanza);
        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setIdPrenotazione(42);
        prenotazione.setDisponibilita(slot);
        prenotazione.setStato(StatoPrenotazione.RICHIESTA);
        prenotazione.setDataOraRichiesta(slot.getDataOraInizio().minusHours(1));
        prenotazione.setPrezzo(new BigDecimal("50.00"));
        // Medico effettivo differente per dimostrare che il DTO usa il proprietario dello slot.
        Medico effettivo = new Medico();
        Utente other = new Utente();
        other.setIdUtente(99);
        effettivo.setUtente(other);
        prenotazione.setMedicoEffettivo(effettivo);
        Paziente paziente = new Paziente();
        paziente.setUtente(owner);
        paziente.setCodiceFiscale("SENSIBILE");
        prenotazione.setPaziente(paziente);
        return prenotazione;
    }

    @Test
    void uc20ReportsSelectionAndReturnsOnlySafeAcknowledgement() throws IOException {
        FakeHttpExchange exchange = request("POST", "/api/prenotazioni/segnala-indisponibilita",
                "{\"medicoId\":7,\"idPrenotazioni\":[42,43]}");
        assertResponse(exchange, 200);
        assertEquals(1, service.reportCalls);
        assertEquals(7, service.reportDoctor);
        assertEquals(List.of(42, 43), service.reportIds);
        JsonNode json = mapper.readTree(exchange.body());
        assertEquals(2, json.size());
        assertTrue(json.get("indisponibilitaSegnalata").asBoolean());
        assertEquals(42, json.get("idPrenotazioni").get(0).asInt());
        assertTrue(exchange.inputClosed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{bad", "{\"medicoId\":7}",
            "{\"medicoId\":7,\"idPrenotazioni\":[]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[42,42]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[null]}",
            "{\"medicoId\":0,\"idPrenotazioni\":[42]}",
            "{\"medicoId\":\"7\",\"idPrenotazioni\":[42]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[42.0]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[\"42\"]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[true]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[0]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[-1]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[2147483648]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[42],\"extra\":true}",
            "{\"medicoId\":7,\"medicoId\":8,\"idPrenotazioni\":[42]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[42],\"idPrenotazioni\":[43]}",
            "{\"medicoId\":7,\"idPrenotazioni\":[42]} {}"})
    void uc20InvalidJsonNeverCallsService(String body) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/segnala-indisponibilita", body), 400);
        assertEquals(0, service.reportCalls);
    }

    @Test
    void uc20DeskListAndAgendaExposeFlagWithoutSensitiveData() throws IOException {
        it.medbook.dto.SegnalazionePendenteResponse pending = new it.medbook.dto.SegnalazionePendenteResponse();
        pending.setIdPaziente(9); pending.setNomePaziente("Paola"); pending.setCognomePaziente("Prova");
        pending.setIdPrenotazione(42); pending.setStato(StatoPrenotazione.CONFERMATA);
        pending.setIdMedicoTitolare(7); pending.setIdMedicoEffettivo(8);
        pending.setNomeMedicoTitolare("Mario"); pending.setNomeMedicoEffettivo("Anna");
        pending.setIdPrestazione(11); pending.setNomePrestazione("Visita");
        pending.setIdStanza(3); pending.setNomeStanza("Sala");
        pending.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0));
        pending.setIndisponibilitaSegnalata(true); service.pending = List.of(pending);
        FakeHttpExchange desk = request("GET", "/api/prenotazioni/segnalazioni-indisponibilita", "");
        assertResponse(desk, 200);
        JsonNode json = mapper.readTree(desk.body()).get(0);
        assertEquals(9, json.get("idPaziente").asInt());
        assertEquals("Paola", json.get("nomePaziente").asText());
        assertEquals("Prova", json.get("cognomePaziente").asText());
        assertEquals(7, json.get("idMedicoTitolare").asInt());
        assertEquals(8, json.get("idMedicoEffettivo").asInt());
        assertEquals("2030-01-15T09:00:00", json.get("dataOraInizio").asText());
        assertTrue(json.get("indisponibilitaSegnalata").asBoolean());
        for (String sensitive : List.of("password", "email", "codiceFiscale", "telefono", "utente")) {
            assertFalse(desk.body().contains(sensitive));
        }
        service.pending = List.of();
        assertEquals("[]", request("GET", "/api/prenotazioni/segnalazioni-indisponibilita", "").body());
        AppuntamentoAgendaResponse appointment = new AppuntamentoAgendaResponse();
        appointment.setIndisponibilitaSegnalata(true); service.agenda = List.of(appointment);
        FakeHttpExchange agenda = request("GET", "/api/prenotazioni/agenda?medicoId=7", "");
        assertResponse(agenda, 200);
        assertTrue(mapper.readTree(agenda.body()).get(0).get("indisponibilitaSegnalata").asBoolean());
    }

    @Test
    void uc20MethodsQueriesAndErrorsDoNotCollideWithExistingRoutes() throws IOException {
        FakeHttpExchange post = request("GET", "/api/prenotazioni/segnala-indisponibilita", "");
        assertResponse(post, 405); assertEquals("POST", post.getResponseHeaders().getFirst("Allow"));
        FakeHttpExchange get = request("POST", "/api/prenotazioni/segnalazioni-indisponibilita", "{}");
        assertResponse(get, 405); assertEquals("GET", get.getResponseHeaders().getFirst("Allow"));
        assertResponse(request("GET", "/api/prenotazioni/segnalazioni-indisponibilita?stato=RICHIESTA", ""), 400);
        assertResponse(request("POST", "/api/prenotazioni/segnala-indisponibilita?extra=1",
                "{\"medicoId\":7,\"idPrenotazioni\":[42]}"), 400);
        assertEquals(0, service.reportCalls); assertEquals(0, service.pendingCalls);
        service.error = new RisorsaNonTrovataException("Prenotazione non trovata");
        assertResponse(request("POST", "/api/prenotazioni/segnala-indisponibilita",
                "{\"medicoId\":7,\"idPrenotazioni\":[42]}"), 404);
        service.error = new ConflittoPrenotazioneException("Conflitto");
        assertResponse(request("POST", "/api/prenotazioni/segnala-indisponibilita",
                "{\"medicoId\":7,\"idPrenotazioni\":[42]}"), 409);
        service.error = null; service.sqlError = new SQLException("password SQL segreta");
        FakeHttpExchange failure = request("POST", "/api/prenotazioni/segnala-indisponibilita",
                "{\"medicoId\":7,\"idPrenotazioni\":[42]}");
        assertResponse(failure, 500); assertEquals("{\"error\":\"Errore interno del server.\"}", failure.body());
        assertResponse(request("GET", "/api/prenotazioni/segnalazioni-indisponibilita", ""), 500);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=StatoPrenotazione.class,names={"RICHIESTA","CONFERMATA"})
    void uc17ReturnsSafeResponseAndRoutesWithoutChangingExistingEndpoints(StatoPrenotazione state) throws IOException {
        service.replacementState=state;
        var exchange = request("POST", "/api/prenotazioni/42/sostituisci-medico", replacementBody().replace("CONFERMATA",state.name()));
        assertResponse(exchange, 200);
        var row = mapper.readTree(exchange.body());
        assertEquals(6, row.size()); assertEquals("CONFERMATA",row.get("stato").asText());
        assertEquals(7,row.get("idOperatore").asInt());assertEquals(42, row.get("idPrenotazione").asInt());
        assertEquals(8, row.get("idMedicoEffettivo").asInt()); assertFalse(row.get("indisponibilitaSegnalata").asBoolean());
        assertEquals("2030-01-15T08:00:00", row.get("dataOraGestione").asText()); assertEquals(1, service.replacementCalls);
    }

    private static String replacementBody() {
        return "{\"idOperatore\":7,\"idMedicoSostituto\":8,\"statoAtteso\":\"CONFERMATA\",\"idMedicoIndisponibile\":9}";
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "null", "[]", "{", "{}",
        "{\"idOperatore\":7,\"idMedicoSostituto\":8,\"statoAtteso\":\"CONFERMATA\"}",
        "{\"idOperatore\":7,\"idMedicoSostituto\":0,\"statoAtteso\":\"CONFERMATA\",\"idMedicoIndisponibile\":9}",
        "{\"idOperatore\":\"7\",\"idMedicoSostituto\":8,\"statoAtteso\":\"CONFERMATA\",\"idMedicoIndisponibile\":9}",
        "{\"idOperatore\":7,\"idOperatore\":7,\"idMedicoSostituto\":8,\"statoAtteso\":\"CONFERMATA\",\"idMedicoIndisponibile\":9}"})
    void uc17RejectsInvalidBodiesBeforeService(String body) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico", body), 400);
        assertEquals(0, service.replacementCalls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1", "abc", "2147483648", "%34%32"})
    void uc17RejectsInvalidPathIds(String id) throws IOException {
        assertResponse(request("POST", "/api/prenotazioni/"+id+"/sostituisci-medico", replacementBody()), 400);
        assertEquals(0, service.replacementCalls);
    }

    @Test
    void uc17RejectsUnknownFieldsTrailingTokensQueryAndWrongStates() throws IOException {
        for (String body : List.of(replacementBody()+" {}", replacementBody().replace("CONFERMATA", "ANNULLATA"),
                replacementBody().replace("}", ",\"extra\":1}"), replacementBody().replace(":8", ":8.5"))) {
            assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico", body), 400);
        }
        assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico?x=1", replacementBody()), 400);
        assertEquals(0, service.replacementCalls);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH"})
    void uc17AllowsOnlyPost(String method) throws IOException {
        var exchange = request(method, "/api/prenotazioni/42/sostituisci-medico", replacementBody());
        assertResponse(exchange,405); assertEquals("POST", exchange.getResponseHeaders().getFirst("Allow"));
        assertEquals(0, service.replacementCalls);
    }

    @Test
    void uc17MapsDomainAndSqlErrors() throws IOException {
        service.error = new it.medbook.exception.OperazioneNonAutorizzataException("Ruolo errato");
        assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico", replacementBody()),403);
        service.error = new RisorsaNonTrovataException("Non trovato");
        assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico", replacementBody()),404);
        service.error = new ConflittoPrenotazioneException("Conflitto");
        assertResponse(request("POST", "/api/prenotazioni/42/sostituisci-medico", replacementBody()),409);
        service.error = null; service.sqlError = new SQLException("Dettaglio SQL riservato");
        var exchange=request("POST", "/api/prenotazioni/42/sostituisci-medico", replacementBody());
        assertResponse(exchange,500); assertFalse(exchange.body().contains("riservato"));
    }

    @ParameterizedTest
    @ValueSource(strings={"RICHIESTA","CONFERMATA","RIFIUTATA","ANNULLATA","EFFETTUATA","PAZIENTE_ASSENTE","NON_EROGATA","TUTTE"})
    void consultationStateIsValidatedAndForwarded(String state) throws IOException {
        var dto=new RichiestaPrenotazioneResponse(); dto.setStato(StatoPrenotazione.RIFIUTATA);
        service.richieste=List.of(dto);
        FakeHttpExchange response=request("GET","/api/prenotazioni/richieste?stato="+state+"&medicoId=7&data=2030-01-15","");
        assertResponse(response,200);
        assertEquals(state,service.statoConsultazione);
        assertEquals(1,service.searchCalls);
        assertEquals(0,service.calls);
        assertEquals("RIFIUTATA",mapper.readTree(response.body()).get(0).get("stato").asText());
    }

    private static class FakeService extends PrenotazioneService {
        private int patientListCalls;
        private Integer patientListId;
        private StatoPrenotazione patientListState;
        private LocalDate patientListData;
        private LocalDate patientListDa;
        private LocalDate patientListA;
        private List<AppuntamentoPazienteResponse> patientList = List.of();

        @Override
        public List<AppuntamentoPazienteResponse> getAppuntamentiPaziente(Integer patient, StatoPrenotazione state,
                LocalDate data, LocalDate da, LocalDate a) throws SQLException {
            patientListCalls++; patientListId = patient; patientListState = state;
            patientListData = data; patientListDa = da; patientListA = a;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return patientList;
        }


        private int agendaCalls;
        private Integer agendaMedico;
        private LocalDate agendaData;
        private LocalDate agendaDa;
        private LocalDate agendaA;
        private List<AppuntamentoAgendaResponse> agenda = List.of();

        @Override
        public List<AppuntamentoAgendaResponse> getAgenda(Integer medico, LocalDate data, LocalDate da, LocalDate a) throws SQLException {
            agendaCalls++; agendaMedico = medico; agendaData = data; agendaDa = da; agendaA = a;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return agenda;
        }


        private int cancelCalls;
        private List<Integer> cancelIds;
        private StatoPrenotazione expected;
        private AnnullamentoPrenotazioneResponse cancelled;

        @Override
        public AnnullamentoPrenotazioneResponse annullaAppuntamento(Integer booking, Integer operator, StatoPrenotazione expected)
                throws SQLException {
            cancelCalls++;
            cancelIds = List.of(booking, operator);
            this.expected = expected;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return cancelled;
        }

        private int patientCancelCalls;
        private List<Integer> patientCancelIds;
        private StatoPrenotazione patientExpected;
        private AnnullamentoPazienteResponse patientCancelled;

        @Override
        public AnnullamentoPazienteResponse annullaAppuntamentoPaziente(Integer booking, Integer operator, StatoPrenotazione expected)
                throws SQLException {
            patientCancelCalls++;
            patientCancelIds = List.of(booking, operator);
            this.patientExpected = expected;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return patientCancelled;
        }

        private int confirmCalls;
        private List<Integer> confirmIds;
        private ConfermaPrenotazioneResponse confirmed;

        @Override
        public ConfermaPrenotazioneResponse confermaAppuntamento(Integer prenotazione, Integer operatore)
                throws SQLException {
            confirmCalls++;
            confirmIds = List.of(prenotazione, operatore);
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return confirmed;
        }

        private int rejectCalls;
        private List<Integer> rejectIds;
        private RifiutoPrenotazioneResponse rejected;

        @Override
        public RifiutoPrenotazioneResponse rifiutaAppuntamento(Integer prenotazione, Integer operatore)
                throws SQLException {
            rejectCalls++;
            rejectIds = List.of(prenotazione, operatore);
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return rejected;
        }

        private String statoConsultazione;
        @Override public List<RichiestaPrenotazioneResponse> getRichieste(Integer medico,Integer prestazione,LocalDate da,LocalDate a,String stato) throws SQLException {
            statoConsultazione=stato;
            return getRichieste(medico,prestazione,da,a);
        }
        private List<RichiestaPrenotazioneResponse> richieste = List.of();
        private int searchCalls;
        private Integer medico;
        private Integer prestazione;
        private LocalDate da;
        private LocalDate a;

        @Override
        public List<RichiestaPrenotazioneResponse> getRichieste(Integer medico, Integer prestazione,
                                                             LocalDate da, LocalDate a) throws SQLException {
            searchCalls++;
            this.medico = medico;
            this.prestazione = prestazione;
            this.da = da;
            this.a = a;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return richieste;
        }

        private int calls;
        private List<Integer> ids;
        private Prenotazione result;
        private RuntimeException error;
        private SQLException sqlError;

        private int reportCalls;
        private int pendingCalls;
        private Integer reportDoctor;
        private List<Integer> reportIds;
        private List<it.medbook.dto.SegnalazionePendenteResponse> pending = List.of();

        @Override
        public it.medbook.dto.SegnalazioneIndisponibilitaResponse segnalaIndisponibilita(
                Integer doctor, List<Integer> ids) throws SQLException {
            reportCalls++; reportDoctor = doctor; reportIds = ids;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            it.medbook.dto.SegnalazioneIndisponibilitaResponse response = new it.medbook.dto.SegnalazioneIndisponibilitaResponse();
            response.setIdPrenotazioni(ids); response.setIndisponibilitaSegnalata(true);
            return response;
        }

        @Override
        public List<it.medbook.dto.SegnalazionePendenteResponse> getSegnalazioniPendenti() throws SQLException {
            pendingCalls++;
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return pending;
        }

        FakeService() { super(null); }

        @Override
        public Prenotazione richiediAppuntamento(Integer paziente, Integer disponibilita) throws SQLException {
            calls++;
            ids = List.of(paziente, disponibilita);
            if (error != null) { throw error; }
            if (sqlError != null) { throw sqlError; }
            return result;
        }
        private int replacementCalls;
        private StatoPrenotazione replacementState=StatoPrenotazione.CONFERMATA;
        @Override public it.medbook.dto.SostituzioneMedicoResponse sostituisciMedico(Integer id,
                Integer operator, Integer substitute, StatoPrenotazione state, Integer unavailable) throws SQLException {
            replacementCalls++;
            assertEquals(42, id); assertEquals(7, operator); assertEquals(8, substitute);
            assertEquals(9, unavailable); assertEquals(replacementState, state);
            if (error != null) { throw error; } if (sqlError != null) { throw sqlError; }
            var response = new it.medbook.dto.SostituzioneMedicoResponse();
            response.setIdPrenotazione(id); response.setStato(StatoPrenotazione.CONFERMATA); response.setIdMedicoEffettivo(substitute);
            response.setIdOperatore(operator); response.setDataOraGestione(LocalDateTime.of(2030,1,15,8,0));
            return response;
        }
    }

    private static class FakeHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private boolean inputClosed;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private boolean closed;
        private boolean outputClosed;
        private int status;
        private long responseLength;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputClosed = true;
                super.close();
            }
        };
        private InputStream input = new ByteArrayInputStream(new byte[0]);
        private OutputStream output = responseBody;

        FakeHttpExchange(String method, String path, String body) {
            this.method = method;
            this.uri = URI.create(path);
            this.input = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    inputClosed = true;
                    super.close();
                }
            };
        }

        String body() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() { return requestHeaders; }

        @Override
        public Headers getResponseHeaders() { return responseHeaders; }

        @Override
        public URI getRequestURI() { return uri; }

        @Override
        public String getRequestMethod() { return method; }

        @Override
        public HttpContext getHttpContext() { return null; }

        @Override
        public void close() { closed = true; }

        @Override
        public InputStream getRequestBody() { return input; }

        @Override
        public OutputStream getResponseBody() { return output; }

        @Override
        public void sendResponseHeaders(int status, long responseLength) {
            this.status = status;
            this.responseLength = responseLength;
        }

        @Override
        public InetSocketAddress getRemoteAddress() { return null; }

        @Override
        public int getResponseCode() { return status; }

        @Override
        public InetSocketAddress getLocalAddress() { return null; }

        @Override
        public String getProtocol() { return "HTTP/1.1"; }

        @Override
        public Object getAttribute(String name) { return attributes.get(name); }

        @Override
        public void setAttribute(String name, Object value) { attributes.put(name, value); }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public HttpPrincipal getPrincipal() { return null; }
    }
}
