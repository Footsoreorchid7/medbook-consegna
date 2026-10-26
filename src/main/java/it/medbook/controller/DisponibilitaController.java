package it.medbook.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.dto.CreaDisponibilitaRequest;
import it.medbook.dto.ConfiguraDisponibilitaRequest;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.dto.DisponibilitaResponse;
import it.medbook.dto.DisponibilitaPrenotabileResponse;
import it.medbook.model.Disponibilita;
import it.medbook.service.DisponibilitaService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Controller funzionale: in Main è registrato dietro AutorizzazioneHandler. */
public class DisponibilitaController implements HttpHandler {
    /* Limite corpo JSON della configurazione disponibilità, come per gli altri controller. */
    static final int MAX_BODY = 8192;
    private final DisponibilitaService disponibilitaService;
    private final ObjectMapper objectMapper;

    public DisponibilitaController(DisponibilitaService disponibilitaService, ObjectMapper objectMapper) {
        this.disponibilitaService = disponibilitaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            if ("/api/disponibilita".equals(path)) {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> handleGet(exchange);
                    case "POST" -> handlePost(exchange);
                    default -> methodNotAllowed(exchange, "GET, POST");
                }
            } else if ("/api/disponibilita/prenotabili".equals(path)) {
                if ("GET".equals(exchange.getRequestMethod())) { handlePrenotabili(exchange); }
                else { methodNotAllowed(exchange, "GET"); }
            } else if (path.matches("/api/disponibilita/[^/]+/configurazione")) {
                if ("PUT".equals(exchange.getRequestMethod())) { handlePut(exchange, path); }
                else { methodNotAllowed(exchange, "PUT"); }
            } else if (path.matches("/api/disponibilita/[^/]+/orario")) {
                if ("PUT".equals(exchange.getRequestMethod())) { handleOrario(exchange, path); }
                else { methodNotAllowed(exchange, "PUT"); }
            } else if (path.matches("/api/disponibilita/[^/]+")) {
                if ("DELETE".equals(exchange.getRequestMethod())) { handleDelete(exchange, path); }
                else { methodNotAllowed(exchange, "DELETE"); }
            } else {
                sendError(exchange, 404, "Percorso non trovato.");
            }
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "Metodo non consentito.");
    }

    // AutorizzazioneHandler verifica identità/profilo; il repository riverifica la proprietà dello slot.
    private void handleOrario(HttpExchange exchange, String path) throws IOException {
        try {
            Integer id = parseIdOpzionale(path.split("/")[3]);
            if (exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            CreaDisponibilitaRequest request;
            try (InputStream input = exchange.getRequestBody()) {
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input);
                if (json == null || !json.isObject() || !json.has("idMedico")
                        || !json.get("idMedico").isIntegralNumber() || !json.get("idMedico").canConvertToInt()
                        || json.get("idMedico").intValue() <= 0
                        || !json.has("dataOraInizio") || !json.get("dataOraInizio").isTextual()
                        || !json.has("dataOraFine") || !json.get("dataOraFine").isTextual()) {
                    throw new IllegalArgumentException();
                }
                // Stessa struttura del DTO di creazione, parsing più rigoroso solo per la nuova rotta.
                request = objectMapper.readerFor(CreaDisponibilitaRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json.traverse(objectMapper));
            }
            if (request.getDataOraInizio() == null || request.getDataOraFine() == null
                    || request.getDataOraInizio().getNano() != 0 || request.getDataOraFine().getNano() != 0) {
                throw new IllegalArgumentException();
            }
            sendJson(exchange, 200, toResponse(disponibilitaService.modificaOrario(id, request.getIdMedico(),
                    request.getDataOraInizio(), request.getDataOraFine())));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (ConflittoDisponibilitaException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (OperazioneNonAutorizzataException e) {
            // UC-23: permesso MEDICO perso tra il controllo HTTP e la transazione.
            sendError(exchange, 403, e.getMessage());
        } catch (SQLException | RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    private void handleDelete(HttpExchange exchange, String path) throws IOException {
        try {
            Integer id = parseIdOpzionale(path.split("/")[3]);
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || query.isEmpty()) { throw new IllegalArgumentException(); }
            String[] parts = query.split("=", 2);
            if (parts.length != 2 || !"medicoId".equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))
                    || query.contains("&")) { throw new IllegalArgumentException(); }
            Integer medico = parseIdOpzionale(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            if (medico == null) { throw new IllegalArgumentException(); }
            disponibilitaService.eliminaDisponibilita(id, medico);
            exchange.sendResponseHeaders(204, -1);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (ConflittoDisponibilitaException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (OperazioneNonAutorizzataException e) {
            // UC-23: permesso MEDICO perso tra il controllo HTTP e la transazione.
            sendError(exchange, 403, e.getMessage());
        } catch (SQLException | RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    private void handlePrenotabili(HttpExchange exchange) throws IOException {
        Integer idMedico;
        Integer idPrestazione;
        LocalDate data;
        try {
            Map<String, String> filters = parseFiltriPrenotabili(exchange.getRequestURI().getRawQuery());
            idMedico = parseIdOpzionale(filters.get("medicoId"));
            idPrestazione = parseIdOpzionale(filters.get("prestazioneId"));
            String value = filters.get("data");
            if (value != null && !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException();
            }
            data = value == null ? null : LocalDate.parse(value);
        } catch (IllegalArgumentException | DateTimeException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
            return;
        }
        List<Disponibilita> slots;
        try {
            slots = disponibilitaService.getDisponibilitaPrenotabili(idMedico, idPrestazione, data);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, slots.stream().map(this::toPrenotabileResponse).toList());
    }

    private Map<String, String> parseFiltriPrenotabili(String query) {
        Map<String, String> filters = new HashMap<>();
        if (query == null || query.isEmpty()) { return filters; }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!List.of("medicoId", "prestazioneId", "data").contains(name) || parts.length != 2) {
                throw new IllegalArgumentException();
            }
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (value.isBlank() || filters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException();
            }
        }
        return filters;
    }

    private Integer parseIdOpzionale(String value) {
        if (value == null) { return null; }
        if (!value.matches("[0-9]+")) { throw new IllegalArgumentException(); }
        Integer id = Integer.valueOf(value);
        if (id <= 0) { throw new IllegalArgumentException(); }
        return id;
    }

    private DisponibilitaPrenotabileResponse toPrenotabileResponse(Disponibilita slot) {
        DisponibilitaPrenotabileResponse response = new DisponibilitaPrenotabileResponse();
        response.setIdDisponibilita(slot.getIdDisponibilita());
        response.setDataOraInizio(slot.getDataOraInizio());
        response.setDataOraFine(slot.getDataOraFine());
        response.setIdMedico(slot.getMedico().getUtente().getIdUtente());
        response.setNomeMedico(slot.getMedico().getUtente().getNome());
        response.setCognomeMedico(slot.getMedico().getUtente().getCognome());
        response.setIdPrestazione(slot.getPrestazione().getIdPrestazione());
        response.setNomePrestazione(slot.getPrestazione().getNome());
        response.setDurataPrestazione(slot.getPrestazione().getDurata());
        response.setPrezzoPrestazione(slot.getPrestazione().getPrezzo());
        return response;
    }

    private void handlePut(HttpExchange exchange, String path) throws IOException {
        Integer id;
        ConfiguraDisponibilitaRequest request;
        try {
            String value = path.split("/")[3];
            if (!value.matches("[0-9]+")) { throw new IllegalArgumentException("ID disponibilità non valido."); }
            id = Integer.valueOf(value);
            if (id <= 0) { throw new IllegalArgumentException("ID disponibilità non valido."); }
            /* Il controller riceve lo stream originale: non c'è attore nel corpo e quindi */
            /* l'autorizzazione non lo bufferizza. Lettura limitata prima di Jackson. */
            // Consuma al massimo MAX_BODY + 1 byte indipendentemente da Content-Length o trasferimento a blocchi.
            byte[] body;
            try (InputStream input = exchange.getRequestBody()) { body = input.readNBytes(MAX_BODY + 1); }
            if (body.length > MAX_BODY) { throw new IllegalArgumentException("Richiesta non valida."); }
            com.fasterxml.jackson.databind.JsonNode json = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (json == null || !json.isObject() || !json.has("idPrestazione") || !json.has("idStanza")
                    || !json.get("idPrestazione").isIntegralNumber() || !json.get("idPrestazione").canConvertToInt()
                    || !json.get("idStanza").isIntegralNumber() || !json.get("idStanza").canConvertToInt()) {
                throw new IllegalArgumentException("Richiesta non valida.");
            }
            request = objectMapper.readerFor(ConfiguraDisponibilitaRequest.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(json.traverse(objectMapper));
            if (request == null || request.getIdPrestazione() == null || request.getIdPrestazione() <= 0
                    || request.getIdStanza() == null || request.getIdStanza() <= 0) {
                throw new IllegalArgumentException("ID prestazione e stanza devono essere maggiori di zero.");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        Disponibilita result;
        try {
            result = disponibilitaService.configuraDisponibilita(id, request.getIdPrestazione(), request.getIdStanza());
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (ConflittoConfigurazioneException e) {
            sendError(exchange, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, toResponse(result));
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        Integer idMedico;
        try {
            idMedico = parseMedicoId(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Parametro medicoId non valido.");
            return;
        }

        List<Disponibilita> slots;
        try {
            slots = disponibilitaService.getDisponibilitaByMedico(idMedico);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, slots.stream().map(this::toResponse).toList());
    }

    private Integer parseMedicoId(String query) {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException();
        }
        String value = null;
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!"medicoId".equals(name) || parts.length != 2 || value != null) {
                throw new IllegalArgumentException();
            }
            value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return Integer.valueOf(value);
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        CreaDisponibilitaRequest request;
        try (InputStream input = exchange.getRequestBody()) {
            request = objectMapper.readerFor(CreaDisponibilitaRequest.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(input);
        } catch (JsonProcessingException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        if (request == null) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }

        List<Disponibilita> slots;
        try {
            slots = disponibilitaService.creaDisponibilita(
                    request.getIdMedico(), request.getDataOraInizio(), request.getDataOraFine());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (OperazioneNonAutorizzataException e) {
            // UC-23: permesso MEDICO perso tra il controllo HTTP e la transazione.
            sendError(exchange, 403, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 201, slots.stream().map(this::toResponse).toList());
    }

    private DisponibilitaResponse toResponse(Disponibilita disponibilita) {
        DisponibilitaResponse response = new DisponibilitaResponse();
        response.setIdDisponibilita(disponibilita.getIdDisponibilita());
        response.setDataOraInizio(disponibilita.getDataOraInizio());
        response.setDataOraFine(disponibilita.getDataOraFine());
        response.setIdMedico(disponibilita.getMedico().getUtente().getIdUtente());
        if (disponibilita.getPrestazione() != null) {
            response.setIdPrestazione(disponibilita.getPrestazione().getIdPrestazione());
            response.setNomePrestazione(disponibilita.getPrestazione().getNome());
        }
        if (disponibilita.getStanza() != null) {
            response.setIdStanza(disponibilita.getStanza().getIdStanza());
            response.setNomeStanza(disponibilita.getStanza().getNome());
        }
        response.setConfigurata(disponibilita.isConfigurata());
        return response;
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] response;
        try {
            response = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            // Invia il 500 prima della chiusura dell'exchange, senza ripetere l'operazione.
            status = 500;
            response = "{\"error\":\"Errore interno del server.\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
