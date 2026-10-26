package it.medbook.service;

import it.medbook.config.*;
import it.medbook.repository.*;
import it.medbook.dto.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RimborsoServiceTest {
    static class Fake extends RimborsoRepository {
        int calls;Integer booking,actor;String motive;boolean approve;Throwable error;
        Fake(){super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()));}
        void call()throws SQLException{calls++;if(error instanceof SQLException e)throw e;if(error instanceof RuntimeException e)throw e;}
        @Override public RimborsoResponse richiediAtomically(Integer b,Integer a,String m)throws SQLException{call();booking=b;actor=a;motive=m;return new RimborsoResponse();}
        @Override public DecisioneRimborsoResponse decidiAtomically(Integer b,Integer a,boolean approve)throws SQLException{call();booking=b;actor=a;this.approve=approve;return new DecisioneRimborsoResponse();}
        @Override public List<RimborsoResponse> findByPaziente(Integer a)throws SQLException{call();actor=a;return List.of(new RimborsoResponse());}
        @Override public List<RichiestaRimborsoDeskResponse> findRichieste()throws SQLException{call();return List.of(new RichiestaRimborsoDeskResponse());}
    }
    Fake fake=new Fake();RimborsoService service=new RimborsoService(fake);
    @Test void singleDelegationAndOriginalMotive()throws Exception{String m="  Visita 😀\r\n\t non erogata  ";service.richiedi(3,7,m);assertEquals(1,fake.calls);assertEquals(3,fake.booking);assertEquals(7,fake.actor);assertEquals(m,fake.motive);}
    @Test void listsDelegate()throws Exception{assertEquals(1,service.getRimborsiPaziente(7).size());assertEquals(7,fake.actor);assertEquals(1,service.getRichieste().size());assertEquals(2,fake.calls);}
    @ParameterizedTest @ValueSource(booleans={false,true})void decisionsDelegate(boolean approve)throws Exception{if(approve)service.approva(3,7);else service.respingi(3,7);assertEquals(1,fake.calls);assertEquals(3,fake.booking);assertEquals(7,fake.actor);assertEquals(approve,fake.approve);}
    @ParameterizedTest @NullSource @ValueSource(ints={0,-1})void invalidIds(Integer id){assertThrows(IllegalArgumentException.class,()->service.richiedi(id,7,"x"));assertThrows(IllegalArgumentException.class,()->service.richiedi(3,id,"x"));assertThrows(IllegalArgumentException.class,()->service.approva(id,7));assertThrows(IllegalArgumentException.class,()->service.respingi(3,id));assertThrows(IllegalArgumentException.class,()->service.getRimborsiPaziente(id));assertEquals(0,fake.calls);}
    @ParameterizedTest @NullSource @ValueSource(strings={""," ","\n\t","\u0000","text\u001b","text\u007f","text\u0085","\uD800"})void invalidMotives(String m){assertThrows(IllegalArgumentException.class,()->service.richiedi(3,7,m));assertEquals(0,fake.calls);}
    @Test void unicodeCountsCharactersNotUtf16Units()throws Exception{service.richiedi(3,7,"😀".repeat(500));assertEquals(1,fake.calls);assertThrows(IllegalArgumentException.class,()->service.richiedi(3,7,"😀".repeat(501)));assertEquals(1,fake.calls);}
    @Test void errorsPropagatedUnchanged(){for(Throwable e:List.of(new SQLException("db"),new ConflittoPrenotazioneException("conflict"),new RisorsaNonTrovataException("missing"),new OperazioneNonAutorizzataException("role"))){fake.error=e;assertSame(e,assertThrows(Exception.class,()->service.richiedi(3,7,"x")));assertSame(e,assertThrows(Exception.class,()->service.approva(3,7)));assertSame(e,assertThrows(Exception.class,()->service.respingi(3,7)));}}
}
