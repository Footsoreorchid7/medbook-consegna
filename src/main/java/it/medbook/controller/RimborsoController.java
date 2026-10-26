package it.medbook.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.exception.*;
import it.medbook.dto.RichiestaRimborsoRequest;
import it.medbook.service.RimborsoService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/** Registrato esclusivamente dietro AutorizzazioneHandler: identità dal Principal di UC-02. */
public class RimborsoController implements HttpHandler {
    private final RimborsoService service;
    private final ObjectMapper mapper;
    public RimborsoController(RimborsoService service,ObjectMapper mapper) {
        this.service=Objects.requireNonNull(service);this.mapper=Objects.requireNonNull(mapper);
    }
    @Override public void handle(HttpExchange exchange)throws IOException {
        try(exchange) {
            String path=exchange.getRequestURI().getRawPath();
            boolean patient=path.equals("/api/rimborsi/paziente"),desk=path.equals("/api/rimborsi/richieste");
            boolean storico=path.equals("/api/rimborsi/desk");
            boolean decision=path.matches("/api/rimborsi/[^/]+/(approva|respingi)");
            if(!patient&&!desk&&!storico&&!decision){error(exchange,404,"Percorso non trovato.");return;}
            String method=decision?"POST":"GET";
            if(!exchange.getRequestMethod().equals(method)){exchange.getResponseHeaders().set("Allow",method);error(exchange,405,"Metodo non consentito.");return;}
            execute(exchange,()-> {
                int actor=actor(exchange);
                if(storico) {
                    String raw=exchange.getRequestURI().getRawQuery();
                    Map<String,String> query=raw==null?Map.of():UtenteController.parametri(raw);
                    if(!Set.of("stato").containsAll(query.keySet()))throw invalid();
                    return service.getStoricoDesk(actor,query.get("stato"));
                }
                noQuery(exchange);
                if(patient)return service.getRimborsiPaziente(actor);
                if(desk)return service.getRichieste();
                int id=pathId(path);JsonNode body=body(exchange);
                if(!body.isObject()||!body.isEmpty())throw invalid();
                return path.endsWith("/approva")?service.approva(id,actor):service.respingi(id,actor);
            },200);
        }
    }
    /** La rotta di creazione resta sotto /api/prenotazioni, senza duplicare il parsing. */
    public void handleRichiesta(HttpExchange exchange)throws IOException {
        execute(exchange,()-> {
            noQuery(exchange);int actor=actor(exchange);int id=pathId(exchange.getRequestURI().getRawPath());
            JsonNode body=body(exchange);
            if(!body.isObject()||body.size()!=1||!body.has("motivazione")||!body.get("motivazione").isTextual())throw invalid();
            RichiestaRimborsoRequest request=new RichiestaRimborsoRequest();request.setMotivazione(body.get("motivazione").textValue());
            return service.richiedi(id,actor,request.getMotivazione());
        },201);
    }
    private JsonNode body(HttpExchange exchange)throws IOException {
        byte[] bytes;try(InputStream input=exchange.getRequestBody()){bytes=input.readNBytes(8193);}
        if(bytes.length>8192)throw invalid();
        try {
            JsonNode node=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if(node==null)throw invalid();return node;
        }catch(JsonProcessingException e){throw invalid();}
    }
    private static void noQuery(HttpExchange e){if(e.getRequestURI().getRawQuery()!=null)throw invalid();}
    private static int actor(HttpExchange e) {
        if(e.getPrincipal()==null)throw new MissingSession();
        try {int id=Integer.parseInt(e.getPrincipal().getUsername());RimborsoService.validaId(id);return id;}
        catch(IllegalArgumentException ex){throw new MissingSession();}
    }
    private static int pathId(String path) {
        String raw=path.split("/",-1)[3];if(!raw.matches("[0-9]+"))throw invalid();
        int id=Integer.parseInt(raw);RimborsoService.validaId(id);return id;
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
