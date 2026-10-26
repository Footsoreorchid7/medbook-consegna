package it.medbook.repository;

import it.medbook.exception.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoAbilitazioniRepositoryTest {
    static class Jdbc extends MedicoRepositoryTest.Jdbc {
        boolean association, availability, blocking, anomalous, duplicateRead, fkAppears, closed, restored;
        int availabilityReads; SQLException rereadError;
        Jdbc(){medici.put(9,"ATTIVO");}
        @Override ResultSet query(String sql,Map<Integer,Object> args){
            if(sql.startsWith("SELECT id_prestazione FROM prestazione"))return record(sql,args,true);
            if(sql.startsWith("SELECT id_medico FROM medico_prestazione"))return record(sql,args,association);
            if(sql.startsWith("SELECT 1 FROM medico_prestazione"))return record(sql,args,duplicateRead);
            if(sql.startsWith("SELECT 1 FROM disponibilita")){availabilityReads++;return record(sql,args,availability||(fkAppears&&availabilityReads>1));}
            if(sql.startsWith("SELECT 1 FROM prenotazione"))return record(sql,args,sql.contains("NOT IN")?anomalous:blocking);
            if(sql.equals("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=?")){
                events.add(sql+" "+args.values());return utente((Integer)args.get(1));
            }
            return super.query(sql,args);
        }
        ResultSet record(String sql,Map<Integer,Object> args,boolean exists){events.add(sql+" "+args.values());return rows(exists?List.of(Map.of()):List.of());}
        @Override Connection connection(){
            Connection original=super.connection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close")){closed=true;restored=original.getAutoCommit()&&original.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ;events.add("close");}
                try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        @Override PreparedStatement statement(String sql){
            var original=super.statement(sql);var args=new TreeMap<Integer,Object>();
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                if(m.getName().startsWith("set"))args.put((Integer)a[0],a[1]);
                if(m.getName().equals("executeUpdate"))events.add("DML "+sql+" "+args.values());
                if(m.getName().equals("executeQuery")&&rereadError!=null&&sql.startsWith("SELECT 1 FROM medico_prestazione"))throw rereadError;
                try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
    }
    @Test void writeLockOrderParametersAndResourcesAreExact()throws Exception{
        var j=new Jdbc();j.repo().assegnaPrestazione(7,9,11);
        assertEquals("acquire",j.events.getFirst());
        assertEquals(List.of("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [7]",
                "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [9]","SELECT stato FROM medico WHERE id_utente = ? [9]",
                "SELECT id_medico FROM medico_prestazione WHERE id_medico=? AND id_prestazione=? [9, 11]"),j.locks());
        int doctor=j.events.indexOf(MedicoRepositoryTest.MEDICO_LOCK+" [9]");
        int prest=j.events.indexOf("SELECT id_prestazione FROM prestazione WHERE id_prestazione=? FOR SHARE [11]");
        int assoc=j.events.indexOf("SELECT id_medico FROM medico_prestazione WHERE id_medico=? AND id_prestazione=? FOR UPDATE [9, 11]");
        assertTrue(doctor<prest&&prest<assoc);
        assertTrue(j.events.contains("DML INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?) [9, 11]"));
        assertTrue(j.closed&&j.restored);assertTrue(j.events.indexOf("commit")<j.events.indexOf("release"));
        var r=new Jdbc();r.association=true;r.repo().rimuoviPrestazione(7,9,11);
        assertTrue(r.events.contains("DML DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=? [9, 11]"));
        assertTrue(r.events.stream().filter(x->x.startsWith("SELECT 1 FROM disponibilita")||x.startsWith("SELECT 1 FROM prenotazione"))
                .noneMatch(x->x.contains("FOR UPDATE")||x.contains("data_ora")||x.contains("pagamento")||x.contains("indisponibilita")));
        assertTrue(r.closed&&r.restored);
    }
    @Test void dependenciesAndRoleFailuresRollBackWithoutDeleting(){
        for(String cause:List.of("availability","blocking","anomalous")){
            var j=new Jdbc();j.association=true;j.availability=cause.equals("availability");j.blocking=cause.equals("blocking");j.anomalous=cause.equals("anomalous");
            assertThrows(ConflittoMedicoException.class,()->j.repo().rimuoviPrestazione(7,9,11));
            assertFalse(j.events.stream().anyMatch(x->x.startsWith("DML")));assertTrue(j.events.contains("rollback"));assertTrue(j.closed&&j.restored);
        }
        var j=new Jdbc();j.utenti.put(7,"OPERATORE_DESK");
        assertThrows(OperazioneNonAutorizzataException.class,()->j.repo().assegnaPrestazione(7,9,11));
        assertFalse(j.events.stream().anyMatch(x->x.startsWith("SELECT stato")));assertTrue(j.events.contains("rollback"));
    }
    @Test void sqlFaultsPreserveCauseAndIndependentCleanup(){
        var j=new Jdbc();var failure=new SQLException("synthetic failure","HY000",999);j.failUpdate.put("INSERT medico_prestazione",failure);
        assertSame(failure,assertThrows(SQLException.class,()->j.repo().assegnaPrestazione(7,9,11)));
        assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));assertTrue(j.events.contains("release"));assertTrue(j.closed&&j.restored);
    }
    @Test void duplicateAndRestrictErrorsRequireCurrentEvidenceIncludingChainedErrors(){
        var outer=new SQLException("outer","HY000",0);outer.setNextException(new SQLException("localized duplicate","23000",1062));
        var j=new Jdbc();j.duplicateRead=true;j.failUpdate.put("INSERT medico_prestazione",outer);
        assertSame(outer,assertThrows(ConflittoMedicoException.class,()->j.repo().assegnaPrestazione(7,9,11)).getCause());
        var unconfirmed=new Jdbc();unconfirmed.failUpdate.put("INSERT medico_prestazione",outer);
        assertSame(outer,assertThrows(SQLException.class,()->unconfirmed.repo().assegnaPrestazione(7,9,11)));
        var fk=new Jdbc();fk.association=true;fk.fkAppears=true;var restrict=new SQLException("localized FK","23000",1451);fk.failUpdate.put("DELETE medico_prestazione",restrict);
        assertSame(restrict,assertThrows(ConflittoMedicoException.class,()->fk.repo().rimuoviPrestazione(7,9,11)).getCause());
        var secondary=new Jdbc();secondary.failUpdate.put("INSERT medico_prestazione",outer);secondary.rereadError=new SQLException("reread failed");
        assertSame(outer,assertThrows(SQLException.class,()->secondary.repo().assegnaPrestazione(7,9,11)));assertTrue(Arrays.asList(outer.getSuppressed()).contains(secondary.rereadError));
    }
    @Test void readsVerifyRolesWithoutMutexAndDeskSelectNeverLoadsPrivateFields()throws Exception{
        var j=new Jdbc();j.utenti.put(7,"OPERATORE_DESK");var result=j.repo().elencaPerDesk(7,"ATTIVO",11);assertEquals(1,result.size());
        String sql=j.events.stream().filter(x->x.startsWith("SELECT u.id_utente")).findFirst().orElseThrow();
        assertTrue(sql.contains("EXISTS("));assertTrue(sql.contains("ORDER BY m.id_utente ASC"));assertTrue(sql.endsWith("[ATTIVO, 11]"));
        for(String field:List.of("email","ruolo_gestionale","paziente","password"))assertFalse(sql.contains(field));
        assertFalse(j.events.contains("acquire"));assertTrue(j.closed);assertFalse(j.events.stream().anyMatch(x->x.contains("FOR UPDATE")||x.contains("FOR SHARE")));
        assertThrows(OperazioneNonAutorizzataException.class,()->j.repo().elencaPerAmministratore(7,null,null));
        j.utenti.put(7,"AMMINISTRATORE");assertEquals(1,j.repo().elencaPerAmministratore(7,null,null).size());
        j.utenti.put(7,null);assertThrows(OperazioneNonAutorizzataException.class,()->j.repo().consultaAbilitazioni(7,9));
    }
}
