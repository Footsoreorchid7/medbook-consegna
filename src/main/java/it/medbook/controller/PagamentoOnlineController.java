package it.medbook.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.exception.*;
import it.medbook.service.PagamentoService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.*;

/** Delegato di PrenotazioneController; sessione, Origin e CSRF sono verificati da UC-02. */
public final class PagamentoOnlineController implements HttpHandler {
    private final PagamentoService service;
    private final ObjectMapper mapper;
    public PagamentoOnlineController(PagamentoService service, ObjectMapper mapper) {
        this.service = service; this.mapper = Objects.requireNonNull(mapper);
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        boolean post = path.matches("/api/prenotazioni/[^/]+/pagamento-online");
        boolean get = path.matches("/api/prenotazioni/[^/]+/pagamenti");
        if (!post && !get) { error(exchange,404,"Percorso non trovato."); return; }
        String method = post ? "POST" : "GET";
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow",method); error(exchange,405,"Metodo non consentito."); return;
        }
        execute(exchange, () -> {
            noQuery(exchange); int patient = actor(exchange); int booking = pathId(path);
            if (service == null) throw new IllegalStateException("PagamentoService non configurato.");
            if (get) return service.getPagamenti(booking,patient);
            JsonNode json = body(exchange);
            if (!json.isObject() || json.size()!=1 || !json.has("importoAtteso") || !json.get("importoAtteso").isNumber()) throw invalid();
            BigDecimal amount = new BigDecimal(json.get("importoAtteso").asText());
            return service.pagaOnline(booking,patient,amount);
        }, post ? 201 : 200);
    }
    private JsonNode body(HttpExchange exchange)throws IOException {
        byte[] bytes;try(InputStream input=exchange.getRequestBody()){bytes=input.readNBytes(8193);}
        if(bytes.length>8192)throw invalid();
        try {
            JsonNode node=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(bytes);
            if(node==null)throw invalid();return node;
        }catch(JsonProcessingException e){throw invalid();}
    }
    private static void noQuery(HttpExchange e){if(e.getRequestURI().getRawQuery()!=null)throw invalid();}
    private static int actor(HttpExchange e) {
        if(e.getPrincipal()==null)throw new MissingSession();
        try {int id=Integer.parseInt(e.getPrincipal().getUsername());if (id <= 0) throw invalid();return id;}
        catch(IllegalArgumentException ex){throw new MissingSession();}
    }
    private static int pathId(String path) {
        String raw=path.split("/",-1)[3];if(!raw.matches("[0-9]+"))throw invalid();
        int id=Integer.parseInt(raw);if (id <= 0) throw invalid();return id;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Richiesta non valida.");}
    private static final class MissingSession extends RuntimeException { }
    @FunctionalInterface private interface Action {Object run()throws IOException,SQLException;}
    private void execute(HttpExchange e,Action action,int success)throws IOException {
        Object response;int status=success;
        try {response=action.run();}
        catch(MissingSession ex){status=401;response=Map.of("error","Autenticazione richiesta.");}
        catch(IllegalArgumentException ex){status=400;response=Map.of("error","Richiesta non valida.");}
        catch(RisorsaNonTrovataException ex){status=404;response=Map.of("error","Risorsa non trovata.");}
        catch(OperazioneNonAutorizzataException ex){status=403;response=Map.of("error","Operazione non autorizzata.");}
        catch(ConflittoPrenotazioneException ex){status=409;response=Map.of("error",ex.getMessage());}
        catch(SQLException|IllegalStateException ex){status=500;response=Map.of("error","Errore interno del server.");}
        send(e,status,response);
    }
    private void error(HttpExchange e,int code,String message)throws IOException {send(e,code,Map.of("error",message));}
    private void send(HttpExchange e,int status,Object value)throws IOException {
        byte[] bytes;
        try{bytes=mapper.writeValueAsBytes(value);}
        catch(JsonProcessingException ex){status=500;bytes="{\"error\":\"Errore interno del server.\"}".getBytes(StandardCharsets.UTF_8);}
        e.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        e.sendResponseHeaders(status,e.getRequestMethod().equals("HEAD")?-1:bytes.length);
        try(OutputStream out=e.getResponseBody()){if(!e.getRequestMethod().equals("HEAD"))out.write(bytes);}
    }
}
