package it.medbook.service;
import it.medbook.repository.PazienteDeskRepository;
import it.medbook.dto.*;
import it.medbook.security.*;
import java.sql.SQLException;
import java.util.*;

public class PazienteDeskService {
    private final PazienteDeskRepository repo; private final PasswordHasher hasher; private final SessionStore sessions;
    public PazienteDeskService(PazienteDeskRepository repo,PasswordHasher hasher,SessionStore sessions){this.repo=Objects.requireNonNull(repo);this.hasher=Objects.requireNonNull(hasher);this.sessions=Objects.requireNonNull(sessions);}
    public ElencoPazientiDeskResponse cerca(int actor,Map<String,String> p) throws SQLException {
        ValidazioneAccount.id(actor);
        if(!Set.of("idPaziente","nome","cognome","email","dopoId","limite").containsAll(p.keySet()))throw new IllegalArgumentException();
        int after=p.containsKey("dopoId")?integer(p.get("dopoId"),true):0,limit=p.containsKey("limite")?integer(p.get("limite"),false):20;
        if(limit>50)throw new IllegalArgumentException();
        return repo.cerca(actor,new PazienteDeskRepository.Filtri(p.containsKey("idPaziente")?integer(p.get("idPaziente"),false):null,
                prefix(p.get("nome"),100,false),prefix(p.get("cognome"),100,false),prefix(p.get("email"),255,true),after,limit));
    }
    private static int integer(String s,boolean zero){if(s==null || !s.matches(zero?"0|[1-9][0-9]{0,9}":"[1-9][0-9]{0,9}"))throw new IllegalArgumentException();return Integer.parseInt(s);}
    private static String prefix(String s,int max,boolean lower){if(s==null)return null;String value=ValidazioneAccount.testo(s,max);return lower?value.toLowerCase(Locale.ROOT):value;}
    public CollegamentoPazienteResponse collega(int actor,int target,String cf,java.time.LocalDate nascita,String telefono) throws SQLException {
        ValidazioneAccount.id(actor);ValidazioneAccount.id(target);
        var dati=ValidazioneProfiloPaziente.valida(cf,nascita,telefono,java.time.Clock.systemDefaultZone());
        repo.collega(actor,target,dati.codiceFiscale(),dati.dataNascita(),dati.telefono());
        return new CollegamentoPazienteResponse(target,true,"Profilo paziente aggiunto allo stesso account. Credenziali invariate.");
    }
    public ResetPasswordPazienteResponse reset(int actor,int target,char[] password) throws SQLException {
        ValidazioneAccount.id(actor);ValidazioneAccount.id(target);ValidazioneAccount.passwordPaziente(password);
        long generation=repo.reimpostaPassword(actor,target,hasher.hash(password));sessions.revokeStale(target,generation);
        return new ResetPasswordPazienteResponse(target,"Password reimpostata. Le sessioni precedenti sono invalidate.",true);
    }
}
