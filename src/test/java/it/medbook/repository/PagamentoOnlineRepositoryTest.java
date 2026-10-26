package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.model.StatoPagamento;
import it.medbook.payment.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagamentoOnlineRepositoryTest {
    static class BaseJdbc {
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
            if(name.equals("getString"))return sql.contains("ruolo_gestionale")?"OPERATORE_DESK":"CONFERMATA";
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
        void pay()throws SQLException{repo().registraInSedeAtomically(3,7,it.medbook.model.ModalitaPagamento.DESK_POS,new BigDecimal("80"));}
    }
    static class Jdbc extends BaseJdbc {
        final Map<Integer,Object> inserted=new HashMap<>();
        boolean wrongPersisted;
        GatewayPagamento gateway=new GatewayPagamentoSimulato();
        int clockReads;
        @Override PreparedStatement statement(String sql) {
            PreparedStatement original=super.statement(sql);
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                if(sql.contains("INSERT INTO pagamento")&&m.getName().startsWith("set"))inserted.put((Integer)a[0],a[1]);
                try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        @Override ResultSet rows(String sql) {
            if(!sql.startsWith("SELECT * FROM pagamento")) {
                ResultSet original=super.rows(sql);
                return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
                    if(m.getName().equals("getInt")&&a[0].equals("id_paziente"))return 7;
                    try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
            Map<String,Object> row=new HashMap<>();
            row.put("id_pagamento",42);row.put("id_prenotazione",3);row.put("modalita","ONLINE");
            row.put("importo",wrongPersisted?BigDecimal.ONE:inserted.get(2));row.put("stato",inserted.get(3));
            row.put("provider_payment_id",inserted.get(4));row.put("creato_il",inserted.get(5));row.put("pagato_il",inserted.get(6));
            int[] index={0};
            return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
                if(m.getName().equals("next"))return index[0]++==0;
                if(m.getName().equals("close")){events.add("close rows");return null;}
                if(m.getName().startsWith("get"))return row.get(a[0]);
                return defaultValue(m.getReturnType());
            });
        }
        @Override PagamentoRepository repo() {
            Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
                public Instant instant(){clockReads++;events.add("clock");return Instant.parse("2030-01-01T08:00:00.987654321Z");}};
            return new PagamentoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
                @Override public Connection getConnection(){events.add("connection");return connection();}
            },clock,(id,amount)->{events.add("gateway");return gateway.paga(id,amount);});
        }
        @Override void pay()throws SQLException {repo().registraOnlineAtomically(3,7,new BigDecimal("80.00"));}
    }
    @Test void lockOrderOneClockReadAndStableSimUuid()throws Exception {
        var j=new Jdbc();var response=j.repo().registraOnlineAtomically(3,7,new BigDecimal("80"));
        assertEquals(StatoPagamento.PAGATO,response.getStato());assertEquals(1,j.clockReads);
        assertEquals(LocalDateTime.of(2030,1,1,8,0),response.getCreatoIl());assertEquals(response.getCreatoIl(),response.getPagatoIl());
        String id=(String)j.inserted.get(4);assertTrue(id.startsWith("SIM-"));assertEquals(id.substring(4),UUID.fromString(id.substring(4)).toString());
        List<String> sql=j.events.stream().filter(e->e.startsWith("SELECT")||e.contains("INSERT INTO")).toList();
        assertTrue(sql.get(0).contains("paziente")&&sql.get(0).contains("FOR SHARE"));
        assertTrue(sql.get(2).contains("disponibilita")&&sql.get(2).contains("FOR UPDATE"));
        assertTrue(sql.get(3).contains("prenotazione")&&sql.get(3).contains("FOR UPDATE"));
        assertTrue(sql.get(4).contains("ORDER BY id_pagamento FOR UPDATE"));
        assertTrue(j.events.indexOf(sql.get(4))<j.events.indexOf("gateway"));assertTrue(j.events.indexOf("gateway")<j.events.indexOf("clock"));
        assertEquals(1,Collections.frequency(j.events,"connection"));assertEquals(1,Collections.frequency(j.events,"commit"));
        assertEquals(List.of("isolation 2","isolation 4"),j.events.stream().filter(e->e.startsWith("isolation ")).toList());
        assertFalse(j.events.contains("rollback"));assertEquals("close connection",j.events.getLast());
    }
    @Test void failedSimulationIsPersistedTerminalWithoutPaidTimestamp()throws Exception {
        var j=new Jdbc();j.gateway=new GatewayPagamentoSimulato(StatoPagamento.FALLITO);var r=j.repo().registraOnlineAtomically(3,7,new BigDecimal("80"));
        assertEquals(StatoPagamento.FALLITO,r.getStato());assertNull(r.getPagatoIl());assertNull(j.inserted.get(6));assertTrue(j.events.contains("commit"));
    }
    @Test void technicalAndInvalidGatewayOutcomesRollbackBeforeInsert() {
        for(GatewayPagamento gateway:List.<GatewayPagamento>of((id,a)->{throw new IllegalStateException("synthetic");},(id,a)->{throw new IllegalArgumentException("technical");},(id,a)->StatoPagamento.IN_ATTESA,(id,a)->null)) {
            var j=new Jdbc();j.gateway=gateway;assertThrows(IllegalStateException.class,j::pay);
            assertTrue(j.inserted.isEmpty());assertEquals(0,j.clockReads);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
            assertEquals("close connection",j.events.getLast());
        }
    }
    @Test void invalidGeneratedKeyAndReadbackMismatchRollback() {
        for(boolean key:new boolean[]{false,true}) {
            var j=new Jdbc();j.invalidKey=key;j.wrongPersisted=!key;
            assertThrows(SQLException.class,j::pay);assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
        }
    }
    @Test void commitFailureRollsBackAndRestores() {
        var j=new Jdbc();j.commitFails=true;assertThrows(SQLException.class,j::pay);
        assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());
    }
    @Test void rollbackFailureSuppressesAndNeverReenablesAutocommit() {
        var j=new Jdbc();j.commitFails=true;j.rollbackFails=true;
        var e=assertThrows(SQLException.class,j::pay);assertEquals("rollback",e.getSuppressed()[0].getMessage());
        assertFalse(j.events.contains("isolation 4"));assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());
    }
    @Test void restoreFailureAfterSuccessfulCommitDoesNotRollbackOrRetry() {
        var j=new Jdbc();j.isolationRestoreFails=true;assertThrows(SQLException.class,j::pay);
        assertEquals(1,Collections.frequency(j.events,"commit"));assertFalse(j.events.contains("rollback"));assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());
    }
    @Test void restoreFailureIsSuppressedAfterRollback() {
        var j=new Jdbc();j.commitFails=true;j.isolationRestoreFails=true;
        var e=assertThrows(SQLException.class,j::pay);assertEquals("isolation restore",e.getSuppressed()[0].getMessage());
        assertFalse(j.events.contains("autocommit true"));assertEquals("close connection",j.events.getLast());
    }
}
