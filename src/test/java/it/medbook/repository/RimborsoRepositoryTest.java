package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RimborsoRepositoryTest {
    static class Jdbc {
        List<String> events=new ArrayList<>();boolean decision,invalidKey,zeroRows,commitFails,rollbackFails,restoreFails,updateFails,corrupt,changedSlot,changedPayment,ambiguous;
        Object def(Class<?> c){if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;return null;}
        ResultSet rows(String sql){int[] index={0};return(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->{
            String name=m.getName();if(name.equals("next"))return !sql.startsWith("SELECT id_rimborso FROM")&&index[0]++ < (ambiguous&&sql.contains("SELECT r.id_pagamento")?2:1);
            if(name.equals("getLong"))return invalidKey?0L:42L;
            if(name.equals("getInt")){String key=a[0].toString();if(sql.contains("SELECT r.id_pagamento"))return key.equals("1")?8:key.equals("2")?3:9;if(key.equals("id_paziente"))return 7;if(changedSlot&&key.equals("id_disponibilita"))return 10;if(sql.contains("SELECT id_pagamento,stato"))return 8;if(key.equals("id_pagamento"))return changedPayment?10:8;return 9;}
            if(name.equals("getString")){if(sql.contains("ruolo_gestionale"))return "stato_accesso".equals(a[0])?"ATTIVO":"OPERATORE_DESK";if(sql.contains("FROM rimborso WHERE"))return "RICHIESTO";if(sql.contains("FROM pagamento"))return a[0].toString().equals("2")?"PAGATO":"ONLINE";return "NON_EROGATA";}
            if(name.equals("getBigDecimal"))return new BigDecimal("80.00");
            if(name.equals("getObject"))return corrupt&&sql.contains("SELECT r.id_pagamento")?null:sql.contains("SELECT r.id_pagamento")?9:null;
            return def(m.getReturnType());});}
        Connection connection(){return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            String name=m.getName();switch(name){
                case "getAutoCommit":return true;case "getTransactionIsolation":return Connection.TRANSACTION_REPEATABLE_READ;
                case "setAutoCommit":events.add("auto "+a[0]);return null;
                case "setTransactionIsolation":events.add("isolation "+a[0]);if((int)a[0]==4&&restoreFails)throw new SQLException("restore");return null;
                case "commit":events.add("commit");if(commitFails)throw new SQLException("commit");return null;
                case "rollback":events.add("rollback");if(rollbackFails)throw new SQLException("rollback");return null;
                case "close":events.add("close");return null;
                case "prepareStatement":String sql=(String)a[0];return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(q,n,b)->{
                    if(n.getName().equals("executeQuery")){events.add(sql);return rows(sql);}
                    if(n.getName().equals("executeUpdate")){events.add(sql);if(updateFails)throw new SQLException("write");return zeroRows?0:1;}
                    if(n.getName().equals("getGeneratedKeys"))return rows("keys");return def(n.getReturnType());});
                default:return def(m.getReturnType());}
        });}
        RimborsoRepository repo(){return new RimborsoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){events.add("connection");return connection();}},Clock.fixed(Instant.parse("2030-01-01T08:00:00.987Z"),ZoneOffset.UTC));}
        void run()throws SQLException{if(decision)repo().decidiAtomically(42,7,true);else repo().richiediAtomically(3,7,"Test");}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void orderedLocksAndRestoration(boolean decision)throws Exception{var j=new Jdbc();j.decision=decision;j.run();var sql=j.events.stream().filter(e->e.contains("SELECT")||e.contains("INSERT")||e.contains("UPDATE rimborso")).toList();assertTrue(sql.getFirst().contains("FOR SHARE"));assertTrue(sql.get(2).contains("disponibilita")&&sql.get(2).contains("FOR UPDATE"));assertTrue(sql.get(3).contains("prenotazione")&&sql.get(3).contains("FOR UPDATE"));assertTrue(sql.get(4).contains("ORDER BY id_pagamento FOR UPDATE"));assertTrue(sql.get(5).contains("rimborso")&&sql.get(5).contains("FOR UPDATE"));assertEquals(1,Collections.frequency(j.events,"connection"));assertTrue(j.events.indexOf("isolation 2")<j.events.indexOf("auto false"));assertTrue(j.events.indexOf("commit")<j.events.indexOf("isolation 4"));assertTrue(j.events.indexOf("isolation 4")<j.events.indexOf("auto true"));assertEquals("close",j.events.getLast());}
    @ParameterizedTest @ValueSource(booleans={false,true})void structurallyBrokenDiscoveryCannotBeDecided(boolean approve){var j=new Jdbc();j.corrupt=true;assertThrows(ConflittoPrenotazioneException.class,()->j.repo().decidiAtomically(42,7,approve));assertTrue(j.events.contains("rollback"));assertFalse(j.events.stream().anyMatch(x->x.contains("UPDATE rimborso")));}
    @ParameterizedTest @ValueSource(strings={"slot","payment","ambiguous"})void associationsAreRecheckedForBothDecisions(String kind){for(boolean approve:new boolean[]{true,false}){var j=new Jdbc();j.changedSlot=kind.equals("slot");j.changedPayment=kind.equals("payment");j.ambiguous=kind.equals("ambiguous");assertThrows(ConflittoPrenotazioneException.class,()->j.repo().decidiAtomically(42,7,approve));assertTrue(j.events.contains("rollback"));assertFalse(j.events.stream().anyMatch(x->x.contains("UPDATE rimborso")));}}
    @Test void missingGeneratedKeyRollsBack(){var j=new Jdbc();j.invalidKey=true;assertThrows(SQLException.class,j::run);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
    @ParameterizedTest @ValueSource(booleans={false,true})void zeroRowsRollsBack(boolean decision){var j=new Jdbc();j.decision=decision;j.zeroRows=true;if(decision)assertThrows(ConflittoPrenotazioneException.class,j::run);else assertThrows(SQLException.class,j::run);assertTrue(j.events.contains("rollback"));}
    @Test void commitFailureRollsBack(){var j=new Jdbc();j.commitFails=true;assertEquals("commit",assertThrows(SQLException.class,j::run).getMessage());assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("auto true"));}
    @Test void rollbackFailureSuppressedAndNoAutoCommit(){var j=new Jdbc();j.updateFails=true;j.rollbackFails=true;var e=assertThrows(SQLException.class,j::run);assertEquals("write",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("auto true"));assertFalse(j.events.contains("isolation 4"));assertEquals("close",j.events.getLast());}
    @Test void restoreAfterRollbackSuppressed(){var j=new Jdbc();j.updateFails=true;j.restoreFails=true;var e=assertThrows(SQLException.class,j::run);assertEquals("write",e.getMessage());assertEquals("restore",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("auto true"));assertEquals("close",j.events.getLast());}
    @Test void restoreAfterCommitNotRolledBack(){var j=new Jdbc();j.restoreFails=true;assertEquals("restore",assertThrows(SQLException.class,j::run).getMessage());assertTrue(j.events.contains("commit"));assertFalse(j.events.contains("rollback"));assertFalse(j.events.contains("auto true"));assertEquals("close",j.events.getLast());}
}
