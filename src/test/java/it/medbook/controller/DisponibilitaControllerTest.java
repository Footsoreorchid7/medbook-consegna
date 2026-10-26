package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import it.medbook.model.Disponibilita;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoDisponibilitaException;

import it.medbook.model.Medico;
import it.medbook.model.Utente;
import it.medbook.model.Prestazione;
import it.medbook.model.Stanza;
import it.medbook.service.DisponibilitaService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisponibilitaControllerTest {
    private final FakeDisponibilitaService service = new FakeDisponibilitaService();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DisponibilitaController controller = new DisponibilitaController(service, mapper);
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final String BODY = """
            {"idMedico":7,"dataOraInizio":"2026-10-08T09:00:00",
             "dataOraFine":"2026-10-08T12:00:00"}
            """;

    @Test
    void getReturnsServiceSlotsAndIsoDates() throws IOException {
        service.slots = List.of(slot(start));
        FakeHttpExchange exchange = request("GET", "?medicoId=7", "");
        assertResponse(exchange, 200);
        assertEquals(Integer.valueOf(7), service.id);
        assertEquals(1, service.calls);
        assertEquals(1, mapper.readTree(exchange.body()).size());
        assertEquals("2026-10-08T09:00:00",
                mapper.readTree(exchange.body()).get(0).get("dataOraInizio").asText());
        assertEquals(1, mapper.readTree(exchange.body()).get(0).get("idDisponibilita").asInt());
        assertSafeSlot(mapper.readTree(exchange.body()).get(0), service.slots.getFirst());
    }

    @Test
    void getDecodesQueryParameter() throws IOException {
        assertResponse(request("GET", "?%6DedicoId=%37", ""), 200);
        assertEquals(Integer.valueOf(7), service.id);
    }

    @Test
    void getRejectsMissingId() throws IOException { assertInvalidQuery(""); }

    @Test
    void getRejectsEmptyId() throws IOException { assertInvalidQuery("?medicoId="); }

    @Test
    void getRejectsNonNumericId() throws IOException { assertInvalidQuery("?medicoId=abc"); }

    @Test
    void getRejectsOverflowId() throws IOException { assertInvalidQuery("?medicoId=2147483648"); }

    @Test
    void getRejectsUnknownOrDuplicateParameters() throws IOException {
        for (String query : List.of("?other=7", "?medicoId=7&other=1", "?medicoId=7&medicoId=8")) {
            assertInvalidQuery(query);
        }
    }

    @Test
    void getReturnsServiceValidationMessage() throws IOException {
        service.validationError = new IllegalArgumentException("ID medico non valido.");
        FakeHttpExchange exchange = request("GET", "?medicoId=0", "");
        assertResponse(exchange, 400);
        assertError(exchange, service.validationError.getMessage());
        assertEquals(1, service.calls);
    }

    @Test
    void getReturnsGenericDatabaseError() throws IOException {
        service.sqlError = new SQLException("Dettaglio SQL riservato.");
        FakeHttpExchange exchange = request("GET", "?medicoId=7", "");
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @Test
    void postReturnsCreatedSlotsAndPassesAllArguments() throws IOException {
        service.slots = List.of(slot(start), slot(start.plusHours(1)), slot(start.plusHours(2)));
        FakeHttpExchange exchange = request("POST", "", BODY);
        assertResponse(exchange, 201);
        assertEquals(1, service.calls);
        assertEquals(Integer.valueOf(7), service.id);
        assertEquals(start, service.start);
        assertEquals(start.plusHours(3), service.end);
        assertEquals(3, mapper.readTree(exchange.body()).size());
        for (int i = 0; i < 3; i++) {
            assertSafeSlot(mapper.readTree(exchange.body()).get(i), service.slots.get(i));
        }
        assertEquals("2026-10-08T12:00:00",
                mapper.readTree(exchange.body()).get(2).get("dataOraFine").asText());
        assertTrue(exchange.inputClosed);
    }

    @Test
    void postRejectsMalformedJsonWithoutCallingService() throws IOException {
        assertInvalidBody("{broken");
    }

    @Test
    void postRejectsNonDeserializableDataWithoutCallingService() throws IOException {
        for (String body : List.of("[]", "null", "", "{\"idMedico\":{}}",
                BODY.replace("2026-10-08T09:00:00", "invalid-date"),
                BODY.replace("7", "7.5"), BODY + " {}")) {
            assertInvalidBody(body);
        }
    }

    @Test
    void postReturnsServiceIdValidationMessage() throws IOException {
        service.validationError = new IllegalArgumentException("ID medico non valido.");
        FakeHttpExchange exchange = request("POST", "", BODY.replace("7", "0"));
        assertResponse(exchange, 400);
        assertError(exchange, service.validationError.getMessage());
        assertEquals(Integer.valueOf(0), service.id);
        assertEquals(1, service.calls);
    }

    @Test
    void postReturnsServiceIntervalValidationMessage() throws IOException {
        service.validationError = new IllegalArgumentException("Intervallo non valido.");
        FakeHttpExchange exchange = request("POST", "", BODY.replace("12:00:00", "09:30:00"));
        assertResponse(exchange, 400);
        assertError(exchange, service.validationError.getMessage());
        assertEquals(start.plusMinutes(30), service.end);
        assertEquals(1, service.calls);
    }

    @Test
    void postReturnsGenericDatabaseError() throws IOException {
        service.sqlError = new SQLException("Dettaglio SQL riservato.");
        FakeHttpExchange exchange = request("POST", "", BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
        assertTrue(exchange.inputClosed);
    }

    @Test
    void unsupportedMethodsReturn405AndAllowWithoutCallingService() throws IOException {
        for (String method : List.of("PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")) {
            FakeHttpExchange exchange = request(method, "", "");
            assertResponse(exchange, 405);
            assertEquals("GET, POST", exchange.responseHeaders.getFirst("Allow"));
            assertError(exchange, "Metodo non consentito.");
        }
        assertEquals(0, service.calls);
    }

    private static final String CONFIG_BODY = "{\"idPrestazione\":11,\"idStanza\":3}";

    @Test
    void putConfiguresAndReturnsSafeResponse() throws IOException {
        Disponibilita configured = slot(start);
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(11);
        prestazione.setNome("Visita");
        Stanza stanza = new Stanza();
        stanza.setIdStanza(3);
        stanza.setNome("Studio 3");
        configured.configura(prestazione, stanza);
        service.configured = configured;
        FakeHttpExchange exchange = request("PUT", "/1/configurazione", CONFIG_BODY);
        assertResponse(exchange, 200);
        assertEquals(List.of(1, 11, 3), service.configIds);
        assertEquals(1, service.calls);
        JsonNode json = mapper.readTree(exchange.body());
        assertEquals(1, json.get("idDisponibilita").asInt());
        assertEquals(7, json.get("idMedico").asInt());
        assertEquals(11, json.get("idPrestazione").asInt());
        assertEquals("Visita", json.get("nomePrestazione").asText());
        assertEquals(3, json.get("idStanza").asInt());
        assertEquals("Studio 3", json.get("nomeStanza").asText());
        assertTrue(json.get("configurata").asBoolean());
        assertNoSensitiveData(json);
        assertTrue(exchange.inputClosed);
    }

    @Test
    void putRejectsMalformedMissingAndWrongTypesWithoutServiceCall() throws IOException {
        for (String body : List.of("{bad", "{}", "null", "[]", "",
                "{\"idPrestazione\":11}", "{\"idStanza\":3}",
                "{\"idPrestazione\":\"11\",\"idStanza\":3}",
                "{\"idPrestazione\":11,\"idStanza\":false}",
                "{\"idPrestazione\":11.5,\"idStanza\":3}",
                CONFIG_BODY + " {}", CONFIG_BODY.replace("11", "0"),
                CONFIG_BODY.replace("3", "-1"))) {
            assertResponse(request("PUT", "/1/configurazione", body), 400);
        }
        assertEquals(0, service.calls);
    }

    @Test
    void putRejectsInvalidPathIds() throws IOException {
        for (String id : List.of("abc", "0", "-1", "2147483648", "%31", "+1")) {
            assertResponse(request("PUT", "/" + id + "/configurazione", CONFIG_BODY), 400);
        }
        assertEquals(0, service.calls);
    }

    @Test
    void putMapsMissingResourcesTo404() throws IOException {
        for (String message : List.of("Disponibilità non trovata.", "Prestazione non trovata.", "Stanza non trovata.")) {
            service.configError = new RisorsaNonTrovataException(message);
            FakeHttpExchange exchange = request("PUT", "/1/configurazione", CONFIG_BODY);
            assertResponse(exchange, 404);
            assertError(exchange, message);
        }
    }

    @Test
    void putMapsConfigurationConflictsTo409() throws IOException {
        for (String message : List.of("Medico non abilitato.", "Stanza occupata.", "Slot già configurato.")) {
            service.configError = new ConflittoConfigurazioneException(message);
            FakeHttpExchange exchange = request("PUT", "/1/configurazione", CONFIG_BODY);
            assertResponse(exchange, 409);
            assertError(exchange, message);
        }
    }

    @Test
    void putMapsServiceValidationTo400() throws IOException {
        service.validationError = new IllegalArgumentException("ID non valido.");
        FakeHttpExchange exchange = request("PUT", "/1/configurazione", CONFIG_BODY);
        assertResponse(exchange, 400);
        assertError(exchange, "ID non valido.");
    }

    @Test
    void putMapsSqlErrorToGeneric500() throws IOException {
        service.sqlError = new SQLException("Dettaglio tecnico riservato.");
        FakeHttpExchange exchange = request("PUT", "/1/configurazione", CONFIG_BODY);
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @Test
    void configurationRouteAllowsOnlyPut() throws IOException {
        for (String method : List.of("GET", "POST", "DELETE", "PATCH")) {
            FakeHttpExchange exchange = request(method, "/1/configurazione", CONFIG_BODY);
            assertResponse(exchange, 405);
            assertEquals("PUT", exchange.responseHeaders.getFirst("Allow"));
        }
        assertEquals(0, service.calls);
    }

    @Test
    void unknownRoutesReturn404WithoutCallingService() throws IOException {
        for (String path : List.of("/1/orario/extra", "/1/configurazione/extra", "/", "/unknown/other")) {
            assertResponse(request("GET", path, ""), 404);
        }
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @CsvSource(value = {"'',null,null,null", "?medicoId=7,7,null,null",
            "?prestazioneId=11,null,11,null", "?data=2026-10-08,null,null,2026-10-08",
            "?medicoId=7&prestazioneId=11&data=2026-10-08,7,11,2026-10-08",
            "?%6DedicoId=%37,7,null,null"}, nullValues = "null")
    void uc04AcceptsOptionalFilters(String query, Integer medico, Integer prestazione, LocalDate data)
            throws IOException {
        FakeHttpExchange exchange = request("GET", "/prenotabili" + query, "");
        assertResponse(exchange, 200);
        assertEquals("[]", exchange.body());
        assertEquals(medico, service.id);
        assertEquals(prestazione, service.prestazioneId);
        assertEquals(data, service.data);
        assertEquals(1, service.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"medicoId=7&medicoId=7", "medicoId=7&%6DedicoId=8",
            "prestazioneId=1&prestazioneId=2", "data=2026-10-08&data=2026-10-08",
            "unknown=1", "medicoId=", "prestazioneId=", "data=", "medicoId",
            "medicoId=abc", "prestazioneId=1.5", "medicoId=2147483648",
            "prestazioneId=2147483648", "medicoId=0", "prestazioneId=-1",
            "medicoId=%207", "medicoId=%2B7", "data=2026-02-30", "data=2026-2-03",
            "data=08-10-2026", "data=2026-10-08T09:00:00", "medicoId=7&", "medicoId=%FF"})
    void uc04RejectsInvalidFiltersWithoutServiceCall(String query) throws IOException {
        FakeHttpExchange exchange = request("GET", "/prenotabili?" + query, "");
        assertResponse(exchange, 400);
        assertError(exchange, "Parametri di ricerca non validi.");
        assertEquals(0, service.calls);
    }

    @Test
    void uc04ReturnsSafePatientDto() throws IOException {
        Disponibilita slot = slot(start);
        slot.getMedico().getUtente().setNome("Mario");
        slot.getMedico().getUtente().setCognome("Rossi");
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(11);
        prestazione.setNome("Visita cardiologica");
        prestazione.setDurata(30);
        prestazione.setPrezzo(new BigDecimal("75.50"));
        Stanza stanza = new Stanza();
        stanza.setIdStanza(3);
        stanza.setNome("Stanza interna");
        slot.configura(prestazione, stanza);
        service.slots = List.of(slot);
        FakeHttpExchange exchange = request("GET", "/prenotabili", "");
        assertResponse(exchange, 200);
        JsonNode json = mapper.readTree(exchange.body());
        assertEquals(1, json.size());
        JsonNode item = json.get(0);
        assertEquals(10, item.size());
        assertEquals(1, item.get("idDisponibilita").asInt());
        assertEquals(7, item.get("idMedico").asInt());
        assertEquals("Mario", item.get("nomeMedico").asText());
        assertEquals("Rossi", item.get("cognomeMedico").asText());
        assertEquals("2026-10-08T09:00:00", item.get("dataOraInizio").asText());
        assertEquals("2026-10-08T10:00:00", item.get("dataOraFine").asText());
        assertEquals(11, item.get("idPrestazione").asInt());
        assertEquals("Visita cardiologica", item.get("nomePrestazione").asText());
        assertEquals(30, item.get("durataPrestazione").asInt());
        assertEquals(0, new BigDecimal("75.50").compareTo(item.get("prezzoPrestazione").decimalValue()));
        assertFalse(item.has("configurata"));
        assertFalse(item.has("stanza"));
        assertFalse(item.has("idStanza"));
        assertFalse(item.has("nomeStanza"));
        assertNoSensitiveData(item);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD"})
    void uc04AllowsOnlyGet(String method) throws IOException {
        FakeHttpExchange exchange = request(method, "/prenotabili", "");
        assertResponse(exchange, 405);
        assertEquals("GET", exchange.responseHeaders.getFirst("Allow"));
        assertError(exchange, "Metodo non consentito.");
        assertEquals(0, service.calls);
    }

    @Test
    void uc04ReturnsGenericSqlError() throws IOException {
        service.sqlError = new SQLException("Dettagli DB riservati.");
        FakeHttpExchange exchange = request("GET", "/prenotabili", "");
        assertResponse(exchange, 500);
        assertError(exchange, "Errore interno del server.");
        assertFalse(exchange.body().contains(service.sqlError.getMessage()));
    }

    @Test
    void uc04ReturnsServiceValidationError() throws IOException {
        service.validationError = new IllegalArgumentException("ID non valido.");
        FakeHttpExchange exchange = request("GET", "/prenotabili", "");
        assertResponse(exchange, 400);
        assertError(exchange, "ID non valido.");
    }

    @Test
    void uc04AcceptsPastDateAndReturnsEmptyArray() throws IOException {
        FakeHttpExchange exchange = request("GET", "/prenotabili?data=2000-01-01", "");
        assertResponse(exchange, 200);
        assertEquals("[]", exchange.body());
        assertEquals(LocalDate.of(2000, 1, 1), service.data);
    }

    private static final String ORARIO_BODY = "{\"idMedico\":7,\"dataOraInizio\":\"2030-01-15T09:00:00\",\"dataOraFine\":\"2030-01-15T10:00:00\"}";

    @Test
    void uc19PutReturnsUpdatedSafeSlotAndOnlyCallsEdit() throws IOException {
        service.configured = slot(LocalDateTime.of(2030, 1, 15, 9, 0));
        FakeHttpExchange exchange = request("PUT", "/1/orario", ORARIO_BODY);
        assertResponse(exchange, 200);
        assertSafeSlot(mapper.readTree(exchange.body()), service.configured);
        assertEquals(List.of(1, 7), service.mutationIds);
        assertEquals(service.configured.getDataOraInizio(), service.start);
        assertEquals(service.configured.getDataOraFine(), service.end);
        assertEquals(1, service.editCalls); assertEquals(0, service.deleteCalls); assertEquals(0, service.calls);
    }

    @Test
    void uc19DeleteReturnsRealNoContent() throws IOException {
        FakeHttpExchange exchange = request("DELETE", "/1?medicoId=7", "");
        assertEquals(204, exchange.status); assertEquals(-1, exchange.responseLength);
        assertEquals("", exchange.body()); assertTrue(exchange.closed);
        assertNull(exchange.responseHeaders.getFirst("Content-Type"));
        assertEquals(List.of(1, 7), service.mutationIds);
        assertEquals(1, service.deleteCalls); assertEquals(0, service.editCalls); assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{bad", "{\"idMedico\":7}",
            ORARIO_BODY + " {}", ORARIO_BODY + " trailing"})
    void uc19MalformedMissingBodiesNeverCallService(String body) throws IOException {
        assertResponse(request("PUT", "/1/orario", body), 400); assertEquals(0, service.editCalls);
    }

    @Test
    void uc19RejectsUnknownDuplicateWrongTypesAndFractions() throws IOException {
        for (String body : List.of(ORARIO_BODY.replace("7", "0"), ORARIO_BODY.replace("7", "-1"),
                ORARIO_BODY.replace("7", "7.0"), ORARIO_BODY.replace("7", "\"7\""),
                ORARIO_BODY.replace("7", "2147483648"), ORARIO_BODY.replace("7", "null"),
                ORARIO_BODY.replace("{", "{\"idStanza\":3,"), ORARIO_BODY.replace("{", "{\"idMedico\":8,"),
                ORARIO_BODY.replace("{", "{\"dataOraInizio\":\"2030-01-15T08:00:00\","),
                ORARIO_BODY.replace("09:00:00", "09:00:00.001"), ORARIO_BODY.replace("10:00:00", "10:00:00.001"),
                ORARIO_BODY.replace("2030-01-15", "2030-02-30"))) {
            assertResponse(request("PUT", "/1/orario", body), 400);
        }
        assertEquals(0, service.editCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "%31", "+1", "2147483648"})
    void uc19InvalidPathIds(String id) throws IOException {
        assertResponse(request("PUT", "/" + id + "/orario", ORARIO_BODY), 400);
        assertResponse(request("DELETE", "/" + id + "?medicoId=7", ""), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "medicoId=", "medicoId", "medicoId=0", "medicoId=-1", "medicoId=abc",
            "medicoId=7&medicoId=8", "medicoId=7&%6DedicoId=8", "other=7", "medicoId=7&other=1",
            "medicoId=7&", "medicoId=%2B7", "medicoId=2147483648"})
    void uc19DeleteStrictQuery(String query) throws IOException {
        assertResponse(request("DELETE", "/1?" + query, ""), 400); assertEquals(0, service.deleteCalls);
    }

    @Test
    void uc19PutForbidsQueryAndDeleteAcceptsDecodedName() throws IOException {
        assertResponse(request("PUT", "/1/orario?medicoId=7", ORARIO_BODY), 400);
        FakeHttpExchange exchange = request("DELETE", "/1?%6DedicoId=7", "");
        assertEquals(204, exchange.status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH", "HEAD"})
    void uc19WrongMethodsHaveAllow(String method) throws IOException {
        FakeHttpExchange put = request(method, "/1/orario", "");
        assertResponse(put, 405); assertEquals("PUT", put.responseHeaders.getFirst("Allow"));
        FakeHttpExchange delete = request(method, "/1?medicoId=7", "");
        assertResponse(delete, 405); assertEquals("DELETE", delete.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.editCalls); assertEquals(0, service.deleteCalls);
    }

    @Test
    void uc19MapsAllErrorsWithoutLeakingSqlDetails() throws IOException {
        for (RuntimeException error : List.of(new IllegalArgumentException("Input."),
                new RisorsaNonTrovataException("Slot altrui."), new ConflittoDisponibilitaException("Conflitto."),
                new IllegalStateException("Riservato."))) {
            service.configError = error;
            int status = error instanceof IllegalArgumentException ? 400 : error instanceof RisorsaNonTrovataException ? 404
                    : error instanceof ConflittoDisponibilitaException ? 409 : 500;
            assertResponse(request("PUT", "/1/orario", ORARIO_BODY), status);
            assertResponse(request("DELETE", "/1?medicoId=7", ""), status);
        }
        service.configError = null; service.sqlError = new SQLException("SQL riservato.");
        for (FakeHttpExchange exchange : List.of(request("PUT", "/1/orario", ORARIO_BODY), request("DELETE", "/1?medicoId=7", ""))) {
            assertResponse(exchange, 500); assertError(exchange, "Errore interno del server.");
            assertFalse(exchange.body().contains(service.sqlError.getMessage()));
        }
    }

    private FakeHttpExchange request(String method, String query, String body) throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange(method, "/api/disponibilita" + query, body);
        controller.handle(exchange);
        return exchange;
    }

    private void assertInvalidQuery(String query) throws IOException {
        FakeHttpExchange exchange = request("GET", query, "");
        assertResponse(exchange, 400);
        assertError(exchange, "Parametro medicoId non valido.");
        assertEquals(0, service.calls);
    }

    private void assertInvalidBody(String body) throws IOException {
        FakeHttpExchange exchange = request("POST", "", body);
        assertResponse(exchange, 400);
        assertError(exchange, "Richiesta non valida.");
        assertEquals(0, service.calls);
        assertTrue(exchange.inputClosed);
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

    private Disponibilita slot(LocalDateTime beginning) {
        Utente utente = new Utente();
        utente.setIdUtente(7);
        utente.setPasswordHash("hash-fittizio-da-non-esporre");
        utente.setEmail("medico-test@example.invalid");
        Medico medico = new Medico();
        medico.setUtente(utente);
        Disponibilita slot = new Disponibilita();
        slot.setIdDisponibilita(1);
        slot.setDataOraInizio(beginning);
        slot.setDataOraFine(beginning.plusHours(1));
        slot.setMedico(medico);
        return slot;
    }

    private void assertSafeSlot(JsonNode json, Disponibilita slot) {
        assertEquals(9, json.size());
        assertEquals(slot.getIdDisponibilita().intValue(), json.get("idDisponibilita").asInt());
        assertEquals(7, json.get("idMedico").asInt());
        assertEquals(mapper.valueToTree(slot.getDataOraInizio()), json.get("dataOraInizio"));
        assertEquals(mapper.valueToTree(slot.getDataOraFine()), json.get("dataOraFine"));
        assertTrue(json.has("idPrestazione") && json.get("idPrestazione").isNull());
        assertTrue(json.has("nomePrestazione") && json.get("nomePrestazione").isNull());
        assertTrue(json.has("idStanza") && json.get("idStanza").isNull());
        assertTrue(json.has("nomeStanza") && json.get("nomeStanza").isNull());
        assertFalse(json.get("configurata").asBoolean());
        assertNoSensitiveData(json);
    }

    private void assertNoSensitiveData(JsonNode json) {
        assertFalse(json.has("medico"));
        assertFalse(json.has("utente"));
        assertFalse(json.toString().contains("passwordHash"));
        assertFalse(json.toString().contains("hash-fittizio-da-non-esporre"));
        assertFalse(json.toString().contains("email"));
        assertFalse(json.toString().contains("medico-test@example.invalid"));
    }

    @Test
    void configuredSlotReturnsOnlyResponseFieldsForGetAndPost() throws IOException {
        Disponibilita slot = slot(start);
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(11);
        prestazione.setNome("Visita cardiologica");
        Stanza stanza = new Stanza();
        stanza.setIdStanza(3);
        stanza.setNome("Ambulatorio 3");
        slot.configura(prestazione, stanza);
        service.slots = List.of(slot);

        for (String method : List.of("GET", "POST")) {
            FakeHttpExchange exchange = request(method,
                    method.equals("GET") ? "?medicoId=7" : "", method.equals("POST") ? BODY : "");
            assertResponse(exchange, method.equals("GET") ? 200 : 201);
            JsonNode json = mapper.readTree(exchange.body()).get(0);
            assertEquals(9, json.size());
            assertEquals(1, json.get("idDisponibilita").asInt());
            assertEquals(7, json.get("idMedico").asInt());
            assertEquals("2026-10-08T09:00:00", json.get("dataOraInizio").asText());
            assertEquals("2026-10-08T10:00:00", json.get("dataOraFine").asText());
            assertEquals(11, json.get("idPrestazione").asInt());
            assertEquals("Visita cardiologica", json.get("nomePrestazione").asText());
            assertEquals(3, json.get("idStanza").asInt());
            assertEquals("Ambulatorio 3", json.get("nomeStanza").asText());
            assertTrue(json.get("configurata").asBoolean());
            assertNoSensitiveData(json);
        }
    }

    private static class FakeDisponibilitaService extends DisponibilitaService {
        private int editCalls, deleteCalls;
        private List<Integer> mutationIds;
        @Override
        public Disponibilita modificaOrario(Integer slot, Integer doctor, LocalDateTime start, LocalDateTime end) throws SQLException {
            editCalls++; mutationIds = List.of(slot, doctor); this.start = start; this.end = end;
            if (configError != null) { throw configError; }
            checkErrors(); return configured;
        }
        @Override
        public void eliminaDisponibilita(Integer slot, Integer doctor) throws SQLException {
            deleteCalls++; mutationIds = List.of(slot, doctor);
            if (configError != null) { throw configError; }
            checkErrors();
        }


        private Integer prestazioneId;
        private LocalDate data;

        @Override
        public List<Disponibilita> getDisponibilitaPrenotabili(Integer medico, Integer prestazione,
                                                             LocalDate data) throws SQLException {
            calls++;
            id = medico;
            prestazioneId = prestazione;
            this.data = data;
            checkErrors();
            return slots;
        }

        private List<Disponibilita> slots = List.of();
        private int calls;
        private Integer id;
        private LocalDateTime start;
        private LocalDateTime end;
        private IllegalArgumentException validationError;
        private SQLException sqlError;
        private RuntimeException configError;
        private Disponibilita configured;
        private List<Integer> configIds;

        @Override
        public Disponibilita configuraDisponibilita(Integer id, Integer prestazione, Integer stanza)
                throws SQLException {
            calls++;
            configIds = List.of(id, prestazione, stanza);
            if (configError != null) { throw configError; }
            checkErrors();
            return configured;
        }

        FakeDisponibilitaService() { super(null); }

        @Override
        public List<Disponibilita> getDisponibilitaByMedico(Integer idMedico) throws SQLException {
            calls++;
            id = idMedico;
            checkErrors();
            return slots;
        }

        @Override
        public List<Disponibilita> creaDisponibilita(Integer idMedico, LocalDateTime start,
                                                   LocalDateTime end) throws SQLException {
            calls++;
            id = idMedico;
            this.start = start;
            this.end = end;
            checkErrors();
            return slots;
        }

        private void checkErrors() throws SQLException {
            if (validationError != null) { throw validationError; }
            if (sqlError != null) { throw sqlError; }
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
