package it.medbook.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.medbook.dto.*;
import it.medbook.exception.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PoliticaAccesso;
import it.medbook.service.MedicoService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.*;

/* Registrato solo dietro autorizzazione; identità mai dal payload. */
public final class MedicoController implements HttpHandler {
    private final MedicoService service;
    private final UtenteRepository identities;
    private final ObjectMapper mapper;
    public MedicoController(MedicoService service, UtenteRepository identities, ObjectMapper mapper) {
        this.service=Objects.requireNonNull(service); this.identities=Objects.requireNonNull(identities); this.mapper=Objects.requireNonNull(mapper);
    }
    @Override public void handle(HttpExchange e)throws IOException {
        try(e) {
            try { route(e); }
            catch (BodyTooLarge x) { error(e,413,"Corpo della richiesta troppo grande."); }
            catch (IllegalArgumentException x) { error(e,400,"Richiesta non valida."); }
            catch (OperazioneNonAutorizzataException x) { error(e,403,"Operazione non autorizzata."); }
            catch (RisorsaNonTrovataException x) { error(e,404,"Risorsa non trovata."); }
            catch (ConflittoMedicoException | GenerazioneEsauritaException x) { error(e,409,"Operazione in conflitto."); }
            catch (UtenteRepository.LockAccountTimeoutException x) {
                if(e.getResponseCode()<=0)e.getResponseHeaders().set("Retry-After","5");
                error(e,503,"Servizio temporaneamente non disponibile.");
            }
            catch (SQLException | RuntimeException x) { error(e,500,"Errore interno del server."); }
        }
    }
    private void route(HttpExchange e)throws IOException,SQLException {
        String path=e.getRequestURI().getRawPath(),method=e.getRequestMethod();
        if(!path.equals("/api/medici")&&!PoliticaAccesso.isMedicoPath(path)) { error(e,404,"Percorso non trovato.");return; }
        var rules=PoliticaAccesso.route(path);
        if(rules.stream().noneMatch(r->r.metodo().equals(method))) {
            e.getResponseHeaders().set("Allow",String.join(", ",rules.stream().map(PoliticaAccesso.Regola::metodo).toList()));
            error(e,405,"Metodo non consentito.");return;
        }
        if(e.getPrincipal()==null) { error(e,401,"Autenticazione richiesta.");return; }
        int actor=Integer.parseInt(e.getPrincipal().getUsername());
        Integer id=path.equals("/api/medici")?null:Integer.valueOf(path.split("/")[3]);
        if(method.equals("GET")) {
            if(id!=null&&e.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException();
            var filters=id==null?filters(e.getRequestURI().getRawQuery()):Map.<String,String>of();
            String state=filters.get("stato");Integer prest=filters.containsKey("idPrestazione")?positive(filters.get("idPrestazione")):null;
            var identity=identities.findById(actor).orElseThrow(()->new OperazioneNonAutorizzataException("Operazione non autorizzata."));
            var role=identity.ruoloGestionale(); Object result;
            /* Il repository scelto riverifica il ruolo esatto; mai riprovare con altra proiezione. */
            if(role==RuoloGestionale.AMMINISTRATORE) {
                result=id==null?service.elencaPerAmministratore(actor,state,prest):
                        new DettaglioMedicoAdminResponse(service.dettaglioPerAmministratore(actor,id),service.consultaAbilitazioni(actor,id));
            } else if(role==RuoloGestionale.OPERATORE_DESK) {
                result=id==null?service.elencaPerDesk(actor,state,prest):
                        new DettaglioMedicoDeskResponse(service.dettaglioPerDesk(actor,id),service.consultaAbilitazioni(actor,id),service.profiloPazientePerDesk(actor,id));
            } else throw new OperazioneNonAutorizzataException("Operazione non autorizzata.");
            /* Rileva cambio ruolo anche fra le due letture del dettaglio e scarta il DTO composto. */
            var current=identities.findById(actor);
            if(current.isEmpty() || current.get().ruoloGestionale()!=role)throw new OperazioneNonAutorizzataException("Operazione non autorizzata.");
            send(e,200,result);return;
        }
        if(e.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException();
        var types=e.getRequestHeaders().get("Content-Type");
        if(types==null || types.size()!=1 || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|\"UTF-8\"))?\\s*")) {
            error(e,415,"Content-Type non supportato.");return;
        }
        JsonNode j=read(e); Object result;int status=200;
        if(id==null) {
            fields(j,"nome","cognome","email","password","specializzazione","numeroIscrizioneOrdine");
            result=service.crea(actor,new NuovoMedicoRequest(text(j,"nome"),text(j,"cognome"),text(j,"email"),text(j,"password"),text(j,"specializzazione"),text(j,"numeroIscrizioneOrdine")));status=201;
        } else if(path.endsWith("/collegamento")) {
            fields(j,"specializzazione","numeroIscrizioneOrdine");
            result=service.collega(actor,id,new CollegamentoMedicoRequest(text(j,"specializzazione"),text(j,"numeroIscrizioneOrdine")));status=201;
        } else if(path.endsWith("/password")) {
            fields(j,"nuovaPassword");
            char[] password = text(j,"nuovaPassword").toCharArray();
            ((com.fasterxml.jackson.databind.node.ObjectNode)j).remove("nuovaPassword");
            try { result=service.reimpostaPassword(actor,id,password); }
            finally { Arrays.fill(password,'\0'); }
        } else if(path.endsWith("/stato")) {
            fields(j,"stato");result=service.cambiaStatoMedico(actor,id,text(j,"stato"));
        } else if(path.endsWith("/prestazioni")) {
            fields(j,"operazione","idPrestazione");String op=text(j,"operazione");var p=j.get("idPrestazione");
            if(!p.isIntegralNumber() || !p.canConvertToInt() || p.intValue()<=0)throw new IllegalArgumentException();
            if(op.equals("ASSEGNA"))service.assegnaPrestazione(actor,id,p.intValue());
            else if(op.equals("RIMUOVI"))service.rimuoviPrestazione(actor,id,p.intValue());else throw new IllegalArgumentException();
            result=new OperazionePrestazioneMedicoResponse(id,p.intValue(),op);
        } else {
            String type=text(j,"tipo");
            if(type.equals("PERSONALI")) {
                fields(j,"tipo","nome","cognome","email");
                result=service.aggiornaDatiPersonali(actor,id,new DatiPersonaliMedicoRequest(text(j,"nome"),text(j,"cognome"),text(j,"email")));
            } else if(type.equals("PROFESSIONALI")) {
                fields(j,"tipo","specializzazione","numeroIscrizioneOrdine");
                result=service.aggiornaDatiProfessionali(actor,id,new DatiProfessionaliMedicoRequest(text(j,"specializzazione"),text(j,"numeroIscrizioneOrdine")));
            } else throw new IllegalArgumentException();
        }
        send(e,status,result);
    }
    private static Map<String,String> filters(String query) {
        Map<String,String> result=new HashMap<>();if(query==null)return result;
        for(String part:query.split("&",-1)) {
            String[] kv=part.split("=",2);if(kv.length!=2)throw new IllegalArgumentException();
            String key=URLDecoder.decode(kv[0],StandardCharsets.UTF_8),value=URLDecoder.decode(kv[1],StandardCharsets.UTF_8);
            if(!Set.of("stato","idPrestazione").contains(key)||value.isEmpty()||result.putIfAbsent(key,value)!=null)throw new IllegalArgumentException();
        }
        return result;
    }
    private static int positive(String value) {
        if(!value.matches("[1-9][0-9]{0,9}"))throw new IllegalArgumentException();int id=Integer.parseInt(value);if(id<=0)throw new IllegalArgumentException();return id;
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
