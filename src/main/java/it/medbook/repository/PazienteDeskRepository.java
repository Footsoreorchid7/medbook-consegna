package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.*;
import it.medbook.exception.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Letture minime e scritture sotto il mutex account condiviso UC-23/26. */
public class PazienteDeskRepository {
    private final ConnectionFactory factory;
    private final Clock clock;
    public PazienteDeskRepository(ConnectionFactory factory) { this(factory, Clock.systemDefaultZone()); }
    public PazienteDeskRepository(ConnectionFactory factory, Clock clock) { this.factory=Objects.requireNonNull(factory);this.clock=Objects.requireNonNull(clock); }
    public record Filtri(Integer id, String nome, String cognome, String email, int dopoId, int limite) { }
    private static void desk(Connection c,int actor) throws SQLException {
        try(var s=c.prepareStatement("SELECT ruolo_gestionale,stato_accesso FROM utente WHERE id_utente=?")) {
            s.setInt(1,actor);try(var r=s.executeQuery()) {
                if(!r.next() || !"OPERATORE_DESK".equals(r.getString(1)) || !"ATTIVO".equals(r.getString(2)))
                    throw new OperazioneNonAutorizzataException("Operazione non autorizzata.");
            }
        }
    }
    public ElencoPazientiDeskResponse cerca(int actor, Filtri f) throws SQLException {
        StringBuilder sql=new StringBuilder("SELECT u.id_utente,u.nome,u.cognome,u.email,u.stato_accesso,u.ruolo_gestionale, EXISTS(SELECT 1 FROM medico m WHERE m.id_utente=u.id_utente) AS medico FROM utente u JOIN paziente p ON p.id_utente=u.id_utente WHERE u.id_utente>?");
        List<Object> args=new ArrayList<>();args.add(f.dopoId());
        if(f.id()!=null){sql.append(" AND u.id_utente=?");args.add(f.id());}
        for(var field:List.of("nome","cognome","email")) {
            String value=switch(field){case "nome"->f.nome();case "cognome"->f.cognome();default->f.email();};
            if(value!=null){sql.append(" AND u.").append(field).append(" LIKE CONCAT(?, '%') ESCAPE '!'");args.add(UtenteRepository.likeLetterale(value));}
        }
        sql.append(" ORDER BY u.id_utente ASC LIMIT ?");args.add(f.limite()+1);
        try(var c=factory.getConnection()) {
            desk(c,actor);List<PazienteDeskResponse> rows=new ArrayList<>();boolean more=false;
            try(var s=c.prepareStatement(sql.toString())) {
                for(int i=0;i<args.size();i++)s.setObject(i+1,args.get(i));
                try(var r=s.executeQuery()){while(r.next()) {
                    if(rows.size()==f.limite()){more=true;break;}
                    String state=r.getString("stato_accesso");
                    if(!Set.of("ATTIVO","DISABILITATO").contains(state))throw new SQLException("Stato account non valido.");
                    rows.add(new PazienteDeskResponse(r.getInt("id_utente"),r.getString("nome"),r.getString("cognome"),r.getString("email"),r.getBoolean("medico"),r.getString("ruolo_gestionale")!=null,state));
                }}
            }
            desk(c,actor);
            return new ElencoPazientiDeskResponse(List.copyOf(rows),more?rows.getLast().idPaziente():null);
        }
    }
    /** L0 account -> L1 UTENTE per ID -> MEDICO. Non modifica mai la riga UTENTE. */
    public void collega(int actor,int target,String cf,java.time.LocalDate nascita,String telefono) throws SQLException {
        AccountMutex.execute(factory,c->{
            for(int id:new TreeSet<>(List.of(actor,target)))try(var q=c.prepareStatement("SELECT id_utente FROM utente WHERE id_utente=? FOR UPDATE")) {
                q.setInt(1,id);try(var r=q.executeQuery()){while(r.next()){ /* lock */ }}
            }
            desk(c,actor);
            try(var q=c.prepareStatement("SELECT id_utente FROM medico WHERE id_utente=? FOR UPDATE")) {
                q.setInt(1,target);try(var r=q.executeQuery()){if(!r.next())throw new RisorsaNonTrovataException("Medico non trovato.");}
            }
            try(var q=c.prepareStatement("SELECT id_utente FROM paziente WHERE id_utente=?")) {
                q.setInt(1,target);try(var r=q.executeQuery()){if(r.next())throw new ConflittoProfiloPazienteException("Profilo paziente già presente. Nessun dato sovrascritto.");}
            }
            try(var q=c.prepareStatement("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita,telefono) VALUES(?,?,?,?)")) {
                q.setInt(1,target);q.setString(2,cf);q.setObject(3,nascita);q.setString(4,telefono);
                try {if(q.executeUpdate()!=1)throw new SQLException("Collegamento non completato.");}
                catch(SQLException e) {
                    /* Le scritture profilo condividono il mutex account; la registrazione si affida comunque al vincolo UNIQUE. */
                    if(e.getErrorCode()==1062) {
                        try(var check=c.prepareStatement("SELECT id_utente FROM paziente WHERE codice_fiscale=?")) {
                            check.setString(1,cf);try(var r=check.executeQuery()) {
                                if(r.next())throw new ConflittoProfiloPazienteException("Codice fiscale già utilizzato. Nessun dato sovrascritto.");
                            }
                        }
                    }
                    throw e;
                }
            }
            return null;
        });
    }

    public long reimpostaPassword(int actor,int target,String hash) throws SQLException {
        return AccountMutex.execute(factory,c->{
            // L0 mutex -> L1 UTENTE crescente -> L2 profili. Anche il collegamento medico usa L0.
            for(int id:new TreeSet<>(List.of(actor,target)))try(var s=c.prepareStatement("SELECT id_utente FROM utente WHERE id_utente=? FOR UPDATE")) {
                s.setInt(1,id);try(var r=s.executeQuery()){while(r.next()){ /* acquisizione lock */ }}
            }
            desk(c,actor);
            try(var s=c.prepareStatement("SELECT u.ruolo_gestionale,u.generazione_autenticazione,EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente=u.id_utente) AS paziente,EXISTS(SELECT 1 FROM medico m WHERE m.id_utente=u.id_utente) AS medico FROM utente u WHERE u.id_utente=?")) {
                s.setInt(1,target);try(var r=s.executeQuery()) {
                    if(!r.next() || !r.getBoolean("paziente"))throw new RisorsaNonTrovataException("Paziente non trovato.");
                    if(r.getBoolean("medico") || r.getString("ruolo_gestionale")!=null)throw new OperazioneNonAutorizzataException("Account con altri profili: reset desk non consentito.");
                    var generation=UtenteRepository.generazione(r);
                    if(generation.isEmpty())throw new SQLException("Generazione non valida.");
                    long old=generation.getAsLong();if(old==Long.MAX_VALUE)throw new GenerazioneEsauritaException();
                    try(var update=c.prepareStatement("UPDATE utente SET password_hash=?,generazione_autenticazione=?,"+UltimaModifica.SET+" WHERE id_utente=? AND generazione_autenticazione=?")) {
                        update.setString(1,hash);update.setLong(2,old+1);UltimaModifica.imposta(update,3,actor,UltimaModifica.adesso(clock));update.setInt(5,target);update.setLong(6,old);
                        if(update.executeUpdate()!=1)throw new SQLException("Reset non completato.");
                    }
                    return old+1;
                }
            }
        });
    }
}
