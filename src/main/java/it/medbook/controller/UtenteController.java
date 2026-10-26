package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.model.RuoloGestionale;
import it.medbook.dto.*;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.service.UtenteService;
import it.medbook.security.PoliticaAccesso;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;


import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.repository.UtenteRepository.ConflittoAccountException;
import it.medbook.repository.UtenteRepository.LockAccountTimeoutException;
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
public class UtenteController implements HttpHandler {
    private final UtenteService utenteService;
    private final ObjectMapper objectMapper;

    public UtenteController(UtenteService utenteService, ObjectMapper objectMapper) {
        this.utenteService = utenteService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (RuntimeException e) {
                /* 500 generico solo prima dell'invio dello status; mai una seconda risposta. */
                if (!responseStarted(exchange)) {
                    sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
                }
            }
        }
    }

    // HttpExchange restituisce -1 prima di sendResponseHeaders; i doppi di test possono restituire zero.
    private static boolean responseStarted(HttpExchange exchange) {
        return exchange.getResponseCode() > 0;
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        boolean base = "/api/utenti".equals(path);
        boolean stato = PoliticaAccesso.isUtenteStatoPath(path);
        boolean item = PoliticaAccesso.isUtenteRuoloPath(path) || stato;
        if ("/api/utenti/ricerca".equals(path)) { ricerca(exchange); return; }
        if (!base && !item) {
            sendJson(exchange, 404, Map.of("error", "Percorso non trovato.")); return;
        }
        String method = exchange.getRequestMethod();
        if (!(base && (method.equals("GET") || method.equals("POST"))) && !(item && method.equals("PUT"))) {
            exchange.getResponseHeaders().set("Allow", base ? "GET, POST" : "PUT");
            sendJson(exchange, 405, Map.of("error", "Metodo non consentito.")); return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendJson(exchange, 400, Map.of("error", "Query string non consentita.")); return;
        }
        if (!method.equals("GET")) {
            try {
                Integer id = null;
                if (item) {
                    String raw = path.substring("/api/utenti/".length(),path.lastIndexOf('/'));
                    if (!raw.matches("[1-9][0-9]*")) { throw new IllegalArgumentException("ID utente non valido."); }
                    id = Integer.valueOf(raw);
                }
                List<String> types = exchange.getRequestHeaders().get("Content-Type");
                if (types == null || types.size() != 1 || !types.getFirst().matches(
                        "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\"))?\\s*")) {
                    sendJson(exchange, 415, Map.of("error", "Content-Type non supportato.")); return;
                }
                JsonNode json = readRequest(exchange, id == null ? Set.of("nome","cognome","email","password","ruoloGestionale")
                        : Set.of(stato ? "statoAccesso" : "ruoloGestionale"));
                int actor=Integer.parseInt(exchange.getPrincipal().getUsername());
                if (stato) {
                    /* 200 con proiezione amministrativa anche quando idempotente, senza scritture. */
                    sendJson(exchange, 200, utenteService.cambiaStato(actor, id, json.get("statoAccesso").textValue())); return;
                }
                RuoloGestionale role=RuoloGestionale.valueOf(json.get("ruoloGestionale").textValue());
                AccountInternoResponse result=id==null?utenteService.crea(actor,new AccountInternoRequest(
                        json.get("nome").textValue(),json.get("cognome").textValue(),json.get("email").textValue(),
                        json.get("password").textValue(),role)):utenteService.aggiornaRuolo(actor,id,new RuoloUtenteRequest(role));
                sendJson(exchange, id == null ? 201 : 200, result);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("error", "Richiesta non valida."));
            } catch (RisorsaNonTrovataException e) {
                sendJson(exchange, 404, Map.of("error", e.getMessage()));
            } catch (OperazioneNonAutorizzataException e) {
                sendJson(exchange,403,Map.of("error","Operazione non autorizzata."));
            } catch (ConflittoAccountException | it.medbook.exception.GenerazioneEsauritaException e) {
                sendJson(exchange, 409, Map.of("error", e.getMessage()));
            } catch (LockAccountTimeoutException e) {
                /* Contesa sul mutex account: condizione transitoria, non conflitto funzionale. */
                exchange.getResponseHeaders().set("Retry-After", "5");
                sendJson(exchange, 503, Map.of("error", "Servizio temporaneamente non disponibile."));
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
            }
            return;
        }

        List<AccountInternoResponse> utenti;
        try {
            utenti = utenteService.getGestionali();
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
            return;
        }
        sendJson(exchange, 200, utenti);
    }

    /** UC-26 §4: GET /api/utenti/ricerca; GET /api/utenti keeps refusing any query string. */
    private void ricerca(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendJson(exchange, 405, Map.of("error", "Metodo non consentito.")); return;
        }
        try {
            int actor = Integer.parseInt(exchange.getPrincipal().getUsername());
            sendJson(exchange, 200, utenteService.cerca(actor, parametri(exchange.getRequestURI().getRawQuery())));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "Richiesta non valida."));
        } catch (OperazioneNonAutorizzataException e) {
            sendJson(exchange, 403, Map.of("error", "Operazione non autorizzata."));
        } catch (SQLException e) {
            /* Anomalia account o errore driver: 500 generico, mai pagina parziale. */
            sendJson(exchange, 500, Map.of("error", "Errore interno del server."));
        }
    }

    /*
     * Query rigorosa: coppie nome=valore non vuote, percent-decoding UTF-8 e più come spazio.
     * Escape malformati o nomi decodificati duplicati producono 400.
     */
    static Map<String, String> parametri(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) { throw new IllegalArgumentException(); }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) { throw new IllegalArgumentException(); }
            String name = decodifica(pair.substring(0, separator)), value = decodifica(pair.substring(separator + 1));
            if (name.isEmpty() || value.isEmpty() || result.putIfAbsent(name, value) != null) { throw new IllegalArgumentException(); }
        }
        return result;
    }
    private static String decodifica(String raw) {
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); ) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) { throw new IllegalArgumentException(); }
                int high = Character.digit(raw.charAt(i + 1), 16), low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) { throw new IllegalArgumentException(); }
                bytes.write(high * 16 + low); i += 3;
            } else if (c == '+') {
                bytes.write(' '); i++;
            } else {
                int codePoint = raw.codePointAt(i);
                if (Character.isSurrogate((char) codePoint) && codePoint <= 0xFFFF) { throw new IllegalArgumentException(); }
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length); i += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("UTF-8 non valido.", e); }
    }

    private JsonNode readRequest(HttpExchange exchange, Set<String> allowed) throws IOException {
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
        json.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException();});
        for(String field:allowed)if(!json.has(field) || !json.get(field).isTextual())throw new IllegalArgumentException();
        return json;
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
