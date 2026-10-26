package it.medbook.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.medbook.dto.CreaPrenotazioneRequest;
import it.medbook.dto.SostituzioneMedicoRequest;
import it.medbook.dto.SegnalaIndisponibilitaRequest;
import it.medbook.dto.ConfermaPrenotazioneRequest;
import it.medbook.dto.RifiutoPrenotazioneRequest;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneRequest;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteRequest;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.dto.PrenotazioneResponse;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.Disponibilita;
import it.medbook.model.Prenotazione;
import it.medbook.model.StatoPrenotazione;
import it.medbook.model.Utente;
import it.medbook.service.PrenotazioneService;

import it.medbook.service.PagamentoService;
import it.medbook.dto.PagamentoResponse;
import it.medbook.dto.PagamentoSedeRequest;
import it.medbook.model.ModalitaPagamento;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.DateTimeException;
import it.medbook.dto.RichiestaPrenotazioneResponse;

/** Controller funzionale: in Main è registrato dietro AutorizzazioneHandler. */
import it.medbook.dto.VerificaMancataErogazioneRequest;
import it.medbook.dto.VerificaMancataErogazioneResponse;

public class PrenotazioneController implements HttpHandler {
    private final PrenotazioneService service;
    private final ObjectMapper objectMapper;
    private final PagamentoService pagamentoService;
    private final RimborsoController rimborsoController;
    private final PagamentoOnlineController pagamentoOnlineController;
    private final RefertoController refertoController;

    public PrenotazioneController(PrenotazioneService service, ObjectMapper objectMapper) {
        this(service, objectMapper, null);
    }

    public PrenotazioneController(PrenotazioneService service, ObjectMapper objectMapper,
            PagamentoService pagamentoService) {
        this(service, objectMapper, pagamentoService, null);
    }

    public PrenotazioneController(PrenotazioneService service, ObjectMapper objectMapper,
            PagamentoService pagamentoService, it.medbook.service.RimborsoService rimborsoService) {
        this(service, objectMapper, pagamentoService, rimborsoService, null);
    }

