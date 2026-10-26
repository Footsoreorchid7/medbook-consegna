package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.model.Stanza;
import it.medbook.exception.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StanzaRepositoryTest {
    static Stanza value() {var p=new Stanza();p.setNome("test");return p;}
    static class Jdbc {
        List<String> events=new ArrayList<>();Map<Integer,Object> bindings=new HashMap<>();
        boolean nullKey,multipleKeys;
        boolean absent,noKey,updateFails,commitFails,rollbackFails,restoreFails,autoRestoreFails;
        boolean rowsCloseFails,statementCloseFails,connectionCloseFails;
        SQLException writeError; boolean originalAuto=true;
        int originalIsolation=Connection.TRANSACTION_REPEATABLE_READ;
        int changed=1;long key=7;
        Object zero(Class<?> c){if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;return null;}
        ResultSet rows(boolean hasRow,boolean generated){int[] n={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->{
            return switch(m.getName()) {case "next" -> hasRow&&n[0]++<(generated&&multipleKeys?2:1);case "getInt" -> 7;case "getString" -> a[0].equals("nome")?"Sala":null;case "getLong" -> generated&&nullKey?0L:key;case "wasNull" -> generated&&nullKey;case "close" -> {events.add("rows closed");if(rowsCloseFails)throw new SQLException("rows close");yield null;}default -> zero(m.getReturnType());};
        });}
        PreparedStatement statement(String sql){return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set")){bindings.put((Integer)a[0],a[1]);return null;}
            return switch(m.getName()) {
                case "executeQuery" -> {events.add(sql);yield rows(!absent,false);}
                case "executeUpdate" -> {events.add(sql);if(writeError!=null)throw writeError;if(updateFails)throw new SQLException("write");yield changed;}
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
        StanzaRepository repo(){return new StanzaRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection(){events.add("connection");return connection();}
        });}
        Stanza update()throws SQLException{return repo().updateAtomically(7,value());}
    }
    @Test void updateLocksOnlyRoomAndPreservesIsolation()throws Exception {
        var j=new Jdbc();var result=j.update();assertEquals(7,result.getIdStanza());
        var sql=j.events.stream().filter(e->e.startsWith("SELECT")||e.startsWith("UPDATE")).toList();
        assertEquals(List.of("SELECT id_stanza FROM stanza WHERE id_stanza = ? FOR UPDATE",
                "UPDATE stanza SET nome = ?, descrizione = ? WHERE id_stanza = ?"),sql);
        assertFalse(j.events.stream().anyMatch(e->e.startsWith("isolation")));
        assertTrue(j.events.indexOf("commit")<j.events.indexOf("auto true"));assertEquals("close",j.events.getLast());
        assertEquals(1,Collections.frequency(j.events,"connection"));assertEquals(7,j.bindings.get(3));
    }
    @Test void identicalPutMayReportZero()throws Exception {var j=new Jdbc();j.changed=0;j.update();assertTrue(j.events.contains("commit"));}
    @Test void missingResourceRollsBack(){var j=new Jdbc();j.absent=true;assertThrows(RisorsaNonTrovataException.class,j::update);assertTrue(j.events.contains("rollback"));}
    @Test void creationBindsValuesAndObtainsGeneratedKey()throws Exception {
        var j=new Jdbc();var result=j.repo().create(value());assertEquals(7,result.getIdStanza());assertEquals("test",j.bindings.get(1));assertNull(j.bindings.get(2));assertEquals(2,j.bindings.size());
        assertFalse(j.events.stream().anyMatch(e->e.startsWith("isolation")));assertTrue(j.events.contains("commit"));assertEquals("close",j.events.getLast());
    }
    @Test void unavailableOrInvalidGeneratedKeyRollsBack() {
        for(long key:new long[]{0,-1,(long)Integer.MAX_VALUE+1}){var j=new Jdbc();j.key=key;assertThrows(SQLException.class,()->j.repo().create(value()));assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
        var j=new Jdbc();j.noKey=true;assertThrows(SQLException.class,()->j.repo().create(value()));assertTrue(j.events.contains("rollback"));
    }
    @Test void writeFailureRollsBackAndRestores(){var j=new Jdbc();j.updateFails=true;assertEquals("write",assertThrows(SQLException.class,j::update).getMessage());assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("auto true"));}
    @Test void rollbackFailureDoesNotToggleAutocommit(){var j=new Jdbc();j.updateFails=true;j.rollbackFails=true;var e=assertThrows(SQLException.class,j::update);assertEquals("write",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("auto true"));assertFalse(j.events.contains("isolation 4"));assertEquals("close",j.events.getLast());}
    @Test void restoreFailureAfterRollbackIsSuppressed(){var j=new Jdbc();j.updateFails=true;j.autoRestoreFails=true;var e=assertThrows(SQLException.class,j::update);assertEquals("write",e.getMessage());assertEquals("auto restore",e.getSuppressed()[0].getMessage());assertEquals("close",j.events.getLast());}
    @Test void restoreFailureAfterCommitDoesNotRollback(){var j=new Jdbc();j.autoRestoreFails=true;assertEquals("auto restore",assertThrows(SQLException.class,j::update).getMessage());assertFalse(j.events.contains("rollback"));assertEquals("close",j.events.getLast());}
    @Test void findAllUsesOrderedNonLockingQueryAndClosesResources()throws Exception {
        var j=new Jdbc();var list=j.repo().findAll();assertEquals(1,list.size());assertEquals(7,list.getFirst().getIdStanza());assertEquals("Sala",list.getFirst().getNome());assertNull(list.getFirst().getDescrizione());
        assertTrue(j.events.contains("SELECT id_stanza, nome, descrizione FROM stanza ORDER BY id_stanza ASC"));assertFalse(j.events.contains("auto false"));assertEquals("close",j.events.getLast());
        j=new Jdbc();j.absent=true;assertTrue(j.repo().findAll().isEmpty());assertTrue(j.events.contains("rows closed"));assertTrue(j.events.contains("statement closed"));
    }
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
        /* Connessioni simulate nuove: autocommit=false non implica transazione avviata. */
        for(boolean auto:new boolean[]{true,false}) for(int isolation:new int[]{Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_SERIALIZABLE}) {
            for(boolean fail:new boolean[]{false,true}) {
                var j=new Jdbc();j.originalAuto=auto;j.originalIsolation=isolation;j.updateFails=fail;
                if(fail)assertEquals("write",assertThrows(SQLException.class,j::update).getMessage());else j.update();
                assertEquals(List.of("auto "+auto,"close"),j.events.subList(j.events.size()-2,j.events.size()));assertFalse(j.events.stream().anyMatch(e->e.startsWith("isolation")));
                assertTrue(j.events.contains(fail?"rollback":"commit"));
            }
        }
    }
    @Test void onlyRealNameUniqueViolationBecomesConflict() {
        var j=new Jdbc();j.writeError=new SQLException("Duplicate entry 'x' for key 'stanza.uq_stanza_nome'","23000",1062);
        var e=assertThrows(StanzaRepository.NomeDuplicatoException.class,()->j.repo().create(value()));
        assertSame(j.writeError,e.getCause());assertTrue(j.events.contains("rollback"));assertEquals("close",j.events.getLast());
        for(SQLException failure:List.of(new SQLException("other unique","23000",1062),
                new SQLException("for key 'PRIMARY'","23000",1062),new SQLException("uq_stanza_nome","23000",1452),
                new SQLException("for key 'uq_stanza_nome'","HY000",1062))) {
            var q=new Jdbc();q.writeError=failure;assertSame(failure,assertThrows(SQLException.class,()->q.repo().create(value())));
        }
    }
    @Test void sqlNullGeneratedKeyRollsBackAndCloses() {
        var j=new Jdbc();j.nullKey=true;
        assertThrows(SQLException.class,()->j.repo().create(value()));
        assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
        assertTrue(j.events.contains("auto true"));assertEquals("close",j.events.getLast());
    }
    @Test void multipleGeneratedKeysRollBackAndClose() {
        var j=new Jdbc();j.multipleKeys=true;
        assertThrows(SQLException.class,()->j.repo().create(value()));
        assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
        assertTrue(j.events.contains("auto true"));assertEquals("close",j.events.getLast());
    }
    @Test void chainedDuplicateDoesNotChangeClassificationOfPrimaryException() {
        for(SQLException primary:List.of(new SQLException("wrapper"),
                new SQLException("Duplicate entry for key 'PRIMARY'","23000",1062))) {
            var duplicate=new SQLException("Duplicate entry for key 'stanza.uq_stanza_nome'","23000",1062);
            primary.setNextException(duplicate);var j=new Jdbc();j.writeError=primary;
            assertSame(primary,assertThrows(SQLException.class,()->j.repo().create(value())));
            assertSame(duplicate,primary.getNextException());
            assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
            assertEquals("close",j.events.getLast());
        }
    }
    @Test void directDuplicatePreservesItsExceptionChain() {
        var primary=new SQLException("Duplicate entry for key 'uq_stanza_nome'","23000",1062);
        var secondary=new SQLException("secondary");primary.setNextException(secondary);
        var j=new Jdbc();j.writeError=primary;
        var error=assertThrows(StanzaRepository.NomeDuplicatoException.class,()->j.repo().create(value()));
        assertSame(primary,error.getCause());assertSame(secondary,primary.getNextException());
        assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
    }
}
