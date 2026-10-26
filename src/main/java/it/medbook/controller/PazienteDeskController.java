package it.medbook.controller;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.service.PazienteDeskService;
import it.medbook.repository.UtenteRepository;
import it.medbook.exception.*;
import it.medbook.security.PoliticaAccesso;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.SQLException;
import java.util.*;

/** Contesto dedicato dietro AutorizzazioneHandler: nessun endpoint amministrativo aperto al desk. */
public final class PazienteDeskController implements HttpHandler {
    private final PazienteDeskService service; private final ObjectMapper mapper;
    public PazienteDeskController(PazienteDeskService service,ObjectMapper mapper){this.service=Objects.requireNonNull(service);this.mapper=Objects.requireNonNull(mapper);}
    @Override public void handle(HttpExchange e)throws IOException {
        try(e) {
            try { route(e); }
            catch(BodyTooLarge x){error(e,413,"Corpo della richiesta troppo grande.");}
            catch(IllegalArgumentException x){error(e,400,"Richiesta non valida.");}
            catch(OperazioneNonAutorizzataException x){error(e,403,"Operazione non autorizzata.");}
            catch(RisorsaNonTrovataException x){error(e,404,"Paziente non trovato.");}
            catch(ConflittoProfiloPazienteException x){error(e,409,x.getMessage());}
            catch(GenerazioneEsauritaException x){error(e,409,"Operazione in conflitto.");}
            catch(UtenteRepository.LockAccountTimeoutException x){if(e.getResponseCode()<=0)e.getResponseHeaders().set("Retry-After","5");error(e,503,"Servizio temporaneamente non disponibile.");}
            catch(SQLException | RuntimeException x){error(e,500,"Errore interno del server.");}
        }
    }
    private void route(HttpExchange e)throws IOException,SQLException {
        String path=e.getRequestURI().getRawPath(),method=e.getRequestMethod();
        boolean base=path.equals("/api/pazienti");
        boolean collega=PoliticaAccesso.isPazienteCollegamentoPath(path);
        if(!base&&!collega&&!PoliticaAccesso.isPazientePasswordPath(path)){error(e,404,"Percorso non trovato.");return;}
        String expected=base?"GET":collega?"POST":"PUT";
        if(!expected.equals(method)){e.getResponseHeaders().set("Allow",expected);error(e,405,"Metodo non consentito.");return;}
        if(e.getPrincipal()==null){error(e,401,"Autenticazione richiesta.");return;}
        int actor=Integer.parseInt(e.getPrincipal().getUsername());
        if(base){String q=e.getRequestURI().getRawQuery();send(e,200,service.cerca(actor,q==null?Map.of():UtenteController.parametri(q)));return;}
        if(e.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException();
        var types=e.getRequestHeaders().get("Content-Type");
        if(types==null||types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\"))?\\s*")){error(e,415,"Content-Type non supportato.");return;}
        JsonNode j=read(e);
        if(collega) {
            Set<String> allowed=Set.of("codiceFiscale","dataNascita","telefono");
            j.fieldNames().forEachRemaining(n->{if(!allowed.contains(n))throw new IllegalArgumentException();});
            String cf=text(j,"codiceFiscale"),date=text(j,"dataNascita");
            if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();
            java.time.LocalDate nascita;
            try{nascita=java.time.LocalDate.parse(date);}catch(java.time.DateTimeException x){throw new IllegalArgumentException();}
            String tel=j.hasNonNull("telefono")?text(j,"telefono"):null;
            send(e,201,service.collega(actor,Integer.parseInt(path.split("/")[3]),cf,nascita,tel));return;
        }
        fields(j,"nuovaPassword");char[] password=text(j,"nuovaPassword").toCharArray();
        ((com.fasterxml.jackson.databind.node.ObjectNode)j).remove("nuovaPassword");
        try{send(e,200,service.reset(actor,Integer.parseInt(path.split("/")[3]),password));}
        finally{Arrays.fill(password,'\0');}
    }
    private JsonNode read(HttpExchange e)throws IOException {
        byte[] bytes;try(var input=e.getRequestBody()){bytes=input.readNBytes(8193);}
        if(bytes.length>8192)throw new BodyTooLarge();if(bytes.length==0)throw new IllegalArgumentException();
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException x){throw new IllegalArgumentException();}
        JsonNode j;
        try{j=mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);}
        catch(JsonProcessingException x){throw new IllegalArgumentException();}
        if(j==null||!j.isObject())throw new IllegalArgumentException();return j;
    }
    private static void fields(JsonNode j,String...names) {
        var allowed=Set.of(names);if(j.size()!=allowed.size())throw new IllegalArgumentException();
        j.fieldNames().forEachRemaining(n->{if(!allowed.contains(n))throw new IllegalArgumentException();});
        for(String n:names)if(!j.hasNonNull(n))throw new IllegalArgumentException();
    }
    private static String text(JsonNode j,String key) {
        var n=j.get(key);if(n==null||!n.isTextual())throw new IllegalArgumentException();return n.textValue();
    }
    private static final class BodyTooLarge extends RuntimeException { }
    private void error(HttpExchange e,int status,String message)throws IOException {if(e.getResponseCode()<=0)send(e,status,Map.of("error",message));}
    private void send(HttpExchange e,int status,Object value)throws IOException {
        if(e.getResponseCode()>0)return;
        byte[] bytes;
        try{bytes=mapper.writeValueAsBytes(value);}catch(JsonProcessingException x){status=500;bytes="{\"error\":\"Errore interno del server.\"}".getBytes(StandardCharsets.UTF_8);}
        e.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        e.sendResponseHeaders(status,e.getRequestMethod().equals("HEAD")?-1:bytes.length);
        try(var out=e.getResponseBody()){if(!e.getRequestMethod().equals("HEAD"))out.write(bytes);}
    }
}
