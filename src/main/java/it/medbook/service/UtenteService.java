package it.medbook.service;

import it.medbook.dto.*;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import java.sql.SQLException;
import java.util.*;

public class UtenteService {
    private final UtenteRepository repository;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    public UtenteService(UtenteRepository repository,PasswordHasher hasher) { this(repository,hasher,null); }
    /* Lo store libera sessioni superate solo dopo commit confermato dello stato di accesso. */
    public UtenteService(UtenteRepository repository,PasswordHasher hasher,SessionStore sessions) {
        this.repository=Objects.requireNonNull(repository);this.hasher=Objects.requireNonNull(hasher);this.sessions=sessions;
    }
    public List<AccountInternoResponse> getGestionali()throws SQLException{return repository.findGestionali();}
    /* Ricerca amministrativa: parametri validati prima di accedere al database. */
    public RicercaUtentiResponse cerca(Integer attore,Map<String,String> parametri)throws SQLException {
        ValidazioneAccount.id(attore);
        return repository.cerca(attore,ValidazioneRicercaUtenti.filtri(parametri));
    }
    /*
     * Disabilita o riabilita account; dopo commit confermato e fuori transazione libera solo
     * sessioni più vecchie. La correttezza dipende dalla riverifica database, non dalla pulizia;
     * una chiamata tardiva non rimuove generazioni uguali o successive.
     */
    public UtenteAmministrativoResponse cambiaStato(Integer attore,Integer destinatario,String statoAccesso)throws SQLException {
        ValidazioneAccount.id(attore);ValidazioneAccount.id(destinatario);
        if(!"ATTIVO".equals(statoAccesso) && !"DISABILITATO".equals(statoAccesso))throw new IllegalArgumentException();
        var esito=repository.cambiaStatoAccesso(attore,destinatario,statoAccesso);
        if(esito.nuovaGenerazione()!=null && sessions!=null)sessions.revokeStale(destinatario,esito.nuovaGenerazione());
        return esito.utente();
    }
    /* Regole account in ValidazioneAccount, condivise coi medici; ordine dei controlli invariato. */
    public AccountInternoResponse crea(Integer id,AccountInternoRequest request)throws SQLException {
        ValidazioneAccount.id(id);if(request==null || request.ruoloGestionale()==null)throw new IllegalArgumentException();
        String nome=ValidazioneAccount.testo(request.nome(),100),cognome=ValidazioneAccount.testo(request.cognome(),100),email=ValidazioneAccount.email(request.email());
        String password=ValidazioneAccount.password(request.password());
        String hash=ValidazioneAccount.hash(hasher,password);
        return repository.createInterno(id,nome,cognome,email,hash,request.ruoloGestionale());
    }
    public AccountInternoResponse aggiornaRuolo(Integer id,Integer target,RuoloUtenteRequest request)throws SQLException {
        ValidazioneAccount.id(id);ValidazioneAccount.id(target);if(request==null || request.ruoloGestionale()==null)throw new IllegalArgumentException();
        return repository.updateRuolo(id,target,request.ruoloGestionale());
    }
}
