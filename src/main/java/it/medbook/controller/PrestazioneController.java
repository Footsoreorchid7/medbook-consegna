package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.model.Prestazione;
import it.medbook.service.PrestazioneService;
import it.medbook.security.PoliticaAccesso;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import it.medbook.dto.PrestazioneRequest;
import it.medbook.dto.PrestazioneResponse;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.Set;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Controller funzionale: in Main è registrato dietro AutorizzazioneHandler. */
public class PrestazioneController implements HttpHandler {
    private final PrestazioneService prestazioneService;
    private final ObjectMapper objectMapper;

    public PrestazioneController(PrestazioneService prestazioneService, ObjectMapper objectMapper) {
        this.prestazioneService = prestazioneService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            boolean base = "/api/prestazioni".equals(path);
            boolean item = PoliticaAccesso.isPrestazioneResourcePath(path);
            if (!base && !item) {
                sendJson(exchange, 404, Map.of("error", "Percorso non trovato.")); return;
            }
            String method = exchange.getRequestMethod();
            if (!(base && (method.equals("GET") || method.equals("POST"))) && !(item && method.equals("PUT"))) {
                exchange.getResponseHeaders().set("Allow", base ? "GET, POST" : "PUT");
                sendJson(exchange, 405, Map.of("error", "Metodo non consentito.")); return;
            }
            if (!method.equals("GET")) {
                try {
                    if (exchange.getRequestURI().getRawQuery() != null) {
                        throw new IllegalArgumentException("Query string non consentita.");
                    }
                    Integer id = null;
                    if (item) {
                        String raw = path.substring("/api/prestazioni/".length());
                        if (!raw.matches("[1-9][0-9]*")) { throw new IllegalArgumentException("ID prestazione non valido."); }
                        id = Integer.valueOf(raw);
                    }
                    List<String> types = exchange.getRequestHeaders().get("Content-Type");
                    if (types == null || types.size() != 1 || !types.getFirst().matches(
                            "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\"))?\\s*")) {
                        sendJson(exchange, 415, Map.of("error", "Content-Type non supportato.")); return;
                    }
                    PrestazioneRequest request = readRequest(exchange);
                    Prestazione result = id == null ? prestazioneService.creaPrestazione(request)
                            : prestazioneService.aggiornaPrestazione(id, request);
                    sendJson(exchange, id == null ? 201 : 200, toResponse(result));
                } catch (IllegalArgumentException e) {
                    sendJson(exchange, 400, Map.of("error", "Richiesta non valida."));
                } catch (RisorsaNonTrovataException e) {
                    sendJson(exchange, 404, Map.of("error", e.getMessage()));
                } catch (ConflittoConfigurazioneException e) {
                    sendJson(exchange, 409, Map.of("error", e.getMessage()));
                } catch (SQLException e) {
                    sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
                }
                return;
            }

            List<Prestazione> prestazioni;
            try {
                prestazioni = prestazioneService.getAllPrestazioni();
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
                return;
            }
            sendJson(exchange, 200, prestazioni);
        }
    }

    private PrestazioneRequest readRequest(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (var input = exchange.getRequestBody()) { bytes = input.readNBytes(8193); }
        if (bytes.length == 0 || bytes.length > 8192) { throw new IllegalArgumentException(); }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("UTF-8 non valido.", e); }
        JsonNode json;
        try {
            json = objectMapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON non valido.", e); }
        if (json == null || !json.isObject()) { throw new IllegalArgumentException(); }
        Set<String> allowed = Set.of("nome", "descrizione", "durata", "prezzo");
        json.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) { throw new IllegalArgumentException(); } });
        JsonNode nome = json.get("nome"), durata = json.get("durata"), prezzo = json.get("prezzo"), descrizione = json.get("descrizione");
        if (nome == null || !nome.isTextual() || durata == null || !durata.isIntegralNumber()
                || !durata.canConvertToInt() || prezzo == null || !prezzo.isNumber()
                || (descrizione != null && !descrizione.isNull() && !descrizione.isTextual())) {
            throw new IllegalArgumentException();
        }
        PrestazioneRequest result = new PrestazioneRequest();
        result.setNome(nome.textValue()); result.setDurata(durata.intValue()); result.setPrezzo(prezzo.decimalValue());
        result.setDescrizione(descrizione == null || descrizione.isNull() ? null : descrizione.textValue());
        return result;
    }

    private PrestazioneResponse toResponse(Prestazione value) {
        PrestazioneResponse result = new PrestazioneResponse();
        result.setIdPrestazione(value.getIdPrestazione()); result.setNome(value.getNome());
        result.setDescrizione(value.getDescrizione()); result.setDurata(value.getDurata()); result.setPrezzo(value.getPrezzo());
        return result;
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
