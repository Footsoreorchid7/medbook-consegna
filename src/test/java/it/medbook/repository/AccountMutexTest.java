package it.medbook.repository;
import it.medbook.config.*;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/* Protocollo mutex account verificato con lavoro generico indipendente dalle regole applicative. */
class AccountMutexTest {
    static class Jdbc {
        final List<String> events=new ArrayList<>();final Set<String> fail=new HashSet<>();
        boolean auto=true,closed,applyIsolationBeforeFailure;int isolation=Connection.TRANSACTION_REPEATABLE_READ;Integer lock=1,release=1;String catalog="medbook_test";
        Connection connection;
        void event(String e)throws SQLException{events.add(e);if(fail.contains(e))throw new SQLException(e);}
        ResultSet rows(String sql) {
            boolean get=sql.contains("GET_LOCK");Integer value=get?lock:release;int[] n={0};
            return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
                case "next" -> n[0]++==0;
                case "getInt" -> value==null?0:value;
                case "wasNull" -> value==null;
                default -> null;
            });
        }
        PreparedStatement statement(String sql) {
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->switch(m.getName()) {
                case "executeQuery" -> {event(sql.equals("SELECT GET_LOCK(?, 5)")?"acquire":sql.equals("SELECT RELEASE_LOCK(?)")?"release":sql);yield rows(sql);}
                default -> null;
            });
        }
        Connection connection() {
            connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
                case "getAutoCommit" -> auto;
                case "getTransactionIsolation" -> isolation;
                case "getCatalog" -> catalog;
                case "setTransactionIsolation" -> {if(applyIsolationBeforeFailure)isolation=(int)a[0];event("isolation "+a[0]);isolation=(int)a[0];yield null;}
                case "setAutoCommit" -> {event("auto "+a[0]);auto=(boolean)a[0];yield null;}
                case "prepareStatement" -> statement((String)a[0]);
                case "commit" -> {event("commit");yield null;}
                case "rollback" -> {event("rollback");yield null;}
                case "close" -> {closed=true;event("close");yield null;}
                default -> null;
            });
            return connection;
        }
        ConnectionFactory factory(){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return connection();}};}
        <T> T run(AccountMutex.Work<T> work)throws SQLException{return AccountMutex.execute(factory(),work);}
    }
    @Test void lockNameIsDeterministicPerCatalogAndShared()throws Exception {
        // Valori attesi calcolati indipendentemente con SHA-256, primi venti byte.
        assertEquals("medbook.accounts.9ed54d7c1cf88474137abaed94c78c504b8ad3b6",AccountMutex.lockName("medbook_test"));
        assertEquals("medbook.accounts.abd530b32aed72c8f4ee170958a1d3fa96298a72",AccountMutex.lockName("medbook_test_uc26"));
        assertEquals("medbook.accounts.97c916cd94785d7b9b52ae7013c267854f275691",AccountMutex.lockName("è"));
        assertEquals(AccountMutex.lockName("x".repeat(500)),UtenteRepository.accountLockName("x".repeat(500)));
        assertTrue(AccountMutex.lockName("x".repeat(500)).length()<=64);
        for(String catalog:new String[]{null,""," "})assertThrows(SQLException.class,()->AccountMutex.lockName(catalog));
    }
    @Test void genericWorkRunsOnLockedConnectionInsideReadCommittedTransaction()throws Exception {
        var j=new Jdbc();Connection[] seen={null};
        Integer result=j.run(c->{seen[0]=c;j.events.add("work");return 42;});
        assertEquals(42,result);assertSame(j.connection,seen[0]);
        assertEquals(List.of("acquire","isolation 2","auto false","work","commit","isolation 4","auto true","release","close"),j.events);
        assertTrue(j.auto);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,j.isolation);
    }
    @Test void timeoutAndInvalidLockResultsNeverRunWork() {
        var j=new Jdbc();j.lock=0;
        assertInstanceOf(UtenteRepository.LockAccountTimeoutException.class,assertThrows(SQLException.class,()->j.run(c->{j.events.add("work");return null;})));
        assertEquals(List.of("acquire","close"),j.events);
        for(Integer value:new Integer[]{null,-1,2}) {
            var k=new Jdbc();k.lock=value;var e=assertThrows(SQLException.class,()->k.run(c->{k.events.add("work");return null;}));
            assertFalse(e instanceof UtenteRepository.LockAccountTimeoutException);assertEquals(List.of("acquire","close"),k.events);
        }
        var k=new Jdbc();k.auto=false;assertThrows(SQLException.class,()->k.run(c->"never"));assertEquals(List.of("close"),k.events);
    }
    @Test void workFailureRollsBackRestoresReleasesAndRethrowsSameInstance() {
        for(Throwable failure:new Throwable[]{new SQLException("sql"),new IllegalStateException("runtime"),new AssertionError("error")}) {
            var j=new Jdbc();
            var thrown=assertThrows(Throwable.class,()->j.run(c->{
                if(failure instanceof SQLException e)throw e;if(failure instanceof RuntimeException e)throw e;throw (Error)failure;}));
            assertSame(failure,thrown);
            assertEquals(List.of("acquire","isolation 2","auto false","rollback","isolation 4","auto true","release","close"),j.events);
        }
    }
    @Test void isolationFailureAfterLockReleasesWithoutStartingTransaction() {
        /* Transazione non iniziata: nessun rollback o modifica autocommit; il mutex acquisito va rilasciato. */
        var j=new Jdbc();j.fail.add("isolation 2");
        var e=assertThrows(SQLException.class,()->j.run(c->{j.events.add("work");return null;}));
        assertEquals("isolation 2",e.getMessage());assertEquals(0,e.getSuppressed().length);
        assertEquals(List.of("acquire","isolation 2","release","close"),j.events);
        assertTrue(j.auto);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,j.isolation);
        /* Ripristina il livello d'isolamento anche se il driver lo ha applicato prima di fallire. */
        var k=new Jdbc();k.applyIsolationBeforeFailure=true;k.fail.add("isolation 2");
        assertEquals("isolation 2",assertThrows(SQLException.class,()->k.run(c->{k.events.add("work");return null;})).getMessage());
        assertEquals(List.of("acquire","isolation 2","isolation 4","release","close"),k.events);
        assertTrue(k.auto);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,k.isolation);
    }
    @Test void uncertainRollbackKeepsMutexAndAutocommitUntilClose() {
        var j=new Jdbc();j.fail.add("rollback");var failure=new IllegalStateException("work");
        assertSame(failure,assertThrows(IllegalStateException.class,()->j.run(c->{throw failure;})));
        assertEquals("rollback",failure.getSuppressed()[0].getMessage());
        assertEquals(List.of("acquire","isolation 2","auto false","rollback","close"),j.events);assertFalse(j.auto);
    }
    @Test void cleanupFailureAfterCommitIsStillThrownWithoutRollback() {
        var j=new Jdbc();j.release=0;
        assertEquals("Rilascio mutex non riuscito.",assertThrows(SQLException.class,()->j.run(c->"done")).getMessage());
        assertEquals(List.of("acquire","isolation 2","auto false","commit","isolation 4","auto true","release","close"),j.events);
        var k=new Jdbc();k.fail.add("close");
        assertEquals("close",assertThrows(SQLException.class,()->k.run(c->"done")).getMessage());assertTrue(k.events.contains("commit"));
    }
}
