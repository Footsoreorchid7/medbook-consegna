package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import it.medbook.exception.*;
import it.medbook.referto.*;
import it.medbook.service.RefertoService;
import java.io.*;
import java.sql.SQLException;
import java.util.*;

/** Upload UC-22 e consultazione UC-09, registrati dietro AutorizzazioneHandler. */
public class RefertoController implements HttpHandler {
    private final RefertoService service;
    private final ObjectMapper mapper;
    public RefertoController(RefertoService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @Override public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            if (!path.matches("/api/prenotazioni/[^/%]+/referto(?:/documento)?")) { error(exchange, 404, "Risorsa non trovata."); return; }
            boolean document = path.endsWith("/documento");
            if ("GET".equals(exchange.getRequestMethod())) { read(exchange, document); return; }
            if (document || !"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", document ? "GET" : "GET, POST"); error(exchange, 405, "Metodo non consentito."); return;
            }
            if (exchange.getPrincipal() == null) { error(exchange, 401, "Autenticazione richiesta."); return; }
            Object result;
            try {
                if (exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
                String raw = path.split("/")[3];
                if (!raw.matches("[1-9][0-9]*")) { throw new IllegalArgumentException(); }
                int booking = Integer.parseInt(raw);
                int doctor = Integer.parseInt(exchange.getPrincipal().getUsername());
                String type = single(exchange, "Content-Type");
                if (!"application/pdf".equalsIgnoreCase(type)) { error(exchange, 415, "Tipo di contenuto non supportato."); return; }
                String encoding = single(exchange, "Content-Encoding");
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) { throw new IllegalArgumentException(); }
                String rawLength = single(exchange, "Content-Length");
                Long length = null;
                if (rawLength != null) {
                    if (!rawLength.matches("[0-9]+") || single(exchange, "Transfer-Encoding") != null) { throw new IllegalArgumentException(); }
                    length = Long.parseLong(rawLength);
                }
                if (service == null) { error(exchange, 500, "Errore interno del server."); return; }
                try (InputStream body = exchange.getRequestBody()) { result = service.upload(booking, doctor, body, length); }
            } catch (RisorsaNonTrovataException e) { error(exchange, 404, "Prenotazione non trovata."); return; }
              catch (ConflittoPrenotazioneException e) { error(exchange, 409, e.getMessage()); return; }
              // UC-23: permesso MEDICO perso tra il controllo HTTP e la transazione.
              catch (OperazioneNonAutorizzataException e) { error(exchange, 403, e.getMessage()); return; }
              catch (UploadNonValidoException e) { error(exchange, e.getStatus(), "Caricamento non valido."); return; }
              catch (PdfRifiutatoException | IllegalArgumentException e) { error(exchange, 400, "Documento o richiesta non validi."); return; }
              catch (SQLException | IOException | RuntimeException e) { error(exchange, 500, "Errore interno del server."); return; }
            // Serializzazione fuori dalla gestione dei dati di input: un suo errore non è un HTTP 400.
            byte[] response;
            try { response = mapper.writeValueAsBytes(result); }
            catch (IOException e) { error(exchange, 500, "Errore interno del server."); return; }
            send(exchange, 201, response);
        }
    }
    private void read(HttpExchange exchange, boolean document) throws IOException {
        if (exchange.getPrincipal() == null) { error(exchange, 401, "Autenticazione richiesta."); return; }
        boolean headersSent = false;
        try {
            String raw = exchange.getRequestURI().getRawPath().split("/")[3];
            if (!raw.matches("[1-9][0-9]*") || exchange.getRequestURI().getRawQuery() != null) { throw new IllegalArgumentException(); }
            int booking = Integer.parseInt(raw);
            int patient = Integer.parseInt(exchange.getPrincipal().getUsername());
            String length = single(exchange, "Content-Length");
            if ((length != null && !length.equals("0")) || single(exchange, "Transfer-Encoding") != null) { throw new IllegalArgumentException(); }
            if (exchange.getRequestBody().read() != -1) { throw new IllegalArgumentException(); }
            if (!document) {
                byte[] bytes = mapper.writeValueAsBytes(service.metadata(booking, patient));
                headersSent = true; send(exchange, 200, bytes); return;
            }
            try (RefertoStorage.Download download = service.download(booking, patient)) {
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"referto-" + booking + ".pdf\"");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                if (download.size() <= 0) { throw new IOException("Documento vuoto."); }
                exchange.getResponseHeaders().set("Content-Length", Long.toString(download.size()));
                headersSent = true;
                exchange.sendResponseHeaders(200, download.size());
                try (InputStream input = download.input(); OutputStream output = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) { output.write(buffer, 0, n); }
                }
            }
        } catch (RisorsaNonTrovataException e) {
            if (!headersSent) { error(exchange, 404, "Referto non trovato."); }
        } catch (IllegalArgumentException e) {
            if (!headersSent) { error(exchange, 400, "Richiesta non valida."); }
        } catch (SQLException | IOException | RuntimeException e) {
            if (!headersSent) { error(exchange, 500, "Errore interno del server."); }
        }
        // Dopo gli header si chiude lo scambio: mai una seconda risposta JSON.
    }
    private static String single(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null) { return null; }
        if (values.size() != 1) { throw new IllegalArgumentException(); }
        return values.getFirst();
    }
    private void error(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, mapper.writeValueAsBytes(Map.of("error", text)));
    }
    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