    public PrenotazioneController(PrenotazioneService service, ObjectMapper objectMapper,
            PagamentoService pagamentoService, it.medbook.service.RimborsoService rimborsoService,
            it.medbook.service.RefertoService refertoService) {
        this.refertoController = new RefertoController(refertoService, objectMapper);
        this.rimborsoController = rimborsoService == null ? null : new RimborsoController(rimborsoService, objectMapper);
        this.pagamentoService = pagamentoService;
        this.pagamentoOnlineController = new PagamentoOnlineController(pagamentoService, objectMapper);
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/%]+/referto(?:/documento)?")) {
                refertoController.handle(exchange); return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/%]+/registra-visita")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleRegistrazioneVisita(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/(pagamento-online|pagamenti)")) {
                pagamentoOnlineController.handle(exchange); return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/rimborso")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else if (rimborsoController == null) {
                    sendError(exchange, 500, "Errore interno del server.");
                } else { rimborsoController.handleRichiesta(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/verifica-mancata-erogazione")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleVerificaMancataErogazione(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/pagamento-sede")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handlePagamentoSede(exchange); }
                return;
            }
            if ("/api/prenotazioni/segnala-indisponibilita".equals(exchange.getRequestURI().getRawPath())) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleSegnalazione(exchange); }
                return;
            }
            if ("/api/prenotazioni/segnalazioni-indisponibilita".equals(exchange.getRequestURI().getRawPath())) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleSegnalazioniPendenti(exchange); }
                return;
            }
            if ("/api/prenotazioni/paziente".equals(exchange.getRequestURI().getRawPath())) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleAppuntamentiPaziente(exchange); }
                return;
            }
            if ("/api/prenotazioni/agenda".equals(exchange.getRequestURI().getRawPath())) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleAgenda(exchange); }
                return;
            }
            if ("/api/prenotazioni/richieste".equals(exchange.getRequestURI().getRawPath())) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleRichieste(exchange); }
                return;
            }
            if ("/api/prenotazioni/desk".equals(exchange.getRequestURI().getRawPath())) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handlePrenotazioniDesk(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/sostituisci-medico")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleSostituzione(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/conferma")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleConferma(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/rifiuta")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleRifiuto(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/annulla")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleAnnullamento(exchange); }
                return;
            }
            if (exchange.getRequestURI().getRawPath().matches("/api/prenotazioni/[^/]+/annulla-paziente")) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendError(exchange, 405, "Metodo non consentito.");
                } else { handleAnnullamentoPaziente(exchange); }
                return;
            }
            if (!"/api/prenotazioni".equals(exchange.getRequestURI().getRawPath())) {
                sendError(exchange, 404, "Percorso non trovato.");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendError(exchange, 405, "Metodo non consentito.");
                return;
            }
            CreaPrenotazioneRequest request;
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idPaziente"))
                        || !validId(json.get("idDisponibilita"))) {
                    sendError(exchange, 400, "Richiesta non valida.");
                    return;
                }
                request = objectMapper.readerFor(CreaPrenotazioneRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
            } catch (JsonProcessingException e) {
                sendError(exchange, 400, "Richiesta non valida.");
                return;
            }
            Prenotazione result;
            try {
                result = service.richiediAppuntamento(request.getIdPaziente(), request.getIdDisponibilita());
            } catch (RisorsaNonTrovataException e) {
                sendError(exchange, 404, e.getMessage());
                return;
            } catch (ConflittoPrenotazioneException e) {
                sendError(exchange, 409, e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage());
                return;
            } catch (SQLException e) {
                sendError(exchange, 500, "Errore interno del server.");
                return;
            }
            sendJson(exchange, 201, toResponse(result));
        }
    }

    private void handleRegistrazioneVisita(HttpExchange exchange) throws IOException {
        if (exchange.getPrincipal() == null) {
            sendError(exchange, 401, "Autenticazione richiesta."); return;
        }
        Integer id;
        Integer medico;
        try {
            medico = Integer.valueOf(exchange.getPrincipal().getUsername());
            if (medico <= 0) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException e) {
            sendError(exchange, 401, "Autenticazione richiesta."); return;
        }
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            if (id == null || exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            byte[] bytes;
            try (InputStream input = exchange.getRequestBody()) { bytes = input.readNBytes(8193); }
            if (bytes.length > 8192) { throw new IllegalArgumentException(); }
            JsonNode json = objectMapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (json == null || !json.isObject() || !json.isEmpty()) { throw new IllegalArgumentException(); }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida."); return;
        }
        it.medbook.dto.RegistrazioneVisitaResponse result;
        try { result = service.registraVisita(id, medico); }
        catch (RisorsaNonTrovataException e) { sendError(exchange, 404, "Prenotazione non trovata."); return; }
        catch (ConflittoPrenotazioneException e) { sendError(exchange, 409, e.getMessage()); return; }
        catch (IllegalArgumentException e) { sendError(exchange, 400, "Richiesta non valida."); return; }
        catch (SQLException | IllegalStateException e) { sendError(exchange, 500, "Errore interno del server."); return; }
        sendJson(exchange, 200, result);
    }

    private void handleVerificaMancataErogazione(HttpExchange exchange) throws IOException {
        Integer id;
        VerificaMancataErogazioneRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            if (id == null || exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            byte[] bytes;
            try (InputStream input = exchange.getRequestBody()) { bytes = input.readNBytes(8193); }
            if (bytes.length > 8192) { throw new IllegalArgumentException(); }
            JsonNode json = objectMapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (json == null || !json.isObject() || json.size() != 3 || !validId(json.get("idOperatore"))
                    || !json.has("statoAtteso") || !json.get("statoAtteso").isTextual()
                    || !"CONFERMATA".equals(json.get("statoAtteso").textValue())
                    || !json.has("esito") || !json.get("esito").isTextual()
                    || !List.of("PAZIENTE_ASSENTE", "NON_EROGATA").contains(json.get("esito").textValue())) {
                throw new IllegalArgumentException();
            }
            request = new VerificaMancataErogazioneRequest();
            request.setIdOperatore(json.get("idOperatore").intValue());
            request.setStatoAtteso(StatoPrenotazione.CONFERMATA);
            request.setEsito(StatoPrenotazione.valueOf(json.get("esito").textValue()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida."); return;
        }
        VerificaMancataErogazioneResponse result;
        try {
            result = service.verificaMancataErogazione(id, request.getIdOperatore(), request.getStatoAtteso(), request.getEsito());
        } catch (OperazioneNonAutorizzataException e) { sendError(exchange, 403, e.getMessage()); return;
        } catch (RisorsaNonTrovataException e) { sendError(exchange, 404, e.getMessage()); return;
        } catch (ConflittoPrenotazioneException e) { sendError(exchange, 409, e.getMessage()); return;
        } catch (IllegalArgumentException e) { sendError(exchange, 400, "Richiesta non valida."); return;
        } catch (SQLException | IllegalStateException e) { sendError(exchange, 500, "Errore interno del server."); return; }
        sendJson(exchange, 200, result);
    }

    private void handlePagamentoSede(HttpExchange exchange) throws IOException {
        Integer id;
        PagamentoSedeRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            if (id == null || exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            byte[] bytes;
            try (InputStream input = exchange.getRequestBody()) { bytes = input.readNBytes(8193); }
            if (bytes.length > 8192) { throw new IllegalArgumentException(); }
            JsonNode json = objectMapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (json == null || !json.isObject() || json.size() != 3 || !validId(json.get("idOperatore"))
                    || !json.has("modalita") || !json.get("modalita").isTextual()
                    || !List.of("DESK_POS", "DESK_CONTANTI").contains(json.get("modalita").textValue())
                    || !json.has("importoAtteso") || !json.get("importoAtteso").isNumber()) {
                throw new IllegalArgumentException();
            }
            request = new PagamentoSedeRequest();
            request.setIdOperatore(json.get("idOperatore").intValue());
            request.setModalita(ModalitaPagamento.valueOf(json.get("modalita").textValue()));
            // Evita conversioni attraverso double: mantiene la precisione del numero JSON.
            request.setImportoAtteso(new BigDecimal(json.get("importoAtteso").asText()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida."); return;
        }
        PagamentoResponse result;
        try {
            if (pagamentoService == null) { throw new IllegalStateException("PagamentoService non configurato."); }
            result = pagamentoService.registraPagamentoInSede(id, request.getIdOperatore(),
                    request.getModalita(), request.getImportoAtteso());
        } catch (OperazioneNonAutorizzataException e) {
            sendError(exchange, 403, e.getMessage()); return;
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage()); return;
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage()); return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida."); return;
        } catch (SQLException | IllegalStateException e) {
            sendError(exchange, 500, "Errore interno del server."); return;
        }
        sendJson(exchange, 201, result);
    }

    // medicoId è verificato contro la sessione dal confine HTTP prima della delega.
    private void handleSegnalazione(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            SegnalaIndisponibilitaRequest request;
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("medicoId"))
                        || !json.has("idPrenotazioni") || !json.get("idPrenotazioni").isArray()
                        || json.get("idPrenotazioni").isEmpty()) {
                    throw new IllegalArgumentException();
                }
                for (JsonNode id : json.get("idPrenotazioni")) {
                    if (!validId(id)) { throw new IllegalArgumentException(); }
                }
                request = objectMapper.readerFor(SegnalaIndisponibilitaRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
                PrenotazioneService.validaSegnalazione(request.getMedicoId(), request.getIdPrenotazioni());
            }
            sendJson(exchange, 200, service.segnalaIndisponibilita(request.getMedicoId(), request.getIdPrenotazioni()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (SQLException | RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    // Destinata a OPERATORE_DESK, verificato da AutorizzazioneHandler.
    private void handleSegnalazioniPendenti(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendError(exchange, 400, "Parametri non consentiti.");
            return;
        }
        try {
            sendJson(exchange, 200, service.getSegnalazioniPendenti());
        } catch (SQLException | RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    // Main verifica sessione, ruolo desk e corrispondenza dell'ID operatore prima della delega.
    private void handleConferma(HttpExchange exchange) throws IOException {
        Integer id;
        ConfermaPrenotazioneRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idOperatore"))) {
                    throw new IllegalArgumentException();
                }
                request = objectMapper.readerFor(ConfermaPrenotazioneRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        ConfermaPrenotazioneResponse result;
        try {
            result = service.confermaAppuntamento(id, request.getIdOperatore());
        } catch (OperazioneNonAutorizzataException e) {
            sendError(exchange, 403, e.getMessage());
            return;
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    // Main verifica sessione, ruolo desk e corrispondenza dell'ID operatore prima della delega.
    private void handleRifiuto(HttpExchange exchange) throws IOException {
        Integer id;
        RifiutoPrenotazioneRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idOperatore"))) {
                    throw new IllegalArgumentException();
                }
                request = objectMapper.readerFor(RifiutoPrenotazioneRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        RifiutoPrenotazioneResponse result;
        try {
            result = service.rifiutaAppuntamento(id, request.getIdOperatore());
        } catch (OperazioneNonAutorizzataException e) {
            sendError(exchange, 403, e.getMessage());
            return;
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    // Main verifica sessione e corrispondenza degli ID attore; qui restano i controlli transazionali.
    private void handleSostituzione(HttpExchange exchange) throws IOException {
        try {
            Integer id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            if (id == null || exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            SostituzioneMedicoRequest request;
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idOperatore"))
                        || !validId(json.get("idMedicoSostituto")) || !validId(json.get("idMedicoIndisponibile"))
                        || !json.has("statoAtteso") || !json.get("statoAtteso").isTextual()
                        || !List.of("RICHIESTA", "CONFERMATA").contains(json.get("statoAtteso").textValue())) {
                    throw new IllegalArgumentException();
                }
                request = objectMapper.readerFor(SostituzioneMedicoRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json.traverse(objectMapper));
            }
            sendJson(exchange, 200, service.sostituisciMedico(id, request.getIdOperatore(), request.getIdMedicoSostituto(),
                    request.getStatoAtteso(), request.getIdMedicoIndisponibile()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
        } catch (OperazioneNonAutorizzataException e) { sendError(exchange, 403, e.getMessage());
        } catch (RisorsaNonTrovataException e) { sendError(exchange, 404, e.getMessage());
        } catch (ConflittoPrenotazioneException e) { sendError(exchange, 409, e.getMessage());
        } catch (SQLException | RuntimeException e) { sendError(exchange, 500, "Errore interno del server."); }
    }

    private void handleAnnullamento(HttpExchange exchange) throws IOException {
        Integer id;
        AnnullamentoPrenotazioneRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idOperatore"))
                        || !json.has("statoAtteso") || !json.get("statoAtteso").isTextual()
                        || !List.of("RICHIESTA", "CONFERMATA").contains(json.get("statoAtteso").textValue())) {
                    throw new IllegalArgumentException();
                }
                request = objectMapper.readerFor(AnnullamentoPrenotazioneRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        AnnullamentoPrenotazioneResponse result;
        try {
            result = service.annullaAppuntamento(id, request.getIdOperatore(), request.getStatoAtteso());
        } catch (OperazioneNonAutorizzataException e) {
            sendError(exchange, 403, e.getMessage());
            return;
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    // Main verifica sessione e corrispondenza del paziente dichiarato; il Repository verifica la proprietà.
    private void handleAnnullamentoPaziente(HttpExchange exchange) throws IOException {
        Integer id;
        AnnullamentoPazienteRequest request;
        try {
            id = parseIdOpzionale(exchange.getRequestURI().getRawPath().split("/")[3]);
            try (InputStream input = exchange.getRequestBody()) {
                JsonNode json = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input);
                if (json == null || !json.isObject() || !validId(json.get("idPaziente"))
                        || !json.has("statoAtteso") || !json.get("statoAtteso").isTextual()
                        || !List.of("RICHIESTA", "CONFERMATA").contains(json.get("statoAtteso").textValue())) {
                    throw new IllegalArgumentException();
                }
                request = objectMapper.readerFor(AnnullamentoPazienteRequest.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(json.traverse(objectMapper));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            sendError(exchange, 400, "Richiesta non valida.");
            return;
        }
        AnnullamentoPazienteResponse result;
        try {
            result = service.annullaAppuntamentoPaziente(id, request.getIdPaziente(), request.getStatoAtteso());
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
            return;
        } catch (ConflittoPrenotazioneException e) {
            sendError(exchange, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    // Main consente questa consultazione soltanto a una sessione con ruolo OPERATORE_DESK.
    private void handleRichieste(HttpExchange exchange) throws IOException {
        Integer medico;
        Integer prestazione;
        LocalDate da;
        LocalDate a;
        String stato;
        try {
            Map<String, String> filters = parseFiltriRichieste(exchange.getRequestURI().getRawQuery());
            stato = filters.get("stato");
            if (stato != null && !"TUTTE".equals(stato)) { StatoPrenotazione.valueOf(stato); }
            medico = parseIdOpzionale(filters.get("medicoId"));
            prestazione = parseIdOpzionale(filters.get("prestazioneId"));
            LocalDate data = parseDataOpzionale(filters.get("data"));
            da = parseDataOpzionale(filters.get("dataDa"));
            a = parseDataOpzionale(filters.get("dataA"));
            if (data != null) {
                if (da != null || a != null) { throw new IllegalArgumentException(); }
                da = data;
                a = data;
            }
            if (da != null && a != null && da.isAfter(a)) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException | DateTimeException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
            return;
        }
        List<RichiestaPrenotazioneResponse> result;
        try {
            result = stato == null ? service.getRichieste(medico, prestazione, da, a)
                    : service.getRichieste(medico, prestazione, da, a, stato);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    // Main consente questa consultazione soltanto a una sessione con ruolo OPERATORE_DESK;
    // medicoId è un filtro sul medico effettivo, non un'identità.
    private void handlePrenotazioniDesk(HttpExchange exchange) throws IOException {
        Integer medico;
        LocalDate data;
        LocalDate da;
        LocalDate a;
        try {
            Map<String, String> filters = parseFiltriDesk(exchange.getRequestURI().getRawQuery());
            medico = parseIdOpzionale(filters.get("medicoId"));
            data = parseDataOpzionale(filters.get("data"));
            da = parseDataOpzionale(filters.get("dataDa"));
            a = parseDataOpzionale(filters.get("dataA"));
            if ((data == null) == (da == null && a == null) || (da == null) != (a == null)
                    || (da != null && (da.isAfter(a) || java.time.temporal.ChronoUnit.DAYS.between(da, a) >= 31))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
            return;
        }
        List<it.medbook.dto.PrenotazioneDeskResponse> result;
        try {
            result = service.getPrenotazioniDesk(medico, data, da, a);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
            return;
        } catch (SQLException | RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
            return;
        }
        sendJson(exchange, 200, result);
    }

    private Map<String, String> parseFiltriDesk(String query) {
        Map<String, String> filters = new HashMap<>();
        if (query == null || query.isEmpty()) { return filters; }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!List.of("medicoId", "data", "dataDa", "dataA").contains(name) || parts.length != 2) {
                throw new IllegalArgumentException();
            }
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (value.isBlank() || filters.putIfAbsent(name, value) != null) { throw new IllegalArgumentException(); }
        }
        return filters;
    }

    // Sessione e profilo medico verificati da AutorizzazioneHandler; qui restano i filtri.
    // Main vincola medicoId al profilo medico dell'identità autenticata.
    private void handleAgenda(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> filters = parseFiltriAgenda(exchange.getRequestURI().getRawQuery());
            Integer medico = parseIdOpzionale(filters.get("medicoId"));
            if (medico == null) { throw new IllegalArgumentException(); }
            LocalDate data = parseDataOpzionale(filters.get("data"));
            LocalDate da = parseDataOpzionale(filters.get("dataDa"));
            LocalDate a = parseDataOpzionale(filters.get("dataA"));
            if ((data != null && (da != null || a != null)) || ((da == null) != (a == null))
                    || (da != null && (da.isAfter(a) || java.time.temporal.ChronoUnit.DAYS.between(da, a) >= 31))) {
                throw new IllegalArgumentException();
            }
            sendJson(exchange, 200, service.getAgenda(medico, data, da, a));
        } catch (IllegalArgumentException | DateTimeException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
        } catch (RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    private Map<String, String> parseFiltriAgenda(String query) {
        Map<String, String> filters = new HashMap<>();
        if (query == null || query.isEmpty()) { return filters; }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!List.of("medicoId", "data", "dataDa", "dataA").contains(name) || parts.length != 2) {
                throw new IllegalArgumentException();
            }
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (value.isBlank() || filters.putIfAbsent(name, value) != null) { throw new IllegalArgumentException(); }
        }
        return filters;
    }

    // Main verifica il profilo paziente e la corrispondenza di idPaziente con la sessione.
    // I controlli di proprietà nel Repository rimangono invariati.
    private void handleAppuntamentiPaziente(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> filters = parseFiltriPaziente(exchange.getRequestURI().getRawQuery());
            Integer paziente = parseIdOpzionale(filters.get("idPaziente"));
            if (paziente == null) { throw new IllegalArgumentException(); }
            String value = filters.get("stato");
            StatoPrenotazione stato = value == null ? null : StatoPrenotazione.valueOf(value);
            LocalDate data = parseDataOpzionale(filters.get("data"));
            LocalDate da = parseDataOpzionale(filters.get("dataDa"));
            LocalDate a = parseDataOpzionale(filters.get("dataA"));
            if ((data != null && (da != null || a != null)) || ((da == null) != (a == null))
                    || (da != null && da.isAfter(a))) {
                throw new IllegalArgumentException();
            }
            sendJson(exchange, 200, service.getAppuntamentiPaziente(paziente, stato, data, da, a));
        } catch (IllegalArgumentException | DateTimeException e) {
            sendError(exchange, 400, "Parametri di ricerca non validi.");
        } catch (RisorsaNonTrovataException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (SQLException e) {
            sendError(exchange, 500, "Errore interno del server.");
        } catch (RuntimeException e) {
            sendError(exchange, 500, "Errore interno del server.");
        }
    }

    private Map<String, String> parseFiltriPaziente(String query) {
        Map<String, String> filters = new HashMap<>();
        if (query == null || query.isEmpty()) { return filters; }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!List.of("idPaziente", "stato", "data", "dataDa", "dataA").contains(name) || parts.length != 2) {
                throw new IllegalArgumentException();
            }
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (value.isBlank() || filters.putIfAbsent(name, value) != null) { throw new IllegalArgumentException(); }
        }
        return filters;
    }

    private Map<String, String> parseFiltriRichieste(String query) {
        Map<String, String> filters = new HashMap<>();
        if (query == null || query.isEmpty()) { return filters; }
        for (String parameter : query.split("&", -1)) {
            String[] parts = parameter.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!List.of("medicoId", "prestazioneId", "data", "dataDa", "dataA", "stato").contains(name)
                    || parts.length != 2) { throw new IllegalArgumentException(); }
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
        int id = Integer.parseInt(value);
        if (id <= 0) { throw new IllegalArgumentException(); }
        return id;
    }

    private LocalDate parseDataOpzionale(String value) {
        if (value == null) { return null; }
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { throw new IllegalArgumentException(); }
        return LocalDate.parse(value);
    }

    private boolean validId(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0;
    }

    private PrenotazioneResponse toResponse(Prenotazione prenotazione) {
        Disponibilita slot = prenotazione.getDisponibilita();
        Utente medico = slot.getMedico().getUtente();
        PrenotazioneResponse response = new PrenotazioneResponse();
        response.setIdPrenotazione(prenotazione.getIdPrenotazione());
        response.setStato(prenotazione.getStato());
        response.setDataOraRichiesta(prenotazione.getDataOraRichiesta());
        response.setPrezzo(prenotazione.getPrezzo());
        response.setIdDisponibilita(slot.getIdDisponibilita());
        response.setDataOraInizio(slot.getDataOraInizio());
        response.setDataOraFine(slot.getDataOraFine());
        response.setIdPrestazione(slot.getPrestazione().getIdPrestazione());
        response.setNomePrestazione(slot.getPrestazione().getNome());
        response.setIdMedico(medico.getIdUtente());
        response.setNomeMedico(medico.getNome());
        response.setCognomeMedico(medico.getCognome());
        return response;
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] response;
        try {
            response = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // Invia il 500 prima della chiusura dell'exchange, senza ripetere l'operazione.
            status = 500;
            response = "{\"error\":\"Errore interno del server.\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
    }
}
