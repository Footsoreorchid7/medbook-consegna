package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.RegistrazioneDuplicataException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrazioneRepositoryTest {
    private final List<String> actions=new ArrayList<>();
    private SQLException insertError,rollbackError,restoreError,commitError;
    private long generated=7;
    private int closedStatements,closedResults;
    private Object result(Class<?> type,java.lang.reflect.InvocationHandler handler){return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},handler);}
    private Connection connection(){return (Connection)result(Connection.class,(proxy,method,args)->switch(method.getName()){
        case "getAutoCommit" -> true;
        case "setAutoCommit" -> {actions.add("autocommit:"+args[0]);if(Boolean.TRUE.equals(args[0])&&restoreError!=null){throw restoreError;}yield null;}
        case "prepareStatement" -> statement((String)args[0]);
        case "commit" -> {actions.add("commit");if(commitError!=null){throw commitError;}yield null;}
        case "rollback" -> {actions.add("rollback");if(rollbackError!=null){throw rollbackError;}yield null;}
        case "close" -> {actions.add("close");yield null;}
        default -> null;
    });}
    private PreparedStatement statement(String sql){return (PreparedStatement)result(PreparedStatement.class,(proxy,method,args)->switch(method.getName()){
        case "executeUpdate" -> {actions.add(sql.startsWith("INSERT INTO utente")?"utente":"paziente");if(sql.startsWith("INSERT INTO paziente")&&insertError!=null){throw insertError;}assertTrue(!sql.startsWith("INSERT INTO utente")||sql.endsWith("VALUES(?,?,?,?,NULL,?,?)"));yield 1;}
        case "getGeneratedKeys" -> result(ResultSet.class,(p,m,a)->switch(m.getName()){case "next" -> true;case "getLong" -> generated;case "close" -> {closedResults++;yield null;}default -> null;});
        case "close" -> {closedStatements++;yield null;}
        default -> null;
    });}
    private Integer register() throws SQLException {
        var repo=new RegistrazioneRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return connection();}});
        return repo.registraPaziente("Fixture","Sintetico","fixture@example.invalid","hash-sintetico","TSTTST90A01A000Q",LocalDate.of(1990,1,1),null);
    }
    @Test void sameConnectionCommitsBothInsertsAndRestoresAutoCommit() throws Exception {
        assertEquals(7,register());assertEquals(List.of("autocommit:false","utente","paziente","commit","autocommit:true","close"),actions);
        assertEquals(2,closedStatements);assertEquals(1,closedResults);
    }
    @Test void secondInsertFailureRollsBackAll(){insertError=new SQLException("Simulato","45000",1644);assertSame(insertError,assertThrows(SQLException.class,this::register));assertEquals(List.of("autocommit:false","utente","paziente","rollback","autocommit:true","close"),actions);}
    @Test void uniqueViolationIsGenericOnlyAfterRollback(){insertError=new SQLException("Dato riservato","23000",1062);var e=assertThrows(RegistrazioneDuplicataException.class,this::register);assertSame(insertError,e.getCause());assertEquals("Registrazione non disponibile con i dati forniti.",e.getMessage());assertTrue(actions.contains("rollback"));assertFalse(actions.contains("commit"));}
    @Test void foreignKeyErrorIsNotMistakenForDuplicate(){insertError=new SQLException("Simulato","23000",1452);assertSame(insertError,assertThrows(SQLException.class,this::register));}
    @Test void rollbackFailureDoesNotEnableAutoCommit(){insertError=new SQLException("Simulato");rollbackError=new SQLException("Rollback");var e=assertThrows(SQLException.class,this::register);assertSame(rollbackError,e.getSuppressed()[0]);assertFalse(actions.contains("autocommit:true"));assertEquals("close",actions.getLast());}
    @Test void restoreFailureIsSuppressedOnOriginalError(){insertError=new SQLException("Simulato");restoreError=new SQLException("Ripristino");var e=assertThrows(SQLException.class,this::register);assertSame(restoreError,e.getSuppressed()[0]);assertEquals("close",actions.getLast());}
    @Test void restoreFailureAfterCommitIsReportedAndConnectionClosed(){restoreError=new SQLException("Ripristino");assertSame(restoreError,assertThrows(SQLException.class,this::register));assertTrue(actions.contains("commit"));assertEquals("close",actions.getLast());}
    @Test void commitFailureCausesRollback(){commitError=new SQLException("Commit");assertSame(commitError,assertThrows(SQLException.class,this::register));assertTrue(actions.contains("rollback"));}
    @Test void unrepresentableGeneratedIdRollsBack(){generated=(long)Integer.MAX_VALUE+1;assertThrows(SQLException.class,this::register);assertFalse(actions.contains("paziente"));assertTrue(actions.contains("rollback"));}
}
