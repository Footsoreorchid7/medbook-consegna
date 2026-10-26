package it.medbook.security;

import java.util.List;

/** Whitelist esatta delle API cliniche: tutto il resto è rifiutato, senza normalizzare percorsi. */
public final class PoliticaAccesso {
    private PoliticaAccesso() { }
    public enum Permesso { PUBBLICO, PAZIENTE, MEDICO, DESK, MEDICO_O_DESK, AMMINISTRATORE, AMMINISTRATORE_O_DESK }
    public record Regola(String metodo,Permesso permesso,String attore,boolean body) { }
    public static List<Regola> route(String path) {
        return switch(path) {
            case "/api/medici" -> List.of(new Regola("GET",Permesso.AMMINISTRATORE_O_DESK,null,false),new Regola("POST",Permesso.AMMINISTRATORE,null,false));
            case "/api/utenti" -> List.of(new Regola("GET",Permesso.AMMINISTRATORE,null,false),new Regola("POST",Permesso.AMMINISTRATORE,null,false));
            case "/api/pazienti" -> List.of(new Regola("GET",Permesso.DESK,null,false));
            case "/api/utenti/ricerca" -> List.of(new Regola("GET",Permesso.AMMINISTRATORE,null,false));
            case "/api/stanze" -> List.of(
                    new Regola("GET",Permesso.AMMINISTRATORE_O_DESK,null,false),
                    new Regola("POST",Permesso.AMMINISTRATORE,null,false));
            case "/api/prestazioni" -> List.of(
                    new Regola("GET",Permesso.PUBBLICO,null,false),
                    new Regola("POST",Permesso.AMMINISTRATORE,null,false));
            case "/api/disponibilita/prenotabili" ->
                    List.of(new Regola("GET",Permesso.PUBBLICO,null,false));
            case "/api/disponibilita" -> List.of(
                    new Regola("GET",Permesso.MEDICO_O_DESK,"medicoId",false),
                    new Regola("POST",Permesso.MEDICO,"idMedico",true));
            case "/api/rimborsi/paziente" -> List.of(new Regola("GET",Permesso.PAZIENTE,null,false));
            case "/api/rimborsi/richieste", "/api/rimborsi/desk" -> List.of(new Regola("GET",Permesso.DESK,null,false));
            case "/api/prenotazioni" -> List.of(new Regola("POST",Permesso.PAZIENTE,"idPaziente",true));
            case "/api/prenotazioni/paziente" -> List.of(new Regola("GET",Permesso.PAZIENTE,"idPaziente",false));
            case "/api/prenotazioni/agenda" -> List.of(new Regola("GET",Permesso.MEDICO,"medicoId",false));
            case "/api/prenotazioni/richieste", "/api/prenotazioni/segnalazioni-indisponibilita",
                 "/api/prenotazioni/desk" ->
                    List.of(new Regola("GET",Permesso.DESK,null,false));
            case "/api/prenotazioni/segnala-indisponibilita" ->
                    List.of(new Regola("POST",Permesso.MEDICO,"medicoId",true));
            default -> dynamicRoute(path);
        };
    }
    /** Percorso risorsa canonico: Integer positivo senza encoding o alias con zeri iniziali. */
    public static boolean isPrestazioneResourcePath(String path) {
        if (path == null || !path.matches("/api/prestazioni/[1-9][0-9]{0,9}")) { return false; }
        try { return Integer.parseInt(path.substring("/api/prestazioni/".length())) > 0; }
        catch (NumberFormatException e) { return false; }
    }
    public static boolean isStanzaResourcePath(String path) {
        if (path == null || !path.matches("/api/stanze/[1-9][0-9]{0,9}")) { return false; }
        try { return Integer.parseInt(path.substring("/api/stanze/".length())) > 0; }
        catch (NumberFormatException e) { return false; }
    }
    public static boolean isUtenteRuoloPath(String path) {
        if(path==null || !path.matches("/api/utenti/[1-9][0-9]{0,9}/ruolo"))return false;
        try{return Integer.parseInt(path.substring("/api/utenti/".length(),path.length()-"/ruolo".length()))>0;}
        catch(NumberFormatException e){return false;}
    }
    /** Same canonical ID rule as {@link #isUtenteRuoloPath}. */
    public static boolean isUtenteStatoPath(String path) {
        if(path==null || !path.matches("/api/utenti/[1-9][0-9]{0,9}/stato"))return false;
        try{return Integer.parseInt(path.substring("/api/utenti/".length(),path.length()-"/stato".length()))>0;}
        catch(NumberFormatException e){return false;}
    }
    public static boolean isMedicoPath(String path) {
        if (path==null || !path.matches("/api/medici/[1-9][0-9]{0,9}(?:/(?:collegamento|stato|prestazioni|password))?")) return false;
        try { return Integer.parseInt(path.split("/")[3])>0; }
        catch (NumberFormatException e) { return false; }
    }
    public static boolean isPazientePasswordPath(String path) {
        if(path==null || !path.matches("/api/pazienti/[1-9][0-9]{0,9}/password"))return false;
        try{return Integer.parseInt(path.split("/")[3])>0;}catch(NumberFormatException e){return false;}
    }
    public static boolean isPazienteCollegamentoPath(String path) {
        if(path==null || !path.matches("/api/pazienti/[1-9][0-9]{0,9}/collegamento"))return false;
        try{return Integer.parseInt(path.split("/")[3])>0;}catch(NumberFormatException e){return false;}
    }
    private static List<Regola> dynamicRoute(String path) {
        if(isPazienteCollegamentoPath(path))return List.of(new Regola("POST",Permesso.DESK,null,false));
        if(isPazientePasswordPath(path))return List.of(new Regola("PUT",Permesso.DESK,null,false));
        if(isMedicoPath(path)) {
            if(path.endsWith("/collegamento")) return List.of(new Regola("POST",Permesso.AMMINISTRATORE,null,false));
            if(path.endsWith("/stato") || path.endsWith("/prestazioni") || path.endsWith("/password")) return List.of(new Regola("PUT",Permesso.AMMINISTRATORE,null,false));
            return List.of(new Regola("GET",Permesso.AMMINISTRATORE_O_DESK,null,false),new Regola("PUT",Permesso.AMMINISTRATORE,null,false));
        }
        if(isUtenteRuoloPath(path) || isUtenteStatoPath(path))return List.of(new Regola("PUT",Permesso.AMMINISTRATORE,null,false));
        if(isStanzaResourcePath(path)) {
            return List.of(new Regola("PUT",Permesso.AMMINISTRATORE,null,false));
        }
        if(isPrestazioneResourcePath(path)) {
            return List.of(new Regola("PUT",Permesso.AMMINISTRATORE,null,false));
        }
        if(path.matches("/api/prenotazioni/[^/%]+/referto")) {
            return List.of(new Regola("GET",Permesso.PAZIENTE,null,false),
                    new Regola("POST",Permesso.MEDICO,null,false));
        }
        if(path.matches("/api/prenotazioni/[^/%]+/referto/documento")) {
            return List.of(new Regola("GET",Permesso.PAZIENTE,null,false));
        }
        if(path.matches("/api/prenotazioni/[^/%]+/registra-visita")) {
            return List.of(new Regola("POST",Permesso.MEDICO,null,true));
        }
        if(path.matches("/api/prenotazioni/[^/]+/pagamento-online")) {
            return List.of(new Regola("POST",Permesso.PAZIENTE,null,true));
        }
        if(path.matches("/api/prenotazioni/[^/]+/pagamenti")) {
            return List.of(new Regola("GET",Permesso.PAZIENTE,null,false));
        }
        if(path.matches("/api/prenotazioni/[^/]+/rimborso")) {
            return List.of(new Regola("POST",Permesso.PAZIENTE,null,true));
        }
        if(path.matches("/api/rimborsi/[^/]+/(approva|respingi)")) {
            return List.of(new Regola("POST",Permesso.DESK,null,true));
        }
        if(path.matches("/api/disponibilita/[^/]+/configurazione")) {
            return List.of(new Regola("PUT",Permesso.DESK,null,false));
        }
        if(path.matches("/api/disponibilita/[^/]+/orario")) {
            return List.of(new Regola("PUT",Permesso.MEDICO,"idMedico",true));
        }
        if(path.matches("/api/disponibilita/[^/]+")) {
            return List.of(new Regola("DELETE",Permesso.MEDICO,"medicoId",false));
        }
        if(path.matches("/api/prenotazioni/[^/]+/annulla-paziente")) {
            return List.of(new Regola("POST",Permesso.PAZIENTE,"idPaziente",true));
        }
        if(path.matches("/api/prenotazioni/[^/]+/(conferma|rifiuta|annulla|sostituisci-medico|pagamento-sede|verifica-mancata-erogazione)")) {
            return List.of(new Regola("POST",Permesso.DESK,"idOperatore",true));
        }
        return List.of();
    }
}
