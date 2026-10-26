package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.model.Prestazione;
import it.medbook.exception.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrestazioneRepositoryTest {
    static Prestazione value() {var p=new Prestazione();p.setNome("test");p.setDurata(30);p.setPrezzo(new BigDecimal("10.00"));return p;}
    static class Jdbc {
        List<String> events=new ArrayList<>();Map<Integer,Object> bindings=new HashMap<>();
        boolean absent,associated,noKey,updateFails,commitFails,rollbackFails,restoreFails,autoRestoreFails;
        boolean rowsCloseFails,statementCloseFails,connectionCloseFails;
        boolean originalAuto=true;
        int originalIsolation=Connection.TRANSACTION_REPEATABLE_READ;
        int changed=1, oldDuration=30;long key=7;
        Object zero(Class<?> c){if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;return null;}
        ResultSet rows(boolean hasRow,boolean generated){int[] n={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->{
            return switch(m.getName()) {case "next" -> hasRow&&n[0]++==0;case "getInt" -> oldDuration;case "getLong" -> key;case "wasNull" -> false;case "close" -> {events.add("rows closed");if(rowsCloseFails)throw new SQLException("rows close");yield null;}default -> zero(m.getReturnType());};
        });}
        PreparedStatement statement(String sql){return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set")){bindings.put((Integer)a[0],a[1]);return null;}
            return switch(m.getName()) {
                case "executeQuery" -> {events.add(sql);yield rows(sql.contains("disponibilita")?associated:!absent,false);}
                case "executeUpdate" -> {events.add(sql);if(updateFails)throw new SQLException("write");yield changed;}
                case "getGeneratedKeys" -> rows(!noKey,true);
                case "close" -> {events.add("statement closed");if(statementCloseFails)throw new SQLException("statement close");yield null;}
                default -> zero(m.getReturnType());
            };
        });}
        Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            return switch(m.getName()) {
                case "getAutoCommit" -> originalAuto;
                case "getTransactionIsolation" -> originalIsolation;
                case "setAutoCommit" -> {events.add("auto "+a[0]);if(Boolean.TRUE.equals(a[0])&&autoRestoreFails)throw new SQLException("auto restore");yield null;}
                case "setTransactionIsolation" -> {events.add("isolation "+a[0]);if((int)a[0]==Connection.TRANSACTION_REPEATABLE_READ&&restoreFails)throw new SQLException("restore");yield null;}
                case "prepareStatement" -> statement((String)a[0]);
                case "commit" -> {events.add("commit");if(commitFails)throw new SQLException("commit");yield null;}
                case "rollback" -> {events.add("rollback");if(rollbackFails)throw new SQLException("rollback");yield null;}
                case "close" -> {events.add("close");if(connectionCloseFails)throw new SQLException("connection close");yield null;}
                default -> zero(m.getReturnType());
            };
        });}
        PrestazioneRepository repo(){return new PrestazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection(){events.add("connection");return connection();}
        });}
        Prestazione update()throws SQLException{return repo().updateAtomically(7,value());}
    }
    @Test void updateLocksOnlyParentAndRestoresProperties()throws Exception {
        var j=new Jdbc();j.oldDuration=20;var result=j.update();assertEquals(7,result.getIdPrestazione());
        var sql=j.events.stream().filter(e->e.startsWith("SELECT")||e.startsWith("UPDATE")).toList();assertEquals(3,sql.size());
        assertTrue(sql.get(0).contains("prestazione")&&sql.get(0).endsWith("FOR UPDATE"));
        assertEquals("SELECT id_disponibilita FROM disponibilita WHERE id_prestazione = ? LIMIT 1",sql.get(1));
        assertTrue(sql.get(2).startsWith("UPDATE prestazione SET"));
        assertTrue(j.events.indexOf("isolation 2")<j.events.indexOf("auto false"));
        assertTrue(j.events.indexOf("commit")<j.events.indexOf("isolation 4"));
        assertTrue(j.events.indexOf("isolation 4")<j.events.indexOf("auto true"));assertEquals("close",j.events.getLast());
        assertEquals(1,Collections.frequency(j.events,"connection"));assertEquals(7,j.bindings.get(5));
    }
    @Test void unchangedDurationSkipsReferencesAndIdenticalPutMayReportZero()throws Exception {
        var j=new Jdbc();j.associated=true;j.changed=0;j.update();assertFalse(j.events.stream().anyMatch(e->e.contains("FROM disponibilita")));assertTrue(j.events.contains("commit"));
    }
    @Test void durationConflictRollsBackWithoutUpdate(){var j=new Jdbc();j.oldDuration=20;j.associated=true;
        assertThrows(ConflittoConfigurazioneException.class,j::update);assertTrue(j.events.contains("rollback"));assertFalse(j.events.stream().anyMatch(e->e.startsWith("UPDATE")));assertTrue(j.events.contains("auto true"));}
    @Test void missingResourceRollsBack(){var j=new Jdbc();j.absent=true;assertThrows(RisorsaNonTrovataException.class,j::update);assertTrue(j.events.contains("rollback"));}
    @Test void creationBindsValuesAndObtainsGeneratedKey()throws Exception {
        var j=new Jdbc();var result=j.repo().create(value());assertEquals(7,result.getIdPrestazione());assertEquals("test",j.bindings.get(1));assertNull(j.bindings.get(2));assertEquals(new BigDecimal("10.00"),j.bindings.get(4));
        assertFalse(j.events.stream().anyMatch(e->e.startsWith("isolation")));assertTrue(j.events.contains("commit"));assertEquals("close",j.events.getLast());
    }
    @Test void unavailableOrInvalidGeneratedKeyRollsBack() {
        for(long key:new long[]{0,-1,(long)Integer.MAX_VALUE+1}){var j=new Jdbc();j.key=key;assertThrows(SQLException.class,()->j.repo().create(value()));assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
        var j=new Jdbc();j.noKey=true;assertThrows(SQLException.class,()->j.repo().create(value()));assertTrue(j.events.contains("rollback"));
    }
    @Test void writeFailureRollsBackAndRestores(){var j=new Jdbc();j.updateFails=true;assertEquals("write",assertThrows(SQLException.class,j::update).getMessage());assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("auto true"));}
    @Test void rollbackFailureDoesNotToggleAutocommit(){var j=new Jdbc();j.updateFails=true;j.rollbackFails=true;var e=assertThrows(SQLException.class,j::update);assertEquals("write",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("auto true"));assertFalse(j.events.contains("isolation 4"));assertEquals("close",j.events.getLast());}
    @Test void restoreFailureAfterRollbackIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.restoreFails=true;var e=assertThrows(SQLException.class,j::update);assertEquals("write",e.getMessage());assertEquals("restore",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("auto true"));assertEquals("close",j.events.getLast());}
    @Test void restoreFailureAfterCommitDoesNotRollback(){var j=new Jdbc();j.restoreFails=true;assertEquals("restore",assertThrows(SQLException.class,j::update).getMessage());assertFalse(j.events.contains("rollback"));assertFalse(j.events.contains("auto true"));assertEquals("close",j.events.getLast());}
    @Test void autocommitRestoreFailureIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.autoRestoreFails=true;var e=assertThrows(SQLException.class,j::update);assertEquals("write",e.getMessage());assertEquals("auto restore",e.getSuppressed()[0].getMessage());assertEquals("close",j.events.getLast());}
    @Test void failedCommitAttemptsRollback(){var j=new Jdbc();j.commitFails=true;assertEquals("commit",assertThrows(SQLException.class,j::update).getMessage());assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("auto true"));}
    @Test void resourceCloseFailuresPreservePrimaryException() {
        var j=new Jdbc();j.updateFails=true;j.statementCloseFails=true;j.connectionCloseFails=true;
        var e=assertThrows(SQLException.class,()->j.repo().create(value()));assertEquals("write",e.getMessage());
        assertEquals(List.of("statement close","connection close"),Arrays.stream(e.getSuppressed()).map(Throwable::getMessage).toList());
        assertTrue(j.events.contains("rollback"));assertEquals("close",j.events.getLast());
    }
    @Test void resultSetCloseFailureRollsBackBeforeAnyWrite() {
        var j=new Jdbc();j.rowsCloseFails=true;j.statementCloseFails=true;
        var e=assertThrows(SQLException.class,j::update);assertEquals("rows close",e.getMessage());
        assertEquals("statement close",e.getSuppressed()[0].getMessage());
        assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
        assertFalse(j.events.stream().anyMatch(x->x.startsWith("UPDATE")));assertEquals("close",j.events.getLast());
    }
    @Test void connectionCloseFailureAfterCommitIsPropagatedWithoutRollback() {
        var j=new Jdbc();j.connectionCloseFails=true;
        assertEquals("connection close",assertThrows(SQLException.class,j::update).getMessage());
        assertTrue(j.events.contains("commit"));assertFalse(j.events.contains("rollback"));assertEquals("close",j.events.getLast());
    }
    @Test void differentOriginalPropertiesAreRestoredOnSuccessAndRollback() throws Exception {
        /* Connessioni simulate nuove: autocommit=false non dimostra una transazione già avviata. */
        for(boolean auto:new boolean[]{true,false}) for(int isolation:new int[]{Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_SERIALIZABLE}) {
            for(boolean fail:new boolean[]{false,true}) {
                var j=new Jdbc();j.originalAuto=auto;j.originalIsolation=isolation;j.updateFails=fail;
                if(fail)assertEquals("write",assertThrows(SQLException.class,j::update).getMessage());else j.update();
                assertEquals(List.of("isolation "+isolation,"auto "+auto,"close"),j.events.subList(j.events.size()-3,j.events.size()));
                assertTrue(j.events.contains(fail?"rollback":"commit"));
            }
        }
    }

}
