package it.medbook.repository;

import it.medbook.exception.*;
import it.medbook.model.StatoPrenotazione;
import it.medbook.dto.MedicoDeskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoAbilitazioniIntegrationTest {
    static class F extends PrenotazioneIndisponibilitaIntegrationTest.Fixture {
        final List<Integer> extra=new ArrayList<>(); final int admin; final MedicoRepository medici;
        F()throws Exception{admin=user();execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",admin);medici=new MedicoRepository(factory);}
        int prestazione()throws SQLException{int p=insert("INSERT INTO prestazione(nome,descrizione,durata,prezzo) VALUES(?,'descrizione',30,75)","F7-"+UUID.randomUUID());extra.add(p);return p;}
        int count(int m,int p)throws SQLException{
            try(var c=factory.getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?")){
                s.setInt(1,m);s.setInt(2,p);try(var r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}
            }
        }
        @Override public void close()throws SQLException{super.close();for(int p:extra)execute("DELETE FROM prestazione WHERE id_prestazione=?",p);}
    }
    @Test void addDuplicateRemoveAbsentAndInactiveDoctor()throws Exception{
        try(var f=new F()){
            int m=f.doctor(),p=f.prestazione();f.execute("UPDATE medico SET stato='INATTIVO' WHERE id_utente=?",m);
            var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m);
            f.medici.assegnaPrestazione(f.admin,m,p);assertEquals(1,f.count(m,p));
            assertThrows(ConflittoMedicoException.class,()->f.medici.assegnaPrestazione(f.admin,m,p));
            f.medici.rimuoviPrestazione(f.admin,m,p);assertEquals(0,f.count(m,p));
            assertThrows(ConflittoMedicoException.class,()->f.medici.rimuoviPrestazione(f.admin,m,p));
            assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",m));
        }
    }
    @Test void historicalAndPartialAvailabilityBlockButUnconfiguredSlotsDoNot()throws Exception{
        for(boolean partial:new boolean[]{false,true})try(var f=new F()){
            int slot=MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,MedicoInattivoFlussiIntegrationTest.NOW.minusDays(10),true);
            if(partial)f.execute("UPDATE disponibilita SET id_stanza=NULL WHERE id_disponibilita=?",slot);
            var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot);
            assertThrows(ConflittoMedicoException.class,()->f.medici.rimuoviPrestazione(f.admin,f.owner,f.prestazione));
            assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot));assertEquals(1,f.count(f.owner,f.prestazione));
        }
        try(var f=new F()){
            MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,MedicoInattivoFlussiIntegrationTest.futuro(),false);
            f.medici.rimuoviPrestazione(f.admin,f.owner,f.prestazione);assertEquals(0,f.count(f.owner,f.prestazione));
        }
    }
    @ParameterizedTest @EnumSource(StatoPrenotazione.class)
    void foreignAppointmentsUseExactStateAndPreserveHistory(StatoPrenotazione state)throws Exception{
        for(int days:new int[]{-3,3})try(var f=new F()){
            int b=f.booking(state,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(days));
            if(state==StatoPrenotazione.RICHIESTA)f.execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",f.effective,b);
            if(state==StatoPrenotazione.CONFERMATA)f.execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",b);
            var before=f.snapshot(b);
            if(state==StatoPrenotazione.RICHIESTA||state==StatoPrenotazione.CONFERMATA)
                assertThrows(ConflittoMedicoException.class,()->f.medici.rimuoviPrestazione(f.admin,f.effective,f.prestazione));
            else f.medici.rimuoviPrestazione(f.admin,f.effective,f.prestazione);
            assertEquals(before,f.snapshot(b));
        }
    }
    @Test void anomalousRelevantBookingsBlockAndOtherPrestazioneDoesNot()throws Exception{
        for(String value:List.of("richiesta","confermata","effettuata"))try(var f=new F()){
            int b=f.booking(StatoPrenotazione.CONFERMATA,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(2));
            f.execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",value,b);var before=f.snapshot(b);
            assertEquals("Stato prenotazione non canonico.",assertThrows(ConflittoMedicoException.class,
                    ()->f.medici.rimuoviPrestazione(f.admin,f.effective,f.prestazione)).getMessage());
            int other=f.prestazione();f.medici.assegnaPrestazione(f.admin,f.effective,other);
            f.medici.rimuoviPrestazione(f.admin,f.effective,other);assertEquals(before,f.snapshot(b));
        }
    }
    @Test void roleExistenceAndDoctorStateAreCheckedWithoutWrites()throws Exception{
        try(var f=new F()){
            int p=f.prestazione();
            assertThrows(OperazioneNonAutorizzataException.class,()->f.medici.assegnaPrestazione(f.operator,f.owner,p));
            assertThrows(OperazioneNonAutorizzataException.class,()->f.medici.rimuoviPrestazione(f.patient,f.owner,p));
            assertThrows(RisorsaNonTrovataException.class,()->f.medici.assegnaPrestazione(f.admin,f.patient,p));
            assertThrows(RisorsaNonTrovataException.class,()->f.medici.assegnaPrestazione(f.admin,f.owner,Integer.MAX_VALUE));
            assertThrows(RisorsaNonTrovataException.class,()->f.medici.rimuoviPrestazione(f.admin,f.owner,Integer.MAX_VALUE));
            f.execute("UPDATE medico SET stato='attivo' WHERE id_utente=?",f.owner);
            assertThrows(ConflittoMedicoException.class,()->f.medici.assegnaPrestazione(f.admin,f.owner,p));
            assertThrows(ConflittoMedicoException.class,()->f.medici.rimuoviPrestazione(f.admin,f.owner,f.prestazione));assertEquals(0,f.count(f.owner,p));
        }
    }
    @Test void filtersAreAndExactOrderedAndDoNotDuplicateDoctors()throws Exception{
        try(var f=new F()){
            int m=f.doctor(),p=f.prestazione();f.medici.assegnaPrestazione(f.admin,f.owner,p);f.medici.assegnaPrestazione(f.admin,m,p);
            f.execute("UPDATE medico SET stato='INATTIVO' WHERE id_utente=?",m);
            assertEquals(List.of(f.owner,m),f.medici.elencaPerAmministratore(f.admin,null,p).stream().map(x->x.idUtente()).toList());
            assertEquals(List.of(f.owner),f.medici.elencaPerAmministratore(f.admin,"ATTIVO",p).stream().map(x->x.idUtente()).toList());
            assertEquals(List.of(m),f.medici.elencaPerDesk(f.operator,"INATTIVO",p).stream().map(x->x.idUtente()).toList());
            var all=f.medici.elencaPerAmministratore(f.admin,null,null).stream().map(x->x.idUtente()).toList();assertEquals(all.stream().sorted().toList(),all);
            assertEquals(all.size(),new HashSet<>(all).size());assertTrue(f.medici.elencaPerDesk(f.operator,null,Integer.MAX_VALUE).isEmpty());
            assertEquals("INATTIVO",f.medici.dettaglioPerAmministratore(f.admin,m).stato());assertEquals(m,f.medici.dettaglioPerDesk(f.operator,m).idUtente());
            var abilities=f.medici.consultaAbilitazioni(f.operator,f.owner);var ids=abilities.stream().map(x->x.getIdPrestazione()).toList();assertEquals(List.of(f.prestazione,p),ids);
            var current=abilities.getLast();assertEquals(30,current.getDurata());assertEquals("descrizione",current.getDescrizione());assertEquals(0,current.getPrezzo().compareTo(new java.math.BigDecimal("75")));
            assertTrue(f.medici.consultaAbilitazioni(f.admin,f.effective).size()==1);int empty=f.doctor();assertTrue(f.medici.consultaAbilitazioni(f.operator,empty).isEmpty());
        }
    }
    @Test void deskProjectionHasExactlySixFieldsAndAdminKeepsRawState()throws Exception{
        try(var f=new F()){
            var dto=f.medici.dettaglioPerDesk(f.operator,f.owner);var json=new ObjectMapper().valueToTree(dto);
            Set<String> fields=new HashSet<>();json.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("idUtente","nome","cognome","specializzazione","numeroIscrizioneOrdine","stato"),fields);
            assertEquals(fields,new HashSet<>(Arrays.stream(MedicoDeskResponse.class.getRecordComponents()).map(x->x.getName()).toList()));
            for(String field:List.of("email","ruoloGestionale","paziente","password","passwordHash"))assertFalse(json.has(field));
            assertThrows(OperazioneNonAutorizzataException.class,()->f.medici.elencaPerAmministratore(f.operator,null,null));
            assertThrows(OperazioneNonAutorizzataException.class,()->f.medici.dettaglioPerAmministratore(f.operator,f.owner));
            assertThrows(OperazioneNonAutorizzataException.class,()->f.medici.consultaAbilitazioni(f.patient,f.owner));
            assertThrows(RisorsaNonTrovataException.class,()->f.medici.dettaglioPerDesk(f.operator,f.patient));
            f.execute("UPDATE medico SET stato='attivo' WHERE id_utente=?",f.owner);
            assertEquals("attivo",f.medici.dettaglioPerAmministratore(f.admin,f.owner).stato());
            assertTrue(f.medici.elencaPerAmministratore(f.admin,null,f.prestazione).stream().anyMatch(x->x.idUtente()==f.owner&&x.stato().equals("attivo")));
            assertThrows(ConflittoMedicoException.class,()->f.medici.dettaglioPerDesk(f.operator,f.owner));
            assertThrows(ConflittoMedicoException.class,()->f.medici.elencaPerDesk(f.operator,null,f.prestazione));
            assertThrows(ConflittoMedicoException.class,()->f.medici.consultaAbilitazioni(f.operator,f.owner));
            assertTrue(f.medici.elencaPerDesk(f.operator,"ATTIVO",f.prestazione).stream().noneMatch(x->x.idUtente()==f.owner));
            assertEquals(1,f.medici.consultaAbilitazioni(f.admin,f.owner).size());
        }
    }
    @Test void realRollbackRestoresBothAssociationDirectionsAndConnectionProperties()throws Exception {
        try(var f=new F()) {
            int p=f.prestazione();var error=new SQLException("synthetic failure before commit");int[] closed={0};
            var observed=new it.medbook.config.ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection()throws SQLException {
                    var c=f.factory.getConnection();boolean auto=c.getAutoCommit();int isolation=c.getTransactionIsolation();
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,m,args)->{
                        if(m.getName().equals("commit"))throw error;
                        if(m.getName().equals("close")){assertEquals(auto,c.getAutoCommit());assertEquals(isolation,c.getTransactionIsolation());closed[0]++;}
                        try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            var repo=new MedicoRepository(observed);
            assertSame(error,assertThrows(SQLException.class,()->repo.assegnaPrestazione(f.admin,f.effective,p)));assertEquals(0,f.count(f.effective,p));
            assertSame(error,assertThrows(SQLException.class,()->repo.rimuoviPrestazione(f.admin,f.effective,f.prestazione)));assertEquals(1,f.count(f.effective,f.prestazione));
            assertEquals(2,closed[0]);
            f.medici.assegnaPrestazione(f.admin,f.effective,p);f.medici.rimuoviPrestazione(f.admin,f.effective,p);
        }
    }
    @Test void databaseRestrictProtectsTheCompositeAssociationIndependently()throws Exception {
        try(var f=new F()) {
            int slot=MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,MedicoInattivoFlussiIntegrationTest.NOW.minusDays(5),true);
            var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot);
            var failure=assertThrows(SQLException.class,()->f.execute("DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?",f.owner,f.prestazione));
            assertEquals(1451,failure.getErrorCode());assertEquals("23000",failure.getSQLState());assertEquals(1,f.count(f.owner,f.prestazione));
            assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot));
        }
    }
}
