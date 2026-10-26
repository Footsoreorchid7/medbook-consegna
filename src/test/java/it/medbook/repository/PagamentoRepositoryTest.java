package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.model.ModalitaPagamento;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PagamentoRepositoryTest {
    static class Jdbc {
        final List<String> events=new ArrayList<>();
        boolean commitFails,rollbackFails,restoreFails,isolationRestoreFails,invalidKey;
        Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
        ResultSet rows(String sql){int[] index={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            String name=m.getName();if(name.equals("close")){events.add("close rows");return null;}
            if(name.equals("next"))return !sql.contains("FROM pagamento") && index[0]++==0;
            if(name.equals("getLong"))return invalidKey?0L:42L;
            if(name.equals("getInt"))return 9;
            if(name.equals("getBoolean"))return false;
            if(name.equals("getBigDecimal"))return new BigDecimal("80.00");
            if(name.equals("getString"))return sql.contains("ruolo_gestionale")?("stato_accesso".equals(a[0])?"ATTIVO":"OPERATORE_DESK"):"CONFERMATA";
            if(name.equals("getObject"))return 7;
            return defaultValue(m.getReturnType());
        });}
        PreparedStatement statement(String sql){return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().equals("executeQuery")){events.add(sql);return rows(sql);}
            if(m.getName().equals("executeUpdate")){events.add(sql);return 1;}
            if(m.getName().equals("getGeneratedKeys"))return rows("keys");
            if(m.getName().equals("close")){events.add("close statement");return null;}
            return defaultValue(m.getReturnType());
        });}
        Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            switch(m.getName()){
                case "getAutoCommit":return true;
                case "getTransactionIsolation":return Connection.TRANSACTION_REPEATABLE_READ;
                case "setTransactionIsolation":events.add("isolation "+a[0]);if((int)a[0]==Connection.TRANSACTION_REPEATABLE_READ&&isolationRestoreFails)throw new SQLException("isolation restore");return null;
                case "setAutoCommit":events.add("autocommit "+a[0]);if(Boolean.TRUE.equals(a[0])&&restoreFails)throw new SQLException("restore");return null;
                case "prepareStatement":return statement((String)a[0]);
                case "commit":events.add("commit");if(commitFails)throw new SQLException("commit");return null;
                case "rollback":events.add("rollback");if(rollbackFails)throw new SQLException("rollback");return null;
                case "close":events.add("close connection");return null;
                default:return defaultValue(m.getReturnType());
            }
        });}
        PagamentoRepository repo(){return new PagamentoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection(){events.add("connection");return connection();}
        },Clock.fixed(Instant.parse("2030-01-01T08:00:00Z"),ZoneOffset.UTC));}
        void pay()throws SQLException{repo().registraInSedeAtomically(3,7,ModalitaPagamento.DESK_POS,new BigDecimal("80"));}
    }
    @Test void oneConnectionLockOrderAndResources()throws Exception{var j=new Jdbc();j.pay();assertEquals(1,Collections.frequency(j.events,"connection"));List<String> sql=j.events.stream().filter(e->e.contains("SELECT")||e.contains("INSERT")).toList();assertEquals(6,sql.size());assertTrue(sql.get(0).contains("FOR SHARE"));assertTrue(sql.get(2).contains("disponibilita")&&sql.get(2).contains("FOR UPDATE"));assertTrue(sql.get(3).contains("prenotazione")&&sql.get(3).contains("FOR UPDATE"));assertTrue(sql.get(4).contains("ORDER BY id_pagamento FOR UPDATE"));assertEquals(6,Collections.frequency(j.events,"close statement"));assertEquals(6,Collections.frequency(j.events,"close rows"));assertEquals("close connection",j.events.getLast());assertFalse(j.events.contains("rollback"));assertEquals(List.of("isolation 2","isolation 4"),j.events.stream().filter(e->e.startsWith("isolation ")).toList());}
    @Test void failedRollbackNeverEnablesAutocommit(){var j=new Jdbc();j.commitFails=true;j.rollbackFails=true;j.isolationRestoreFails=true;var e=assertThrows(SQLException.class,j::pay);assertEquals("commit",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("isolation 4"));assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());}
    @Test void restorationFailureIsSuppressedOnOriginalError(){var j=new Jdbc();j.commitFails=true;j.restoreFails=true;var e=assertThrows(SQLException.class,j::pay);assertEquals("commit",e.getMessage());assertEquals("restore",e.getSuppressed()[0].getMessage());assertEquals("close connection",j.events.getLast());}
    @Test void restorationFailureAfterCommitIsReportedWithoutRetry(){var j=new Jdbc();j.restoreFails=true;assertEquals("restore",assertThrows(SQLException.class,j::pay).getMessage());assertEquals(1,Collections.frequency(j.events,"commit"));assertFalse(j.events.contains("rollback"));assertEquals("close connection",j.events.getLast());}
    @Test void missingGeneratedKeyRollsBack(){var j=new Jdbc();j.invalidKey=true;assertThrows(SQLException.class,j::pay);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
    @Test void isolationRestorationFailureAfterCommitClosesWithoutRetry() {
        var j = new Jdbc(); j.isolationRestoreFails = true;
        var error = assertThrows(SQLException.class, j::pay);
        assertEquals("isolation restore", error.getMessage());
        assertEquals(0, error.getSuppressed().length);
        assertEquals(1, Collections.frequency(j.events, "commit"));
        assertFalse(j.events.contains("rollback"));
        assertFalse(j.events.contains("autocommit true"));
        assertEquals("close connection", j.events.getLast());
        assertTrue(j.events.indexOf("commit") < j.events.indexOf("isolation 4"));
    }

    @Test void isolationRestorationFailureAfterRollbackIsSuppressed() {
        var j = new Jdbc(); j.commitFails = true; j.isolationRestoreFails = true;
        var error = assertThrows(SQLException.class, j::pay);
        assertEquals("commit", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("isolation restore", error.getSuppressed()[0].getMessage());
        assertEquals(1, Collections.frequency(j.events, "rollback"));
        assertFalse(j.events.contains("autocommit true"));
        assertEquals("close connection", j.events.getLast());
        assertTrue(j.events.indexOf("rollback") < j.events.indexOf("isolation 4"));
    }

}
