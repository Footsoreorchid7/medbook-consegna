package it.medbook.repository;
import it.medbook.config.*;
import it.medbook.model.RuoloGestionale;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AccountInternoRepositoryTest {
    static class Jdbc {
        final List<String> events=new ArrayList<>();final Map<String,Map<Integer,Object>> bindings=new HashMap<>();
        boolean auto=true,closed,medico,emailAbsent,nullKey,multiKey,releaseFalse,applyAutoBeforeFailure,emptyKey,nullRelease;
        int isolation=Connection.TRANSACTION_REPEATABLE_READ,lock=1;long key=9,count=2;
        String targetRole="OPERATORE_DESK",actorRole="AMMINISTRATORE";Integer actorId=7;
        /* Stato grezzo e generazione del destinatario letti FOR UPDATE; conteggio delle righe aggiornate. */
        String targetState="ATTIVO";Object targetGeneration=java.math.BigInteger.ZERO;int updated=1;
        final Set<Integer> absent=new HashSet<>(),medici=new HashSet<>();
        final Set<String> fail=new HashSet<>();SQLException writeError;
        Object zero(Class<?> t){return t==boolean.class?false:t==int.class?0:t==long.class?0L:null;}
        void event(String e)throws SQLException{events.add(e);if(fail.contains(e))throw new SQLException(e);}
        ResultSet rows(String sql,Map<Integer,Object> args) {
            int[] n={0};return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
                case "next" -> {
                    if(sql.equals("keys")&&emptyKey)yield false;
                    if(sql.startsWith("SELECT id_utente FROM medico"))yield (medico||medici.contains(args.get(1)))&&n[0]++==0;
                    if(sql.startsWith("SELECT 1 FROM utente WHERE email"))yield !emailAbsent&&n[0]++==0;
                    if(sql.contains("FOR UPDATE")&&absent.contains(args.get(1)))yield false;
                    yield n[0]++<(sql.equals("keys")&&multiKey?2:1);
                }
                case "getInt" -> sql.contains("GET_LOCK")?lock:sql.contains("RELEASE_LOCK")?(releaseFalse?0:1):9;
                case "getLong" -> sql.equals("keys")?key:count;
                case "wasNull" -> sql.contains("GET_LOCK")?lock==-1:sql.contains("RELEASE_LOCK")?nullRelease:sql.equals("keys")&&nullKey;
                case "getString" -> {
                    String field=(String)a[0];
                    yield switch(field){case "ruolo_gestionale" -> sql.contains("FOR UPDATE")?Objects.equals(args.get(1),actorId)?actorRole:targetRole:"OPERATORE_DESK";case "email" -> "e@example.invalid";case "nome" -> "N";case "cognome" -> "C";
                        case "stato_accesso" -> sql.contains("FOR UPDATE")&&!Objects.equals(args.get(1),actorId)?targetState:"ATTIVO";default -> null;};
                }
                case "getBoolean" -> false;
                case "getObject" -> "generazione_autenticazione".equals(a[0])?(Objects.equals(args.get(1),actorId)?java.math.BigInteger.ZERO:targetGeneration):null;
                case "close" -> {event("rows.close");yield null;}
                default -> zero(m.getReturnType());
            });
        }
        PreparedStatement statement(String sql) {
            var args=new HashMap<Integer,Object>();bindings.put(sql,args);
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                if(m.getName().startsWith("set")){args.put((Integer)a[0],a[1]);return null;}
                return switch(m.getName()) {
                    case "executeQuery" -> {event(sql.contains("GET_LOCK")?"acquire":sql.contains("RELEASE_LOCK")?"release":sql+" "+args.get(1));yield rows(sql,args);}
                    case "executeUpdate" -> {event("write");if(writeError!=null)throw writeError;yield updated;}
                    case "getGeneratedKeys" -> rows("keys",args);
                    case "close" -> {event("statement.close");yield null;}
                    default -> zero(m.getReturnType());
                };
            });
        }
        Connection connection() {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
                case "getAutoCommit" -> auto;
                case "getTransactionIsolation" -> isolation;
                case "getCatalog" -> "medbook_test_unit";
                case "setTransactionIsolation" -> {event("isolation "+a[0]);isolation=(int)a[0];yield null;}
                case "setAutoCommit" -> {if(applyAutoBeforeFailure)auto=(boolean)a[0];event("auto "+a[0]);auto=(boolean)a[0];yield null;}
                case "prepareStatement" -> statement((String)a[0]);
                case "commit" -> {event("commit");yield null;}
                case "rollback" -> {event("rollback");yield null;}
                case "close" -> {closed=true;event("close");yield null;}
                default -> zero(m.getReturnType());
            });
        }
        UtenteRepository repo(){return new UtenteRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return connection();}});}
        Object create()throws SQLException{return repo().createInterno(7,"N","C","e@example.invalid","hash",RuoloGestionale.OPERATORE_DESK);}
        Object update()throws SQLException{return repo().updateRuolo(7,9,RuoloGestionale.AMMINISTRATORE);}
    }
    @Test void transactionOrderPropertiesParametersAndRelease()throws Exception {
        var j=new Jdbc();j.create();assertTrue(j.closed);assertTrue(j.auto);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,j.isolation);
        assertTrue(j.events.indexOf("acquire")<j.events.indexOf("auto false"));assertTrue(j.events.indexOf("commit")<j.events.indexOf("release"));
        assertTrue(j.events.indexOf("isolation 2")<j.events.indexOf("auto false"));
        var insert=j.bindings.entrySet().stream().filter(e->e.getKey().startsWith("INSERT")).findFirst().orElseThrow();assertEquals(7,insert.getValue().size());assertEquals("e@example.invalid",insert.getValue().get(3));
        assertEquals(7,insert.getValue().get(6));assertInstanceOf(java.time.LocalDateTime.class,insert.getValue().get(7));
        assertEquals(1,Collections.frequency(j.events,"acquire"));assertEquals(1,Collections.frequency(j.events,"release"));
        assertEquals(UtenteRepository.accountLockName("medbook_test_unit"),j.bindings.get("SELECT GET_LOCK(?, 5)").get(1));
    }
    @Test void lockFailureHasNoTransactionOrWritesAndCloses() {
        for(int result:new int[]{0,-1,3}) {var j=new Jdbc();j.lock=result;assertThrows(SQLException.class,j::create);assertFalse(j.events.contains("write"));assertFalse(j.events.contains("auto false"));assertFalse(j.events.contains("release"));assertTrue(j.closed);}
        var j=new Jdbc();j.fail.add("acquire");assertThrows(SQLException.class,j::create);assertTrue(j.closed);assertFalse(j.events.contains("write"));
    }
    @Test void invalidGeneratedKeysRollbackThenRelease() {
        for(long key:new long[]{0,-1,(long)Integer.MAX_VALUE+1}){var j=new Jdbc();j.key=key;assertThrows(SQLException.class,j::create);assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("release"));assertFalse(j.events.contains("commit"));}
        var empty=new Jdbc();empty.emptyKey=true;assertThrows(SQLException.class,empty::create);assertTrue(empty.events.contains("rollback"));
        for(boolean isNull:new boolean[]{true,false}){var j=new Jdbc();j.nullKey=isNull;j.multiKey=!isNull;assertThrows(SQLException.class,j::create);assertTrue(j.events.contains("rollback"));assertTrue(j.closed);}
    }
    @Test void originalCauseSurvivesRollbackAndCleanupFailures() {
        var j=new Jdbc();j.fail.addAll(Set.of("write","rollback"));var e=assertThrows(SQLException.class,j::create);
        assertEquals("write",e.getMessage());assertEquals("rollback",e.getSuppressed()[0].getMessage());assertFalse(j.events.contains("release"));assertFalse(j.events.contains("auto true"));assertTrue(j.closed);
        j=new Jdbc();j.fail.addAll(Set.of("write","isolation 4","auto true","release","close"));e=assertThrows(SQLException.class,j::create);
        assertEquals("write",e.getMessage());assertTrue(j.events.contains("release"));assertTrue(j.closed);
        assertEquals("isolation 4",e.getSuppressed()[0].getMessage());assertEquals(2,e.getSuppressed()[0].getSuppressed().length);assertEquals("close",e.getSuppressed()[1].getMessage());
    }
    @Test void postCommitRestoreOrReleaseFailureDoesNotRollback() {
        for(String failure:List.of("isolation 4","auto true","release","close")) {
            var j=new Jdbc();j.fail.add(failure);assertEquals(failure,assertThrows(SQLException.class,j::create).getMessage());
            assertTrue(j.events.contains("commit"));assertFalse(j.events.contains("rollback"));assertTrue(j.events.contains("release"));assertTrue(j.closed);
        }
        var nullResult=new Jdbc();nullResult.nullRelease=true;assertThrows(SQLException.class,nullResult::create);assertTrue(nullResult.closed);assertFalse(nullResult.events.contains("rollback"));
        var j=new Jdbc();j.releaseFalse=true;assertThrows(SQLException.class,j::create);assertTrue(j.closed);assertFalse(j.events.contains("rollback"));
    }
    @Test void commitErrorRollsBackAndReleasesOnlyAfterConclusion() {
        var j=new Jdbc();j.fail.add("commit");assertEquals("commit",assertThrows(SQLException.class,j::create).getMessage());assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("release"));
        j=new Jdbc();j.fail.addAll(Set.of("commit","rollback"));assertThrows(SQLException.class,j::create);assertFalse(j.events.contains("release"));assertFalse(j.events.contains("auto true"));assertTrue(j.closed);
    }
    @Test void sortedUserLocksAndOnlyRoleUpdate()throws Exception {
        var j=new Jdbc();j.update();var events=j.events.stream().filter(s->s.contains("FOR UPDATE")).toList();assertTrue(events.get(0).endsWith(" 7"));assertTrue(events.get(1).endsWith(" 9"));
        String update="UPDATE utente SET ruolo_gestionale=?, modificato_da = ?, modificato_il = ? WHERE id_utente=?";
        assertTrue(j.bindings.containsKey(update));var args=j.bindings.get(update);
        assertEquals(4,args.size());assertEquals("AMMINISTRATORE",args.get(1));assertEquals(7,args.get(2));assertInstanceOf(java.time.LocalDateTime.class,args.get(3));assertEquals(9,args.get(4));
    }
    @Test void roleIntegrityActorAndLastAdminAreCheckedBeforeWrite() {
        var j=new Jdbc();j.actorRole="OPERATORE_DESK";assertThrows(it.medbook.exception.OperazioneNonAutorizzataException.class,j::create);assertFalse(j.events.contains("write"));
        j=new Jdbc();j.targetRole=null;assertThrows(UtenteRepository.ConflittoAccountException.class,j::update);assertFalse(j.events.contains("write"));
        for(String role:List.of("operatore_desk","amministratore","UNKNOWN")){var invalid=new Jdbc();invalid.targetRole=role;assertThrows(UtenteRepository.ConflittoAccountException.class,invalid::update);assertFalse(invalid.events.contains("write"));}
        j=new Jdbc();j.medico=true;assertThrows(UtenteRepository.ConflittoAccountException.class,j::update);assertFalse(j.events.contains("write"));
        /* Il conteggio esclude il destinatario: nessun altro amministratore attivo costituisce conflitto. */
        j=new Jdbc();j.count=0;j.targetRole="AMMINISTRATORE";var q=j;assertThrows(UtenteRepository.ConflittoAccountException.class,()->q.repo().updateRuolo(7,9,RuoloGestionale.OPERATORE_DESK));assertFalse(j.events.contains("write"));
        j=new Jdbc();var self=j;assertThrows(UtenteRepository.ConflittoAccountException.class,()->self.repo().updateRuolo(7,7,RuoloGestionale.OPERATORE_DESK));assertFalse(j.events.contains("write"));
    }
    static final String EMAIL_CHECK="SELECT 1 FROM utente WHERE email=? e@example.invalid";
    @Test void duplicateEmailIsRecognizedWithoutParsingLocalizedMessages() {
        for(String message:new String[]{"Duplicate entry 'e@example.invalid' for key 'utente.uq_utente_email'",
                "Valore duplicato 'e@example.invalid' per la chiave 'utente.uq_utente_email'","Doppelter Eintrag 'e@example.invalid' für Schlüssel 'uq_utente_email'","",null}) {
            var j=new Jdbc();j.writeError=new SQLException(message,"23000",1062);var e=assertThrows(UtenteRepository.ConflittoAccountException.class,j::create);
            assertSame(j.writeError,e.getCause());assertEquals("Email già presente.",e.getMessage());
            assertTrue(j.events.indexOf("write")<j.events.indexOf(EMAIL_CHECK));assertTrue(j.events.indexOf(EMAIL_CHECK)<j.events.indexOf("rollback"));
            assertTrue(j.events.indexOf("rollback")<j.events.indexOf("release"));assertFalse(j.events.contains("commit"));assertTrue(j.closed);
        }
    }
    @Test void otherSqlErrorsAreNeverConflicts() {
        /*
         * Duplicato PRIMARY da esaurimento AUTO_INCREMENT, con email non presente: il testo
         * localizzato non determina la classificazione.
         */
        for(String message:new String[]{"Duplicate entry '4294967295' for key 'utente.PRIMARY'","Valore duplicato '4294967295' per la chiave 'utente.PRIMARY'"}) {
            var j=new Jdbc();j.emailAbsent=true;j.writeError=new SQLException(message,"23000",1062);assertSame(j.writeError,assertThrows(SQLException.class,j::create));
            assertTrue(j.events.contains(EMAIL_CHECK));assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));
        }
        /* Codice o stato diverso: nessuna riclassificazione né ricerca email. */
        for(SQLException error:List.of(new SQLException("uq_utente_email","23000",1452),new SQLException("uq_utente_email","HY000",1062),
                new SQLException("Data too long for column 'nome'","22001",1406),new SQLException("Deadlock found","40001",1213))) {
            var j=new Jdbc();j.writeError=error;assertSame(error,assertThrows(SQLException.class,j::create));assertFalse(j.events.contains(EMAIL_CHECK));
        }
        /* La verifica fallita conserva l'errore originale e aggiunge il guasto come eccezione soppressa. */
        var j=new Jdbc();j.writeError=new SQLException("Duplicate","23000",1062);j.fail.add(EMAIL_CHECK);
        var e=assertThrows(SQLException.class,j::create);assertSame(j.writeError,e);assertEquals(EMAIL_CHECK,e.getSuppressed()[0].getMessage());
        /* L'UPDATE del ruolo non riclassifica un errore 1062. */
        var u=new Jdbc();u.writeError=new SQLException("Duplicate","23000",1062);assertSame(u.writeError,assertThrows(SQLException.class,u::update));assertFalse(u.events.contains(EMAIL_CHECK));
    }
    @Test void demotedActorGets403WhetherOrNotTargetExistsWithAscendingLocks() {
        /* Qualunque sia l'ordine degli ID, i lock crescono e la decisione resta quella dell'attore. */
        for(int[] pair:new int[][]{{7,9},{9,7}}) {
            int actor=pair[0],target=pair[1];
            for(boolean targetExists:new boolean[]{true,false}) {
                for(String actorRole:new String[]{"OPERATORE_DESK",null}) {
                    var j=new Jdbc();j.actorId=actor;j.actorRole=actorRole;if(!targetExists)j.absent.add(target);var q=j;
                    assertThrows(it.medbook.exception.OperazioneNonAutorizzataException.class,()->q.repo().updateRuolo(actor,target,RuoloGestionale.AMMINISTRATORE));
                    assertEquals(List.of("SELECT ruolo_gestionale, stato_accesso, generazione_autenticazione FROM utente WHERE id_utente=? FOR UPDATE 7","SELECT ruolo_gestionale, stato_accesso, generazione_autenticazione FROM utente WHERE id_utente=? FOR UPDATE 9"),
                            j.events.stream().filter(x->x.contains("FOR UPDATE")).toList());
                    assertFalse(j.events.stream().anyMatch(x->x.startsWith("SELECT id_utente FROM medico")||x.contains("COUNT")));
                    assertFalse(j.events.contains("write"));assertTrue(j.events.contains("rollback"));assertTrue(j.events.contains("release"));
                }
            }
        }
        // Anche un attore eliminato ottiene 403, non 404.
        var j=new Jdbc();j.absent.add(7);assertThrows(it.medbook.exception.OperazioneNonAutorizzataException.class,j::update);assertFalse(j.events.contains("write"));
        j=new Jdbc();j.absent.add(7);assertThrows(it.medbook.exception.OperazioneNonAutorizzataException.class,j::create);assertFalse(j.events.contains("write"));
        /* Solo un amministratore apprende che il destinatario manca. */
        j=new Jdbc();j.absent.add(9);assertThrows(it.medbook.exception.RisorsaNonTrovataException.class,j::update);
        assertFalse(j.events.stream().anyMatch(x->x.startsWith("SELECT id_utente FROM medico")));assertFalse(j.events.contains("write"));
    }
    @Test void administratorDoctorMayActButDoctorTargetStaysRejected()throws Exception {
        var j=new Jdbc();j.medici.add(7);j.create();assertTrue(j.events.contains("write"));assertTrue(j.events.contains("commit"));
        assertFalse(j.events.stream().anyMatch(x->x.startsWith("SELECT id_utente FROM medico")));
        j=new Jdbc();j.medici.add(7);j.update();assertTrue(j.events.contains("commit"));
        assertTrue(j.events.contains("SELECT id_utente FROM medico WHERE id_utente=? 9"));assertFalse(j.events.contains("SELECT id_utente FROM medico WHERE id_utente=? 7"));
        for(boolean actorDoctor:new boolean[]{true,false}) {
            j=new Jdbc();j.medici.add(9);if(actorDoctor)j.medici.add(7);
            assertEquals("Profilo medico incompatibile con ruolo gestionale.",assertThrows(UtenteRepository.ConflittoAccountException.class,j::update).getMessage());
            assertFalse(j.events.contains("write"));assertFalse(j.events.contains("commit"));
        }
    }
    @Test void namespaceIsDatabaseSpecificAndBounded()throws Exception {
        assertNotEquals(UtenteRepository.accountLockName("test_a"),UtenteRepository.accountLockName("test_b"));assertTrue(UtenteRepository.accountLockName("a".repeat(100)).length()<=64);assertThrows(SQLException.class,()->UtenteRepository.accountLockName(null));
    }
    @Test void uncertainTransactionStartupDoesNotReleaseMutexOrEnableAutocommit() {
        var j=new Jdbc();j.applyAutoBeforeFailure=true;j.fail.addAll(Set.of("auto false","rollback"));
        var failure=assertThrows(SQLException.class,j::create);assertEquals("auto false",failure.getMessage());
        assertEquals("rollback",failure.getSuppressed()[0].getMessage());assertTrue(j.closed);assertFalse(j.auto);
        assertFalse(j.events.contains("release"));assertFalse(j.events.contains("auto true"));
        j=new Jdbc();j.applyAutoBeforeFailure=true;j.fail.add("auto false");assertThrows(SQLException.class,j::create);
        assertTrue(j.events.indexOf("rollback")<j.events.indexOf("release"));assertTrue(j.auto);assertTrue(j.closed);
    }


    /* Casi non raggiungibili su database valido per CHECK e controlli dell'attore attivo. */
    Object disable(Jdbc j)throws SQLException{return j.repo().cambiaStatoAccesso(7,9,"DISABILITATO");}
    @Test void accessStateWritesExactlyOneRowWithIncrementedGenerationAndTracking()throws Exception {
        var j=new Jdbc();var result=(UtenteRepository.CambioStatoAccesso)disable(j);assertEquals(1L,result.nuovaGenerazione());
        String update=j.bindings.keySet().stream().filter(k->k.startsWith("UPDATE utente SET stato_accesso")).findFirst().orElseThrow();
        assertTrue(update.contains("CAST(stato_accesso AS BINARY) = CAST(? AS BINARY) AND generazione_autenticazione = ?"),update);
        var args=j.bindings.get(update);
        assertEquals("DISABILITATO",args.get(1));assertEquals(1L,args.get(2));assertEquals(7,args.get(3));assertInstanceOf(java.time.LocalDateTime.class,args.get(4));
        assertEquals(9,args.get(5));assertEquals("ATTIVO",args.get(6));assertEquals(0L,args.get(7));assertTrue(j.events.contains("commit"));
        var locks=j.events.stream().filter(e->e.contains("FOR UPDATE")).toList();assertTrue(locks.get(0).endsWith(" 7"));assertTrue(locks.get(1).endsWith(" 9"));
    }
    @Test void unexpectedUpdateCountRollsBackAs500()throws Exception {
        for(int n:new int[]{0,2}){var j=new Jdbc();j.updated=n;var e=assertThrows(SQLException.class,()->disable(j));
            assertEquals("Aggiornamento stato non completato.",e.getMessage());assertTrue(j.events.contains("rollback"));assertFalse(j.events.contains("commit"));}
    }
    @Test void lastUsableAdministratorAnomaliesAndGenerationLimitWriteNothing()throws Exception {
        var j=new Jdbc();j.targetRole="AMMINISTRATORE";j.count=0;
        assertEquals("Ultimo amministratore attivo protetto.",assertThrows(UtenteRepository.ConflittoAccountException.class,()->disable(j)).getMessage());
        assertFalse(j.events.contains("write"));assertTrue(j.events.contains("rollback"));
        var other=new Jdbc();other.targetRole="AMMINISTRATORE";other.count=1;disable(other);assertTrue(other.events.contains("write"));
        for(String state:new String[]{"attivo","ATTIVO ","SOSPESO",null}){var a=new Jdbc();a.targetState=state;
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->disable(a),String.valueOf(state));assertFalse(a.events.contains("write"));}
        for(Object generation:new Object[]{null,java.math.BigInteger.ONE.shiftLeft(63),java.math.BigInteger.valueOf(-1)}){var g=new Jdbc();g.targetGeneration=generation;
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->disable(g));assertFalse(g.events.contains("write"));}
        /* La generazione anomala è rifiutata prima dell'idempotenza in entrambi gli stati. */
        for(String state:new String[]{"ATTIVO","DISABILITATO"})for(Object generation:new Object[]{null,java.math.BigInteger.ONE.shiftLeft(63)}) {
            var noop=new Jdbc();noop.targetState=state;noop.targetGeneration=generation;
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->noop.repo().cambiaStatoAccesso(7,9,state),state+" "+generation);
            assertFalse(noop.events.contains("write"));assertTrue(noop.events.contains("rollback"));
        }
        var noopMax=new Jdbc();noopMax.targetGeneration=java.math.BigInteger.valueOf(Long.MAX_VALUE);
        assertNull(noopMax.repo().cambiaStatoAccesso(7,9,"ATTIVO").nuovaGenerazione());assertFalse(noopMax.events.contains("write"));
        var max=new Jdbc();max.targetGeneration=java.math.BigInteger.valueOf(Long.MAX_VALUE);
        assertThrows(it.medbook.exception.GenerazioneEsauritaException.class,()->disable(max));assertFalse(max.events.contains("write"));
        var desk=new Jdbc();desk.actorRole="OPERATORE_DESK";assertThrows(it.medbook.exception.OperazioneNonAutorizzataException.class,()->disable(desk));
        assertFalse(desk.events.contains("write"));
    }
}
