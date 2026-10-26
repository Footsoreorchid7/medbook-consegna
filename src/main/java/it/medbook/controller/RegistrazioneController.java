package it.medbook.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.config.AuthConfig;
import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.exception.RegistrazioneDuplicataException;
import it.medbook.security.LoginThrottle;
import it.medbook.service.RegistrazioneService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Endpoint pubblico esatto, senza auto-login. Origin esatta + JSON obbligatorio,
 * nessun CORS: protezione delle richieste anonime coerente con login UC-02.
 * Throttle separato dal login; nessuna identità viene dedotta dal body o dai cookie.
 */
public final class RegistrazioneController implements HttpHandler {
    private static final int MAX_BODY=8192;
    private static final Set<String> REQUIRED=Set.of("nome","cognome","email","password","codiceFiscale","dataNascita");
    private final RegistrazioneService service;
    private final LoginThrottle throttle;
    private final AuthConfig config;
    private final ObjectMapper mapper;
    public RegistrazioneController(RegistrazioneService service,LoginThrottle throttle,AuthConfig config,ObjectMapper mapper){
        this.service=Objects.requireNonNull(service);this.throttle=Objects.requireNonNull(throttle);
        this.config=Objects.requireNonNull(config);this.mapper=Objects.requireNonNull(mapper);
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
        try{
            if(!exchange.getRequestURI().getRawPath().equals("/api/auth/register")){send(exchange,404,"Risorsa non trovata.");return;}
            if(!exchange.getRequestMethod().equals("POST")){exchange.getResponseHeaders().set("Allow","POST");send(exchange,405,"Metodo non consentito.");return;}
            if(exchange.getRequestURI().getRawQuery()!=null){throw new IllegalArgumentException();}
            if(!config.origin().equals(single(exchange,"Origin"))){send(exchange,403,"Richiesta non consentita.");return;}
            String peer=exchange.getRemoteAddress()==null?"unknown":exchange.getRemoteAddress().getAddress().getHostAddress();
            if(!throttle.allow(peer)){exchange.getResponseHeaders().set("Retry-After","300");send(exchange,429,"Troppi tentativi. Riprova più tardi.");return;}
            try(RegistrazionePazienteRequest request=read(exchange)){service.registra(request);}
            send(exchange,201,"Registrazione completata. Accedi per continuare.");
        }catch(JsonProcessingException | IllegalArgumentException | DateTimeParseException e){send(exchange,400,"Dati di registrazione non validi.");
        }catch(RegistrazioneDuplicataException e){send(exchange,409,"Registrazione non disponibile con i dati forniti.");
        }catch(SQLException | IllegalStateException e){send(exchange,500,"Errore interno del server.");
        }finally{try{exchange.getRequestBody().close();}finally{exchange.close();}}
    }
    private RegistrazionePazienteRequest read(HttpExchange exchange) throws IOException {
        String type=single(exchange,"Content-Type");
        if(type==null || !type.split(";",2)[0].strip().equalsIgnoreCase("application/json")){throw new IllegalArgumentException();}
        byte[] body;
        try(InputStream input=exchange.getRequestBody()){body=input.readNBytes(MAX_BODY+1);}
        if(body.length>MAX_BODY){throw new IllegalArgumentException();}
        JsonNode json=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        if(json==null || !json.isObject()){throw new IllegalArgumentException();}
        var names=new HashSet<String>();json.fieldNames().forEachRemaining(names::add);
        if(!names.containsAll(REQUIRED) || names.stream().anyMatch(n -> !REQUIRED.contains(n)&&!n.equals("telefono"))){throw new IllegalArgumentException();}
        for(String name:REQUIRED){if(!json.get(name).isTextual()){throw new IllegalArgumentException();}}
        JsonNode phone=json.get("telefono");if(phone!=null&&!phone.isNull()&&!phone.isTextual()){throw new IllegalArgumentException();}
        String date=json.get("dataNascita").textValue();if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")){throw new IllegalArgumentException();}
        LocalDate dataNascita=LocalDate.parse(date);
        return new RegistrazionePazienteRequest(json.get("nome").textValue(),json.get("cognome").textValue(),json.get("email").textValue(),
                json.get("password").textValue().toCharArray(),json.get("codiceFiscale").textValue(),dataNascita,phone==null||phone.isNull()?null:phone.textValue());
    }
    private String single(HttpExchange exchange,String name){var values=exchange.getRequestHeaders().get(name);return values!=null&&values.size()==1?values.getFirst():null;}
    private void send(HttpExchange exchange,int status,String message) throws IOException {
        byte[] body;
        try{body=mapper.writeValueAsBytes(Map.of(status==201?"message":"error",message));}
        catch(JsonProcessingException e){status=500;body="{\"error\":\"Errore interno del server.\"}".getBytes(StandardCharsets.UTF_8);}
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");boolean head=exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status,head?-1:body.length);
        try(OutputStream output=exchange.getResponseBody()){if(!head){output.write(body);}}
    }
}
