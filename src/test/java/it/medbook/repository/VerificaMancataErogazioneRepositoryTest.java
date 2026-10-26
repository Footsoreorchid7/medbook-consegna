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

class VerificaMancataErogazioneRepositoryTest {
    static class Jdbc {
        final List<String> events=new ArrayList<>();
        boolean commitFails,rollbackFails,restoreFails,isolationRestoreFails,invalidKey,updateFails,zeroRows;
        Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
        ResultSet rows(String sql){int[] index={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            String name=m.getName();if(name.equals("close")){events.add("close rows");return null;}
            if(name.equals("next"))return !sql.contains("FROM pagamento") && index[0]++==0;
            if(name.equals("getLong"))return invalidKey?0L:42L;
            if(name.equals("getInt"))return 9;
            if(name.equals("getBoolean"))return false;
            if(name.equals("getBigDecimal"))return new BigDecimal("80.00");
            if(name.equals("getString"))return sql.contains("ruolo_gestionale")?("stato_accesso".equals(a[0])?"ATTIVO":"OPERATORE_DESK"):"CONFERMATA";
            if(name.equals("getObject"))return "data_ora_fine".equals(a[0])?LocalDateTime.of(2030,1,1,8,0):7;
            return defaultValue(m.getReturnType());
        });}
        PreparedStatement statement(String sql){return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().equals("executeQuery")){events.add(sql);return rows(sql);}
            if(m.getName().equals("executeUpdate")){events.add(sql);if(updateFails)throw new SQLException("update");return zeroRows?0:1;}
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
        PrenotazioneRepository repo(){return new PrenotazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection(){events.add("connection");return connection();}
        },Clock.fixed(Instant.parse("2030-01-01T08:00:00Z"),ZoneOffset.UTC));}
        void pay()throws SQLException{repo().verificaMancataErogazioneAtomically(3,7,it.medbook.model.StatoPrenotazione.CONFERMATA,it.medbook.model.StatoPrenotazione.NON_EROGATA);}
    }
    @Test void oneConnectionOrderedLocksAndAtomicUpdate() throws Exception {
        var j=new Jdbc(); j.pay();
        var sql=j.events.stream().filter(e->e.contains("SELECT")||e.contains("UPDATE prenotazione")).toList();
        assertEquals(6,sql.size());assertTrue(sql.get(0).contains("FOR SHARE"));
        assertTrue(sql.get(2).contains("disponibilita")&&sql.get(2).contains("FOR UPDATE"));
        assertTrue(sql.get(3).contains("prenotazione")&&sql.get(3).contains("FOR UPDATE"));
        assertTrue(sql.get(4).contains("ORDER BY id_pagamento FOR UPDATE"));
        assertTrue(sql.get(5).contains("indisponibilita_segnalata=FALSE"));
        assertTrue(sql.get(5).contains("gestita_da=?")&&sql.get(5).contains("data_ora_gestione=?"));
        assertTrue(sql.get(5).contains("WHERE id_prenotazione=? AND stato=?"));
        assertEquals(1,Collections.frequency(j.events,"connection"));assertEquals(6,Collections.frequency(j.events,"close statement"));assertEquals(5,Collections.frequency(j.events,"close rows"));
        assertEquals(List.of("isolation 2","isolation 4"),j.events.stream().filter(e->e.startsWith("isolation ")).toList());
        assertTrue(j.events.indexOf("isolation 2")<j.events.indexOf("autocommit false"));
        assertTrue(j.events.indexOf("commit")<j.events.indexOf("isolation 4"));
        assertTrue(j.events.indexOf("isolation 4")<j.events.indexOf("autocommit true"));
        assertEquals("close connection",j.events.getLast());
    }
    @Test void failedUpdateRollsBack(){var j=new Jdbc();j.updateFails=true;assertEquals("update",assertThrows(SQLException.class,j::pay).getMessage());assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));assertTrue(j.events.contains("autocommit true"));}
    @Test void zeroUpdatedRowsIsConflict(){var j=new Jdbc();j.zeroRows=true;assertThrows(it.medbook.exception.ConflittoPrenotazioneException.class,j::pay);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
    @Test void failedRollbackClosesWithoutRestoration(){var j=new Jdbc();j.updateFails=true;j.rollbackFails=true;var e=assertThrows(SQLException.class,j::pay);assertEquals("update",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("isolation 4"));assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());}
    @Test void isolationRestoreErrorAfterRollbackIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.isolationRestoreFails=true;var e=assertThrows(SQLException.class,j::pay);assertEquals("update",e.getMessage());assertEquals("isolation restore",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());}
    @Test void restoreErrorAfterCommitIsReported(){var j=new Jdbc();j.isolationRestoreFails=true;assertEquals("isolation restore",assertThrows(SQLException.class,j::pay).getMessage());assertFalse(j.events.contains("rollback"));assertEquals("close connection",j.events.getLast());}
    @Test void autocommitRestoreErrorIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.restoreFails=true;var e=assertThrows(SQLException.class,j::pay);assertEquals("restore",e.getSuppressed()[0].getMessage());assertEquals("close connection",j.events.getLast());}
}
