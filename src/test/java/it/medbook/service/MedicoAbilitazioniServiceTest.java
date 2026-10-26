package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.repository.MedicoRepository;
import it.medbook.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MedicoAbilitazioniServiceTest {
    static class Repo extends MedicoRepository {
        int calls; String operation; List<Object> args; SQLException error;
        final MedicoResponse admin=new MedicoResponse(9,"N","C","mail","S","O","ATTIVO",false,null);
        final MedicoDeskResponse desk=new MedicoDeskResponse(9,"N","C","S","O","ATTIVO");
        final List<MedicoResponse> admins=List.of(admin); final List<MedicoDeskResponse> desks=List.of(desk);
        final List<PrestazioneResponse> prestazioni=List.of(new PrestazioneResponse());
        Repo(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public java.sql.Connection getConnection(){throw new AssertionError("unexpected JDBC");}});}
        void call(String name,Object...values)throws SQLException{calls++;operation=name;args=Arrays.asList(values);if(error!=null)throw error;}
        @Override public void assegnaPrestazione(int a,int m,int p)throws SQLException{call("add",a,m,p);}
        @Override public void rimuoviPrestazione(int a,int m,int p)throws SQLException{call("remove",a,m,p);}
        @Override public List<MedicoResponse> elencaPerAmministratore(int a,String s,Integer p)throws SQLException{call("adminList",a,s,p);return admins;}
        @Override public List<MedicoDeskResponse> elencaPerDesk(int a,String s,Integer p)throws SQLException{call("deskList",a,s,p);return desks;}
        @Override public MedicoResponse dettaglioPerAmministratore(int a,int m)throws SQLException{call("adminDetail",a,m);return admin;}
        @Override public MedicoDeskResponse dettaglioPerDesk(int a,int m)throws SQLException{call("deskDetail",a,m);return desk;}
        @Override public List<PrestazioneResponse> consultaAbilitazioni(int a,int m)throws SQLException{call("abilities",a,m);return prestazioni;}
    }
    @Test void eachOperationDelegatesExactlyOnceAndPreservesResult()throws Exception{
        var r=new Repo();var s=new MedicoService(r,new PasswordHasher());
        s.assegnaPrestazione(7,9,11);assertEquals("add",r.operation);assertEquals(List.of(7,9,11),r.args);assertEquals(1,r.calls);
        s.rimuoviPrestazione(7,9,11);assertEquals("remove",r.operation);assertEquals(2,r.calls);
        assertSame(r.admins,s.elencaPerAmministratore(7,"INATTIVO",11));assertEquals(Arrays.asList(7,"INATTIVO",11),r.args);
        assertSame(r.desks,s.elencaPerDesk(7,null,null));assertEquals(Arrays.asList(7,null,null),r.args);
        assertSame(r.admin,s.dettaglioPerAmministratore(7,9));assertSame(r.desk,s.dettaglioPerDesk(7,9));
        assertSame(r.prestazioni,s.consultaAbilitazioni(7,9));assertEquals(7,r.calls);
    }
    @Test void invalidIdsAndExactFiltersNeverReachRepository(){
        var r=new Repo();var s=new MedicoService(r,new PasswordHasher());
        for(Integer bad:Arrays.asList(null,0,-1)){
            assertThrows(IllegalArgumentException.class,()->s.assegnaPrestazione(bad,9,11));
            assertThrows(IllegalArgumentException.class,()->s.assegnaPrestazione(7,bad,11));
            assertThrows(IllegalArgumentException.class,()->s.assegnaPrestazione(7,9,bad));
            assertThrows(IllegalArgumentException.class,()->s.rimuoviPrestazione(7,bad,11));
            assertThrows(IllegalArgumentException.class,()->s.rimuoviPrestazione(bad,9,11));
            assertThrows(IllegalArgumentException.class,()->s.rimuoviPrestazione(7,9,bad));
            assertThrows(IllegalArgumentException.class,()->s.dettaglioPerAmministratore(7,bad));
            assertThrows(IllegalArgumentException.class,()->s.dettaglioPerDesk(bad,9));
            assertThrows(IllegalArgumentException.class,()->s.consultaAbilitazioni(7,bad));
            assertThrows(IllegalArgumentException.class,()->s.elencaPerAmministratore(bad,null,null));
            assertThrows(IllegalArgumentException.class,()->s.elencaPerDesk(bad,null,null));
        }
        for(String bad:List.of("","attivo","ATTIVO "," INATTIVO","UNKNOWN")){
            assertThrows(IllegalArgumentException.class,()->s.elencaPerAmministratore(7,bad,null));
            assertThrows(IllegalArgumentException.class,()->s.elencaPerDesk(7,bad,null));
        }
        for(int bad:new int[]{0,-1}){
            assertThrows(IllegalArgumentException.class,()->s.elencaPerAmministratore(7,null,bad));
            assertThrows(IllegalArgumentException.class,()->s.elencaPerDesk(7,null,bad));
        }
        assertEquals(0,r.calls);
    }
    @Test void sqlErrorsAreNotWrappedOrRetried(){
        var r=new Repo();r.error=new SQLException("synthetic");var s=new MedicoService(r,new PasswordHasher());
        assertSame(r.error,assertThrows(SQLException.class,()->s.rimuoviPrestazione(7,9,11)));assertEquals(1,r.calls);
        assertSame(r.error,assertThrows(SQLException.class,()->s.elencaPerDesk(7,null,null)));assertEquals(2,r.calls);
    }
}
