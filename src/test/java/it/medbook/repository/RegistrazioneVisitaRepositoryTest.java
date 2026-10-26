package it.medbook.repository;

import it.medbook.config.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneVisitaRepositoryTest {
    static class Jdbc {
        final List<String> events=new ArrayList<>();
        final List<List<Integer>> bindings=new ArrayList<>();
        boolean originalAutoCommit=true;
        int originalIsolation=Connection.TRANSACTION_REPEATABLE_READ;
        boolean commitFails,rollbackFails,restoreFails,isolationRestoreFails,updateFails,zeroRows;
        Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
        ResultSet rows(String sql){int[] index={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            String name=m.getName();if(name.equals("close")){events.add("close rows");return null;}
            if(name.equals("next"))return !sql.contains("FROM pagamento") && index[0]++==0;
            if(name.equals("getInt"))return "id_medico_effettivo".equals(a[0])?7:9;
            if(name.equals("getBoolean"))return false;
            if(name.equals("getString"))return "CONFERMATA";
            if(name.equals("getObject"))return "data_ora_fine".equals(a[0])?LocalDateTime.of(2030,1,1,8,0):7;
            return defaultValue(m.getReturnType());
        });}
        PreparedStatement statement(String sql){List<Integer> bound=new ArrayList<>();bindings.add(bound);return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().equals("setInt")){bound.add((Integer)a[1]);return null;}
            if(m.getName().equals("executeQuery")){events.add(sql);return rows(sql);}
            if(m.getName().equals("executeUpdate")){events.add(sql);if(updateFails)throw new SQLException("update");return zeroRows?0:1;}
            if(m.getName().equals("close")){events.add("close statement");return null;}
            return defaultValue(m.getReturnType());
        });}
        Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            switch(m.getName()){
                case "getAutoCommit":return originalAutoCommit;
                case "getTransactionIsolation":return originalIsolation;
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
        void visit()throws SQLException{repo().registraVisitaAtomically(3,7);}
    }
    @Test void oneConnectionOrderedLocksAndAtomicUpdate() throws Exception {
        var j=new Jdbc(); j.visit();
        var sql=j.events.stream().filter(e->e.contains("SELECT")||e.contains("UPDATE prenotazione")).toList();
        assertEquals(5,sql.size());assertFalse(sql.get(0).contains("FOR UPDATE"));
        assertTrue(sql.get(1).contains("disponibilita")&&sql.get(1).contains("FOR UPDATE"));
        assertTrue(sql.get(2).contains("prenotazione")&&sql.get(2).contains("FOR UPDATE"));
        assertTrue(sql.get(3).contains("ORDER BY id_pagamento FOR UPDATE"));
        String update=sql.get(4);
        assertTrue(update.contains("SET stato='EFFETTUATA'"));
        assertTrue(update.contains("AND stato='CONFERMATA' AND indisponibilita_segnalata=FALSE"));
        assertTrue(update.contains("id_medico_effettivo=? AND id_disponibilita=?"));
        assertEquals("UPDATE prenotazione SET stato='EFFETTUATA'",update.substring(0,update.indexOf("WHERE")).strip());
        assertEquals(List.of(List.of(3,7),List.of(9),List.of(3),List.of(3),List.of(3,7,9)),j.bindings);
        assertEquals(1,Collections.frequency(j.events,"connection"));assertEquals(5,Collections.frequency(j.events,"close statement"));assertEquals(4,Collections.frequency(j.events,"close rows"));
        assertEquals(List.of("isolation 2","isolation 4"),j.events.stream().filter(e->e.startsWith("isolation ")).toList());
        assertTrue(j.events.indexOf("isolation 2")<j.events.indexOf("autocommit false"));
        assertTrue(j.events.indexOf("commit")<j.events.indexOf("isolation 4"));
        assertTrue(j.events.indexOf("isolation 4")<j.events.indexOf("autocommit true"));
        assertEquals("close connection",j.events.getLast());
    }
    @Test void failedUpdateRollsBack(){var j=new Jdbc();j.updateFails=true;assertEquals("update",assertThrows(SQLException.class,j::visit).getMessage());assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));assertTrue(j.events.contains("autocommit true"));}
    @Test void zeroUpdatedRowsIsConflict(){var j=new Jdbc();j.zeroRows=true;assertThrows(it.medbook.exception.ConflittoPrenotazioneException.class,j::visit);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
    @Test void failedRollbackClosesWithoutRestoration(){var j=new Jdbc();j.updateFails=true;j.rollbackFails=true;var e=assertThrows(SQLException.class,j::visit);assertEquals("update",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("isolation 4"));assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());}
    @Test void isolationRestoreErrorAfterRollbackIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.isolationRestoreFails=true;var e=assertThrows(SQLException.class,j::visit);assertEquals("update",e.getMessage());assertEquals("isolation restore",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());}
    @Test void restoreErrorAfterCommitIsReported(){var j=new Jdbc();j.isolationRestoreFails=true;assertEquals("isolation restore",assertThrows(SQLException.class,j::visit).getMessage());assertFalse(j.events.contains("rollback"));assertEquals("close connection",j.events.getLast());}
    @Test void autocommitRestoreErrorIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.restoreFails=true;var e=assertThrows(SQLException.class,j::visit);assertEquals("restore",e.getSuppressed()[0].getMessage());assertEquals("close connection",j.events.getLast());}
    @Test void failedCommitRollsBackAndRestores() {
        var j=new Jdbc();j.commitFails=true;assertEquals("commit",assertThrows(SQLException.class,j::visit).getMessage());
        assertTrue(j.events.contains("rollback"));assertTrue(j.events.indexOf("rollback")<j.events.indexOf("isolation 4"));assertEquals("close connection",j.events.getLast());
    }
    @Test void originalAutocommitAndIsolationArePreserved() throws Exception {
        var j=new Jdbc();j.originalAutoCommit=false;j.originalIsolation=Connection.TRANSACTION_SERIALIZABLE;j.visit();
        assertEquals(List.of("isolation 2","isolation 8"),j.events.stream().filter(e->e.startsWith("isolation ")).toList());
        assertFalse(j.events.contains("autocommit true"));assertEquals(2,Collections.frequency(j.events,"autocommit false"));
    }
    @Test void invalidIdsDoNotOpenConnection() {
        var j=new Jdbc();for(Integer id:new Integer[]{null,0,-1}){
            assertThrows(IllegalArgumentException.class,()->j.repo().registraVisitaAtomically(id,7));
            assertThrows(IllegalArgumentException.class,()->j.repo().registraVisitaAtomically(3,id));
        }assertTrue(j.events.isEmpty());
    }

}
