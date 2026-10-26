package it.medbook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.PrenotazioneDeskResponse;
import it.medbook.model.*;
import it.medbook.repository.PrenotazioneRepository;
import it.medbook.service.PrenotazioneService;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** GET /api/prenotazioni/desk al confine del controller (ruolo verificato da AutorizzazioneHandler). */
class PrenotazioniDeskControllerTest {
    static class Fake extends PrenotazioneService {
        int calls; Integer medico; LocalDate data, da, a; Exception error; List<PrenotazioneDeskResponse> result = List.of();
        Fake() { super(new PrenotazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()))); }
        @Override public List<PrenotazioneDeskResponse> getPrenotazioniDesk(Integer medico, LocalDate data, LocalDate da, LocalDate a)
                throws SQLException {
            calls++; this.medico = medico; this.data = data; this.da = da; this.a = a;
            if (error instanceof SQLException e) { throw e; }
            if (error instanceof RuntimeException e) { throw e; }
            return result;
        }
    }

    final Fake service = new Fake();
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final PrenotazioneController controller = new PrenotazioneController(service, mapper);

    TestHttpExchange get(String uri) throws Exception { return invoke("GET", uri); }
    TestHttpExchange invoke(String method, String uri) throws Exception {
        var exchange = new TestHttpExchange(method, uri, ""); controller.handle(exchange);
        assertTrue(exchange.closed); assertEquals("application/json; charset=UTF-8", exchange.responseHeaders.getFirst("Content-Type"));
        return exchange;
    }

    @ParameterizedTest
    @CsvSource(value = {
            "data=2030-01-15,null,2030-01-15,null,null",
            "dataDa=2030-01-01&dataA=2030-01-31,null,null,2030-01-01,2030-01-31",
            "dataDa=2030-01-15&dataA=2030-01-15,null,null,2030-01-15,2030-01-15",
            "medicoId=7&data=2030-01-15,7,2030-01-15,null,null",
            "dataA=2030-02-28&medicoId=0007&dataDa=2030-02-01,7,null,2030-02-01,2030-02-28",
            "%64ata=2030-01-15,null,2030-01-15,null,null"}, nullValues = "null")
    void validFiltersReachServiceAndEmptyListIsArray(String query, Integer medico, LocalDate data, LocalDate da, LocalDate a)
            throws Exception {
        var exchange = get("/api/prenotazioni/desk?" + query);
        assertEquals(200, exchange.status); assertEquals("[]", exchange.body());
        assertEquals(1, service.calls); assertEquals(medico, service.medico); assertEquals(data, service.data);
        assertEquals(da, service.da); assertEquals(a, service.a);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?", "?medicoId=7", "?dataDa=2030-01-01", "?dataA=2030-01-31",
            "?dataDa=2030-01-01&dataA=2030-02-01", "?dataDa=2030-01-31&dataA=2030-01-01",
            "?data=2030-01-15&dataDa=2030-01-15&dataA=2030-01-15", "?data=2030-01-15&dataA=2030-01-15",
            "?data=2030-01-15&data=2030-01-15", "?data=2030-01-15&%64ata=2030-01-16",
            "?data=2030-01-15&medicoId=7&medicoId=7", "?data=2030-01-15&stato=CONFERMATA", "?data=2030-01-15&prestazioneId=1",
            "?data=2030-01-15&idPaziente=1", "?data=2030-01-15&unknown=1", "?data=2030-01-15&", "?data", "?data=",
            "?data=%20", "?data=2030-02-30", "?data=2030-1-15", "?data=15-01-2030", "?data=2030-01-15T00:00:00",
            "?data=%25ZZ", "?data=2030-01-15&medicoId=", "?data=2030-01-15&medicoId=0", "?data=2030-01-15&medicoId=-1",
            "?data=2030-01-15&medicoId=abc", "?data=2030-01-15&medicoId=%2B7", "?data=2030-01-15&medicoId=%207",
            "?data=2030-01-15&medicoId=2147483648", "?data=2030-01-15&medicoId=1.5"})
    void invalidFiltersReturn400WithoutServiceCall(String query) throws Exception {
        var exchange = get("/api/prenotazioni/desk" + query);
        assertEquals(400, exchange.status, query);
        assertEquals("Parametri di ricerca non validi.", mapper.readTree(exchange.body()).get("error").textValue());
        assertEquals(0, service.calls);
    }

    @Test
    void serviceValidationErrorsAreParameterErrors() throws Exception {
        service.error = new IllegalArgumentException("Periodo non rappresentabile da MySQL DATETIME.");
        var exchange = get("/api/prenotazioni/desk?data=9999-12-31");
        assertEquals(400, exchange.status);
        assertEquals("Parametri di ricerca non validi.", mapper.readTree(exchange.body()).get("error").textValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD"})
    void onlyGetIsAllowed(String method) throws Exception {
        var exchange = invoke(method, "/api/prenotazioni/desk?data=2030-01-15");
        assertEquals(405, exchange.status); assertEquals("GET", exchange.responseHeaders.getFirst("Allow"));
        assertEquals(0, service.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/prenotazioni/desk/", "/api/prenotazioni/desk/extra", "/api/prenotazioni/Desk",
            "/api/prenotazioni/desks", "/api/prenotazioni/%64esk"})
    void malformedRoutesAreNotFound(String path) throws Exception {
        assertEquals(404, get(path + "?data=2030-01-15").status);
        assertEquals(0, service.calls);
    }

    @Test
    void sqlAndUnexpectedErrorsStayGeneric() throws Exception {
        for (Exception error : new Exception[] {new SQLException("Dati incoerenti nell'elenco desk. SEGRETO"),
                new IllegalStateException("SEGRETO")}) {
            service.error = error;
            var exchange = get("/api/prenotazioni/desk?data=2030-01-15");
            assertEquals(500, exchange.status); assertFalse(exchange.body().contains("SEGRETO"));
            assertEquals("Errore interno del server.", mapper.readTree(exchange.body()).get("error").textValue());
        }
    }

    @Test
    void serializesExactProjectionWithNestedListsAndNulls() throws Exception {
        var row = new PrenotazioneDeskResponse();
        row.setIdPrenotazione(42); row.setStato(StatoPrenotazione.NON_EROGATA); row.setPrezzo(new BigDecimal("80.00"));
        row.setIdDisponibilita(3); row.setDataOraInizio(LocalDateTime.of(2030, 1, 15, 9, 0));
        row.setDataOraFine(LocalDateTime.of(2030, 1, 15, 10, 0));
        row.setIdPaziente(197); row.setNomePaziente("Anna"); row.setCognomePaziente("Rossi");
        row.setIdMedicoTitolare(7); row.setNomeMedicoTitolare("Mario"); row.setCognomeMedicoTitolare("Bianchi");
        row.setIdMedicoEffettivo(8); row.setNomeMedicoEffettivo("Luca"); row.setCognomeMedicoEffettivo("Verdi");
        row.setGestitaDa(5); row.setDataOraGestione(LocalDateTime.of(2030, 1, 15, 11, 0, 1));
        row.getPagamenti().add(new PrenotazioneDeskResponse.Pagamento(10, ModalitaPagamento.ONLINE, new BigDecimal("80.00"),
                StatoPagamento.FALLITO, LocalDateTime.of(2030, 1, 10, 8, 0), null, null));
        row.getPagamenti().add(new PrenotazioneDeskResponse.Pagamento(11, ModalitaPagamento.ONLINE, new BigDecimal("80.00"),
                StatoPagamento.PAGATO, LocalDateTime.of(2030, 1, 10, 8, 5), LocalDateTime.of(2030, 1, 10, 8, 5), null));
        row.getRimborsi().add(new PrenotazioneDeskResponse.Rimborso(4, 11, StatoRimborso.RICHIESTO, new BigDecimal("80.00"),
                LocalDateTime.of(2030, 1, 16, 9, 0), null, null));
        service.result = List.of(row);
        var exchange = get("/api/prenotazioni/desk?data=2030-01-15");
        assertEquals(200, exchange.status);
        JsonNode list = mapper.readTree(exchange.body());
        assertTrue(list.isArray()); assertEquals(1, list.size());
        JsonNode json = list.get(0);
        assertEquals(Set.of("idPrenotazione", "stato", "indisponibilitaSegnalata", "prezzo", "idDisponibilita",
                "dataOraInizio", "dataOraFine", "idPaziente", "nomePaziente", "cognomePaziente",
                "idMedicoTitolare", "nomeMedicoTitolare", "cognomeMedicoTitolare",
                "idMedicoEffettivo", "nomeMedicoEffettivo", "cognomeMedicoEffettivo",
                "idPrestazione", "nomePrestazione", "idStanza", "nomeStanza", "gestitaDa", "dataOraGestione",
                "pagamenti", "rimborsi"), names(json));
        assertEquals("NON_EROGATA", json.get("stato").textValue());
        assertEquals(0, new BigDecimal("80.00").compareTo(json.get("prezzo").decimalValue()));
        assertEquals("2030-01-15T09:00:00", json.get("dataOraInizio").textValue());
        assertEquals("2030-01-15T11:00:01", json.get("dataOraGestione").textValue());
        assertEquals(5, json.get("gestitaDa").intValue());
        for (String nullable : List.of("idPrestazione", "nomePrestazione", "idStanza", "nomeStanza")) {
            assertTrue(json.get(nullable).isNull(), nullable);
        }
        JsonNode payments = json.get("pagamenti");
        assertEquals(2, payments.size());
        assertEquals(Set.of("idPagamento", "modalita", "importo", "stato", "creatoIl", "pagatoIl", "registratoDa"), names(payments.get(0)));
        assertEquals(List.of(10, 11), List.of(payments.get(0).get("idPagamento").intValue(), payments.get(1).get("idPagamento").intValue()));
        assertTrue(payments.get(0).get("pagatoIl").isNull()); assertTrue(payments.get(1).get("registratoDa").isNull());
        JsonNode refund = json.get("rimborsi").get(0);
        assertEquals(Set.of("idRimborso", "idPagamento", "stato", "importo", "richiestoIl", "verificatoIl", "verificatoDa"), names(refund));
        assertEquals(11, refund.get("idPagamento").intValue());
        assertTrue(refund.get("verificatoIl").isNull()); assertTrue(refund.get("verificatoDa").isNull());
        for (String forbidden : List.of("email", "telefono", "codiceFiscale", "password", "provider", "motivazione", "percorso")) {
            assertFalse(exchange.body().toLowerCase().contains(forbidden.toLowerCase()), forbidden);
        }
    }

    @Test
    void emptyNestedListsAreArrays() throws Exception {
        var row = new PrenotazioneDeskResponse();
        row.setIdPrenotazione(1); row.setStato(StatoPrenotazione.ANNULLATA);
        service.result = List.of(row);
        JsonNode json = mapper.readTree(get("/api/prenotazioni/desk?data=2030-01-15").body()).get(0);
        assertTrue(json.get("pagamenti").isArray()); assertEquals(0, json.get("pagamenti").size());
        assertTrue(json.get("rimborsi").isArray()); assertEquals(0, json.get("rimborsi").size());
        assertTrue(json.get("idMedicoEffettivo").isNull()); assertTrue(json.get("gestitaDa").isNull());
    }

    /** Il server HTTP del JDK aggiunge già l'header Date: unico riferimento temporale server esistente. */
    @Test
    void realHttpRouteCarriesStandardDateHeader() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/prenotazioni", controller); server.start();
        try {
            var uri = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/prenotazioni/desk?data=2030-01-15");
            var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertEquals("[]", response.body());
            String date = response.headers().firstValue("Date").orElseThrow();
            assertDoesNotThrow(() -> java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
        } finally { server.stop(0); }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names;
    }
}
