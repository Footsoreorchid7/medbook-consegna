package it.medbook.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import it.medbook.config.AuthConfig;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.model.RuoloGestionale;
import it.medbook.service.AutenticazioneService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Confine HTTP obbligatorio in Main per ogni controller clinico.
 * I controller sottostanti mantengono parsing completo, proprietà e regole funzionali.
 * Gli ID attore sono ammessi soltanto se uguali all'identità verificata; gli ID risorsa
 * e i filtri desk non sono identità. I profili sono riletti tramite EXISTS nel DB.
 * Nessun lock aggiuntivo: la lettura identità termina prima delle transazioni cliniche.
 * Body ispezionato e riprodotto senza modifiche, massimo 1 MiB (nessuna troncatura).
 * Principal e buffer sono locali all'exchange, mai in attributi di contesto condivisi.
 */
public final class AutorizzazioneHandler implements HttpHandler {
    private static final int MAX_BODY=1024*1024;
    private final HttpHandler delegate;
    private final AutenticazioneService auth;
    private final SessionStore sessions;
    private final AuthConfig config;
    private final SessionSecurity security;
    private final ObjectMapper mapper;
    public AutorizzazioneHandler(HttpHandler delegate,AutenticazioneService auth,SessionStore sessions,
                                  AuthConfig config,ObjectMapper mapper) {
        this.delegate=Objects.requireNonNull(delegate); this.auth=Objects.requireNonNull(auth);
        this.sessions=Objects.requireNonNull(sessions); this.config=Objects.requireNonNull(config);
        this.security=new SessionSecurity(sessions,config); this.mapper=Objects.requireNonNull(mapper);
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        try {
            List<PoliticaAccesso.Regola> routes=PoliticaAccesso.route(exchange.getRequestURI().getRawPath());
            if(routes.isEmpty()) { error(exchange,404,"Percorso non trovato."); return; }
            var rule=routes.stream().filter(r -> r.metodo().equals(exchange.getRequestMethod())).findFirst();
            if(rule.isEmpty()) {
                exchange.getResponseHeaders().set("Allow",routes.stream().map(PoliticaAccesso.Regola::metodo).collect(Collectors.joining(", ")));
                error(exchange,405,"Metodo non consentito."); return;
            }
            var policy=rule.get();
            if(policy.permesso()==PoliticaAccesso.Permesso.PUBBLICO) { delegate.handle(exchange); return; }
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            var found=security.find(exchange);
            if(found.isEmpty()) { unauthenticated(exchange); return; }
            var session=found.get();
            var identity=auth.identity(session);
            if(identity.isEmpty()) { unauthenticated(exchange); return; }
            var user=identity.get();
            if(user.idUtente()==null || user.idUtente()!=session.idUtente()) { throw new IllegalStateException("Identità incoerente."); }
            if(!permitted(user,policy.permesso())) { error(exchange,403,"Operazione non autorizzata."); return; }
            boolean modifying=!policy.metodo().equals("GET");
            if((modifying || exchange.getRequestHeaders().containsKey("Origin")) && !security.validOrigin(exchange)) {
                error(exchange,403,"Richiesta non consentita."); return;
            }
            if(modifying && !security.validCsrf(exchange,session)) {
                error(exchange,403,"Richiesta non consentita."); return;
            }
            byte[] body=null;
            if(policy.attore()!=null) {
                int declared;
                if(policy.body()) {
                    try(InputStream input=exchange.getRequestBody()) { body=input.readNBytes(MAX_BODY+1); }
                    if(body.length>MAX_BODY) { throw new IllegalArgumentException(); }
                    JsonNode json;
                    try {
                        json=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
                    } catch(JsonProcessingException e) {
                        throw new IllegalArgumentException("Richiesta non valida.", e);
                    }
                    JsonNode value=json!=null && json.isObject()?json.get(policy.attore()):null;
                    if(value==null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<=0) {
                        throw new IllegalArgumentException();
                    }
                    declared=value.intValue();
                } else { declared=queryActor(exchange,policy.attore()); }
                boolean deskConsultation=policy.permesso()==PoliticaAccesso.Permesso.MEDICO_O_DESK
                        && user.ruoloGestionale()==RuoloGestionale.OPERATORE_DESK;
                if(!deskConsultation && declared!=user.idUtente()) {
                    error(exchange,403,"Operazione non autorizzata."); return;
                }
            }
            // Ricontrolla scadenza/revoca dopo lettura DB e parsing, prima della delega.
            if(!sessions.touch(session)) { unauthenticated(exchange); return; }
            delegate.handle(new AuthorizedExchange(exchange,body,new HttpPrincipal(user.idUtente().toString(),"MedBook")));
        } catch(IllegalArgumentException e) {
            error(exchange,400,"Richiesta non valida.");
        } catch(JsonProcessingException | SQLException | IllegalStateException e) {
            error(exchange,500,"Errore interno del server.");
        } finally { exchange.close(); }
    }
    private boolean permitted(UtenteAutenticatoResponse user,PoliticaAccesso.Permesso permission) {
        return switch(permission) {
            case PUBBLICO -> true;
            case PAZIENTE -> user.paziente();
            case MEDICO -> user.medico();
            case AMMINISTRATORE -> user.ruoloGestionale()==RuoloGestionale.AMMINISTRATORE;
            case AMMINISTRATORE_O_DESK -> user.ruoloGestionale()==RuoloGestionale.AMMINISTRATORE
                    || user.ruoloGestionale()==RuoloGestionale.OPERATORE_DESK;
            case DESK -> user.ruoloGestionale()==RuoloGestionale.OPERATORE_DESK;
            case MEDICO_O_DESK -> user.medico() || user.ruoloGestionale()==RuoloGestionale.OPERATORE_DESK;
        };
    }
    private int queryActor(HttpExchange exchange,String actor) {
        String query=exchange.getRequestURI().getRawQuery();
        if(query==null || query.isEmpty()) { throw new IllegalArgumentException(); }
        Map<String,String> values=new HashMap<>();
        for(String part:query.split("&",-1)) {
            String[] pair=part.split("=",2);
            if(pair.length!=2) { throw new IllegalArgumentException(); }
            String name=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            String value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            if(values.putIfAbsent(name,value)!=null) { throw new IllegalArgumentException(); }
        }
        String value=values.get(actor);
        if(value==null || !value.matches("[0-9]+")) { throw new IllegalArgumentException(); }
        int id=Integer.parseInt(value); if(id<=0) { throw new IllegalArgumentException(); } return id;
    }
    private void unauthenticated(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Set-Cookie",config.cookie("",true));
        error(exchange,401,"Autenticazione richiesta.");
    }
    private void error(HttpExchange exchange,int status,String message) throws IOException {
        byte[] body;
        try { body=mapper.writeValueAsBytes(Map.of("error",message)); }
        catch(JsonProcessingException e) {
            status=500;
            body="{\"error\":\"Errore interno del server.\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        boolean head=exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status,head?-1:body.length);
        try(OutputStream output=exchange.getResponseBody()) { if(!head) { output.write(body); } }
    }
    private static final class AuthorizedExchange extends HttpExchange {
        private final HttpExchange original;
        private final HttpPrincipal principal;
        private InputStream input;
        AuthorizedExchange(HttpExchange original,byte[] body,HttpPrincipal principal) {
            this.original=original; this.principal=principal;
            this.input=body==null?original.getRequestBody():new ByteArrayInputStream(body);
        }
        @Override public Headers getRequestHeaders(){return original.getRequestHeaders();}
        @Override public Headers getResponseHeaders(){return original.getResponseHeaders();}
        @Override public URI getRequestURI(){return original.getRequestURI();}
        @Override public String getRequestMethod(){return original.getRequestMethod();}
        @Override public HttpContext getHttpContext(){return original.getHttpContext();}
        @Override public void close(){try{input.close();}catch(IOException ignored){} finally{original.close();}}
        @Override public InputStream getRequestBody(){return input;}
        @Override public OutputStream getResponseBody(){return original.getResponseBody();}
        @Override public void sendResponseHeaders(int code,long length)throws IOException{original.sendResponseHeaders(code,length);}
        @Override public InetSocketAddress getRemoteAddress(){return original.getRemoteAddress();}
        @Override public int getResponseCode(){return original.getResponseCode();}
        @Override public InetSocketAddress getLocalAddress(){return original.getLocalAddress();}
        @Override public String getProtocol(){return original.getProtocol();}
        @Override public Object getAttribute(String name){return original.getAttribute(name);}
        @Override public void setAttribute(String name,Object value){original.setAttribute(name,value);}
        @Override public void setStreams(InputStream input,OutputStream output){this.input=input; original.setStreams(input,output);}
        @Override public HttpPrincipal getPrincipal(){return principal;}
    }
}
