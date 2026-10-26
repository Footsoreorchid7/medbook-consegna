package it.medbook.repository;
import it.medbook.config.*;
import it.medbook.model.StatoPrenotazione;
import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static it.medbook.repository.AgendaRefertoPresenteTest.proxy;

/** Verifica query e mapping senza collegarsi a database. Il browser prova anche la SELECT reale. */
class RichiesteStatoTest {
 @ParameterizedTest @EnumSource(StatoPrenotazione.class) @NullSource
 void queryUsesBoundStateOrNoStatePredicateAndPreservesDatesAndOrder(StatoPrenotazione state) throws Exception {
  List<Object> params=new ArrayList<>(); List<String> queries=new ArrayList<>();int[] closed={0};
  Connection c=proxy(Connection.class,(m,a)->switch(m){
   case "close"->{closed[0]++;yield null;}
   case "prepareStatement"->{queries.add((String)a[0]);yield proxy(PreparedStatement.class,(s,b)->switch(s){
    case "setInt","setString","setObject"->{params.add(b[1]);yield null;}
    case "close"->{closed[0]++;yield null;}
    case "executeQuery"->{int[] row={-1};yield proxy(ResultSet.class,(r,v)->switch(r){
     case "next"->++row[0]<(state==null?2:1);
     case "close"->{closed[0]++;yield null;}
     case "getInt"->"id_prenotazione".equals(v[0])?24+row[0]:4;
     case "getBoolean"->false;
     case "getBigDecimal"->new BigDecimal("120.00");
     case "getString"->"stato".equals(v[0])?(state==null?(row[0]==0?"RIFIUTATA":"ANNULLATA"):state.name()):"Sintetico";
     case "getObject"->((String)v[0]).startsWith("data_ora")?LocalDateTime.of(2030,1,15,9,0):Integer.valueOf(1);
     default->throw new AssertionError(r);
    });}
    default->throw new AssertionError(s);
   });}
   default->throw new AssertionError(m);
  });
  var factory=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return c;}};
  var repo=new PrenotazioneRepository(factory,Clock.systemUTC());
  var day=LocalDate.of(2030,1,15);
  var result=repo.findRichieste(4,2,day,day,state);
  var expected=new ArrayList<Object>();if(state!=null)expected.add(state.name());expected.addAll(List.of(4,2,day.atStartOfDay(),day.plusDays(1).atStartOfDay()));
  assertEquals(expected,params);
  assertEquals(state!=null,queries.getFirst().contains("AND pr.stato = ?"));
  assertTrue(queries.getFirst().contains("ORDER BY d.data_ora_inizio ASC, pr.data_ora_richiesta ASC, pr.id_prenotazione ASC"));
  assertEquals(state==null?List.of(24,25):List.of(24),result.stream().map(x->x.getIdPrenotazione()).toList());
  assertEquals(state==null?StatoPrenotazione.RIFIUTATA:state,result.getFirst().getStato());
  assertEquals(3,closed[0]);
 }
}
