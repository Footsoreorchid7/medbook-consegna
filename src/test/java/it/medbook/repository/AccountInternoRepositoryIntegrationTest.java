package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.AccountInternoRequest;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.StatoPrenotazione;
import it.medbook.security.PasswordHasher;
import it.medbook.service.UtenteService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountInternoRepositoryIntegrationTest {
    static class Fixture extends PrenotazioneIndisponibilitaIntegrationTest.Fixture {
        Fixture() throws Exception { super(); }
        int admin() throws SQLException { int id=user();execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",id);return id; }
        UtenteRepository accounts(){return new UtenteRepository(factory);}
        List<Object> userSnapshot(int id)throws SQLException {
            try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM utente WHERE id_utente=?")) {
                s.setInt(1,id);try(ResultSet r=s.executeQuery()){assertTrue(r.next());var a=new ArrayList<Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)a.add(r.getObject(i));return a;}
            }
        }
    }
    @FunctionalInterface interface Hook {void run()throws Exception;}
    static ConnectionFactory observe(ConnectionFactory factory,Hook before,Hook after) {
        return new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) { @Override public Connection getConnection() throws SQLException { Connection c=factory.getConnection();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try {Object value=m.invoke(c,a);
                if(m.getName().equals("prepareStatement")&&((String)a[0]).startsWith("SELECT GET_LOCK")) {
                    PreparedStatement s=(PreparedStatement)value;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(q,n,b)->{
                        try{if(n.getName().equals("executeQuery")){before.run();Object rows=n.invoke(s,b);after.run();return rows;}return n.invoke(s,b);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                }return value;
            }catch(InvocationTargetException e){throw e.getCause();}
        });}};
    }
    static void await(CountDownLatch l)throws Exception{assertTrue(l.await(10,TimeUnit.SECONDS));}
    static int scalar(Connection c,String sql,String name)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,name);try(ResultSet r=s.executeQuery()){assertTrue(r.next());int v=r.getInt(1);assertFalse(r.wasNull());return v;}}
    }
    @Test void createsBothRolesLoginCredentialsAndNoProfiles()throws Exception {
        try(Fixture f=new Fixture()) {
            int actor=f.admin();var hasher=new PasswordHasher();var service=new UtenteService(f.accounts(),hasher);
            for(var role:RuoloGestionale.values()) {
                String email=UUID.randomUUID()+"@example.invalid";
                var result=service.crea(actor,new AccountInternoRequest(" Interno "," Test ",email," Test password ",role));f.users.add(result.idUtente());
                assertEquals(role,result.ruoloGestionale());assertFalse(result.paziente());assertFalse(result.medico());
                var credentials=f.accounts().findByEmail(email).orElseThrow();assertTrue(hasher.verify(" Test password ".toCharArray(),credentials.passwordHash()));
                assertEquals(result.idUtente(),credentials.identita().idUtente());assertEquals(role,credentials.identita().ruoloGestionale());
                var auth=new it.medbook.service.AutenticazioneService(f.accounts(),hasher,new it.medbook.security.SessionStore(java.time.Clock.systemUTC(),10));
                var session=auth.login(email," Test password ".toCharArray()).orElseThrow();assertEquals(role,auth.identity(session).orElseThrow().ruoloGestionale());
                assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().createInterno(actor,"Altro","Test",email.toUpperCase(Locale.ROOT),credentials.passwordHash(),role));
            }
        }
    }
    @Test void listFiltersOrdersAndDoesNotExposePassword()throws Exception {
        try(Fixture f=new Fixture()){int admin=f.admin();var list=f.accounts().findGestionali();assertTrue(list.stream().anyMatch(r->r.idUtente()==admin));assertTrue(list.stream().anyMatch(r->r.idUtente()==f.operator));
            assertFalse(list.stream().anyMatch(r->r.idUtente()==f.patient||r.idUtente()==f.owner));
            assertEquals(list.stream().map(r->r.idUtente()).sorted().toList(),list.stream().map(r->r.idUtente()).toList());
            String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);assertFalse(json.contains("password"));}
    }
    @Test void onlyRoleChangesAndHistoricalBookingIsPreserved()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();int booking=f.booking(StatoPrenotazione.CONFERMATA,LocalDateTime.of(2030,1,2,10,0));var bookingBefore=f.snapshot(booking);var before=f.userSnapshot(f.operator);
            f.accounts().updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE);var after=f.userSnapshot(f.operator);
            try(Connection c=f.factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM utente WHERE id_utente=?")){s.setInt(1,f.operator);try(ResultSet r=s.executeQuery()){assertTrue(r.next());for(int i=1;i<=r.getMetaData().getColumnCount();i++)if(!Set.of("ruolo_gestionale","modificato_da","modificato_il").contains(r.getMetaData().getColumnLabel(i)))assertEquals(before.get(i-1),after.get(i-1));
                /* Il cambio ruolo effettivo aggiorna i metadati sulla stessa riga con l'amministratore attore. */
                assertEquals(actor,r.getInt("modificato_da"));assertNotNull(r.getObject("modificato_il"));}}
            assertEquals(bookingBefore,f.snapshot(booking));assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE).ruoloGestionale());
            assertEquals(RuoloGestionale.OPERATORE_DESK,f.accounts().updateRuolo(actor,f.operator,RuoloGestionale.OPERATORE_DESK).ruoloGestionale());}
    }
    @Test void rejectsSelfPatientDoctorAndMissingWithoutChangingRows()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();var before=f.userSnapshot(actor);
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(actor,actor,RuoloGestionale.OPERATORE_DESK));
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(actor,f.patient,RuoloGestionale.AMMINISTRATORE));
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(actor,f.owner,RuoloGestionale.AMMINISTRATORE));
            assertThrows(it.medbook.exception.RisorsaNonTrovataException.class,()->f.accounts().updateRuolo(actor,Integer.MAX_VALUE,RuoloGestionale.AMMINISTRATORE));assertEquals(before,f.userSnapshot(actor));}
    }
    @Test void crossedDemotionsKeepAdministratorAndReloadWaitingActor()throws Exception {
        try(Fixture f=new Fixture()){int a=f.admin(),b=f.admin();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
            var first=new UtenteRepository(observe(f.factory,()->{},()->{held.countDown();await(release);}));var second=new UtenteRepository(observe(f.factory,attempted::countDown,()->{}));
            try(var pool=Executors.newFixedThreadPool(2)){var winner=pool.submit(()->first.updateRuolo(a,b,RuoloGestionale.OPERATORE_DESK));await(held);var loser=pool.submit(()->second.updateRuolo(b,a,RuoloGestionale.OPERATORE_DESK));await(attempted);assertThrows(TimeoutException.class,()->loser.get(100,TimeUnit.MILLISECONDS));release.countDown();assertEquals(RuoloGestionale.OPERATORE_DESK,winner.get(10,TimeUnit.SECONDS).ruoloGestionale());assertInstanceOf(OperazioneNonAutorizzataException.class,assertThrows(ExecutionException.class,()->loser.get(10,TimeUnit.SECONDS)).getCause());}
            finally{release.countDown();}
            assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().findById(a).orElseThrow().ruoloGestionale());assertEquals(RuoloGestionale.OPERATORE_DESK,f.accounts().findById(b).orElseThrow().ruoloGestionale());}
    }
    @Test void waitingCreatorCannotInsertAfterDemotion()throws Exception {
        try(Fixture f=new Fixture()){int a=f.admin(),b=f.admin();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);String email=UUID.randomUUID()+"@example.invalid";
            var first=new UtenteRepository(observe(f.factory,()->{},()->{held.countDown();await(release);}));var second=new UtenteRepository(observe(f.factory,attempted::countDown,()->{}));
            try(var pool=Executors.newFixedThreadPool(2)){var winner=pool.submit(()->first.updateRuolo(a,b,RuoloGestionale.OPERATORE_DESK));await(held);var loser=pool.submit(()->second.createInterno(b,"No","Insert",email,"synthetic-test-hash",RuoloGestionale.OPERATORE_DESK));await(attempted);release.countDown();winner.get(10,TimeUnit.SECONDS);assertInstanceOf(OperazioneNonAutorizzataException.class,assertThrows(ExecutionException.class,()->loser.get(10,TimeUnit.SECONDS)).getCause());}finally{release.countDown();}
            assertTrue(f.accounts().findByEmail(email).isEmpty());}
    }
    @Test void namedMutexSurvivesCommitRollbackAndReleasesOnClose()throws Exception {
        try(Fixture f=new Fixture();Connection owner=f.factory.getConnection();Connection other=f.factory.getConnection()){String name=UtenteRepository.accountLockName(owner.getCatalog());assertEquals(1,scalar(owner,"SELECT GET_LOCK(?,0)",name));owner.setAutoCommit(false);owner.commit();assertEquals(0,scalar(other,"SELECT GET_LOCK(?,0)",name));owner.rollback();assertEquals(0,scalar(other,"SELECT GET_LOCK(?,0)",name));assertEquals(1,scalar(owner,"SELECT RELEASE_LOCK(?)",name));assertEquals(1,scalar(other,"SELECT GET_LOCK(?,0)",name));other.close();assertEquals(1,scalar(owner,"SELECT GET_LOCK(?,5)",name));assertEquals(1,scalar(owner,"SELECT RELEASE_LOCK(?)",name));}
    }
    @Test void namedLockTimeoutDoesNotWriteAndCanAcquireAfterOwnerCloses()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();String email=UUID.randomUUID()+"@example.invalid";try(Connection owner=f.factory.getConnection()){assertEquals(1,scalar(owner,"SELECT GET_LOCK(?,0)",UtenteRepository.accountLockName(owner.getCatalog())));assertThrows(UtenteRepository.LockAccountTimeoutException.class,()->f.accounts().createInterno(actor,"No","Timeout",email,"synthetic-test-hash",RuoloGestionale.OPERATORE_DESK));assertTrue(f.accounts().findByEmail(email).isEmpty());}
            var result=f.accounts().createInterno(actor,"Yes","Released",email,"synthetic-test-hash",RuoloGestionale.OPERATORE_DESK);f.users.add(result.idUtente());}
    }
    @Test void rollbackReleasesMutexAndLaterRequestWorks()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(actor,f.patient,RuoloGestionale.OPERATORE_DESK));assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE).ruoloGestionale());}
    }
    @Test void deskSharedUserLockSerializesRoleChangeWithoutChangingBooking()throws Exception {
        try(Fixture f=new Fixture();Connection desk=f.factory.getConnection()){int actor=f.admin();int booking=f.booking(StatoPrenotazione.CONFERMATA,LocalDateTime.of(2030,1,2,10,0));var before=f.snapshot(booking);desk.setAutoCommit(false);
            try(PreparedStatement s=desk.prepareStatement("SELECT id_utente FROM utente WHERE id_utente=? FOR SHARE")){s.setInt(1,f.operator);try(ResultSet r=s.executeQuery()){assertTrue(r.next());}}
            var attempted=new CountDownLatch(1);var repo=new UtenteRepository(observe(f.factory,attempted::countDown,()->{}));
            try(var pool=Executors.newSingleThreadExecutor()){var update=pool.submit(()->repo.updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE));await(attempted);assertThrows(TimeoutException.class,()->update.get(150,TimeUnit.MILLISECONDS));desk.commit();assertEquals(RuoloGestionale.AMMINISTRATORE,update.get(10,TimeUnit.SECONDS).ruoloGestionale());}finally{desk.rollback();}
            assertEquals(before,f.snapshot(booking));}
    }
    @Test void successiveDemotionsUseCurrentCountAndSessionIdentity()throws Exception {
        try(Fixture f=new Fixture()){int a=f.admin(),b=f.admin(),c=f.admin();
            var sessions=new it.medbook.security.SessionStore(java.time.Clock.systemUTC(),10);
            var auth=new it.medbook.service.AutenticazioneService(f.accounts(),new PasswordHasher(),sessions);var session=sessions.create(b, 0L);
            assertEquals(RuoloGestionale.AMMINISTRATORE,auth.identity(session).orElseThrow().ruoloGestionale());
            f.accounts().updateRuolo(a,b,RuoloGestionale.OPERATORE_DESK);f.accounts().updateRuolo(a,c,RuoloGestionale.OPERATORE_DESK);
            assertEquals(RuoloGestionale.OPERATORE_DESK,auth.identity(session).orElseThrow().ruoloGestionale());
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(a,a,RuoloGestionale.OPERATORE_DESK));
            assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().findById(a).orElseThrow().ruoloGestionale());
        }
    }
    @Test void actualJdbcPropertiesRestoredOnSuccessAndRollbackBeforeClose()throws Exception {
        try(Fixture f=new Fixture()){int a=f.admin();var closed=new java.util.concurrent.atomic.AtomicInteger();
            ConnectionFactory checked=new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()){
                @Override public Connection getConnection()throws SQLException {
                    Connection c=f.factory.getConnection();int original=c.getTransactionIsolation();boolean auto=c.getAutoCommit();
                    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
                        try{if(m.getName().equals("close")){assertEquals(original,c.getTransactionIsolation());assertEquals(auto,c.getAutoCommit());closed.incrementAndGet();}return m.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            var repo=new UtenteRepository(checked);repo.updateRuolo(a,f.operator,RuoloGestionale.AMMINISTRATORE);
            assertThrows(UtenteRepository.ConflittoAccountException.class,()->repo.updateRuolo(a,f.patient,RuoloGestionale.OPERATORE_DESK));assertEquals(2,closed.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void roleChangeVersusActualDeskConfirmation(boolean deskFirst)throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();int booking=f.booking(StatoPrenotazione.RICHIESTA,LocalDateTime.of(2030,2,1,10,0));
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
            var deskFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(deskFirst&&after&&sql.contains("FROM utente")&&sql.contains("FOR SHARE")){held.countDown();await(release);}
                if(!deskFirst&&!after&&sql.contains("FROM utente")&&sql.contains("FOR SHARE"))attempted.countDown();
            });
            var accountFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(deskFirst&&!after&&sql.startsWith("SELECT GET_LOCK"))attempted.countDown();
                if(!deskFirst&&after&&id==f.operator&&sql.contains("FROM utente")&&sql.contains("FOR UPDATE")){held.countDown();await(release);}
            });
            var accounts=new UtenteRepository(accountFactory);
            var desk=new PrenotazioneRepository(deskFactory,java.time.Clock.fixed(java.time.Instant.parse("2030-01-15T08:00:00Z"),java.time.ZoneOffset.UTC));
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<?> first=pool.submit(()->deskFirst?desk.confermaAtomically(booking,f.operator):accounts.updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE));await(held);
                Future<?> second=pool.submit(()->deskFirst?accounts.updateRuolo(actor,f.operator,RuoloGestionale.AMMINISTRATORE):desk.confermaAtomically(booking,f.operator));await(attempted);
                assertThrows(TimeoutException.class,()->second.get(100,TimeUnit.MILLISECONDS));release.countDown();first.get(10,TimeUnit.SECONDS);
                if(deskFirst){second.get(10,TimeUnit.SECONDS);try(Connection c=f.factory.getConnection();PreparedStatement st=c.prepareStatement("SELECT stato,gestita_da FROM prenotazione WHERE id_prenotazione=?")){st.setInt(1,booking);try(ResultSet r=st.executeQuery()){assertTrue(r.next());assertEquals("CONFERMATA",r.getString(1));assertEquals(f.operator,r.getInt(2));}}}
                else{assertInstanceOf(OperazioneNonAutorizzataException.class,assertThrows(ExecutionException.class,()->second.get(10,TimeUnit.SECONDS)).getCause());try(Connection c=f.factory.getConnection();PreparedStatement st=c.prepareStatement("SELECT stato FROM prenotazione WHERE id_prenotazione=?")){st.setInt(1,booking);try(ResultSet r=st.executeQuery()){assertTrue(r.next());assertEquals("RICHIESTA",r.getString(1));}}}
            }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
            assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().findById(f.operator).orElseThrow().ruoloGestionale());
        }
    }

    @Test void listShowsDisabledAccountsAndRejectsGenerationOutsideJavaDomain()throws Exception {
        // DISABILITATO canonico compare in elenco; una generazione oltre Long.MAX_VALUE è anomalia.
        try(Fixture f=new Fixture()){int admin=f.admin();
            f.execute("UPDATE utente SET stato_accesso='DISABILITATO', generazione_autenticazione=9223372036854775807 WHERE id_utente=?",f.operator);
            var list=f.accounts().findGestionali();assertTrue(list.stream().anyMatch(r->r.idUtente()==f.operator));assertTrue(list.stream().anyMatch(r->r.idUtente()==admin));
            f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE id_utente=?",f.operator);
            var e=assertThrows(it.medbook.exception.AnomaliaDatiAccountException.class,()->f.accounts().findGestionali());assertFalse(e.getMessage().contains("9223372036854775808"));
            f.execute("UPDATE utente SET generazione_autenticazione=0, ruolo_gestionale='operatore_desk' WHERE id_utente=?",f.operator);
            assertThrows(it.medbook.exception.AnomaliaDatiAccountException.class,()->f.accounts().findGestionali());
        }
    }
    @Test void nonCanonicalExistingRolesAreRejectedWithoutRepair()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();
            for(String role:List.of("operatore_desk","amministratore")){
                f.execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",role,f.operator);var before=f.userSnapshot(f.operator);
                assertThrows(UtenteRepository.ConflittoAccountException.class,()->f.accounts().updateRuolo(actor,f.operator,RuoloGestionale.OPERATORE_DESK));
                assertEquals(before,f.userSnapshot(f.operator));assertThrows(SQLException.class,()->f.accounts().findGestionali());
            }
        }
    }

    static final String PASSWORD="integration password";
    static int scalarInt(Connection c,String sql)throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getInt(1);}
    }
    static Object lockState(Connection c,String sql,String name)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,name);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getObject(1);}}
    }
    List<Object> medicoSnapshot(Fixture f,int id)throws SQLException {
        try(Connection c=f.factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM medico WHERE id_utente=?")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()){assertTrue(r.next());var a=new ArrayList<Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)a.add(r.getObject(i));return a;}
        }
    }
    @Test void preexistingAdministratorDoctorManagesOthersWithoutAutomaticCorrection()throws Exception {
        try(Fixture f=new Fixture()){
            /* Combinazioni preesistenti inserite come fixture: non correggerle automaticamente. */
            f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",f.owner);
            f.execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?",f.effective);
            var actorBefore=f.userSnapshot(f.owner);var actorDoctor=medicoSnapshot(f,f.owner);
            var targetBefore=f.userSnapshot(f.effective);var targetDoctor=medicoSnapshot(f,f.effective);
            var service=new UtenteService(f.accounts(),new PasswordHasher());
            var created=service.crea(f.owner,new AccountInternoRequest("Nuovo","Interno",UUID.randomUUID()+"@example.invalid",PASSWORD,RuoloGestionale.OPERATORE_DESK));
            f.users.add(created.idUtente());assertFalse(created.medico());assertEquals(RuoloGestionale.OPERATORE_DESK,created.ruoloGestionale());
            assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().updateRuolo(f.owner,f.operator,RuoloGestionale.AMMINISTRATORE).ruoloGestionale());
            for(var role:RuoloGestionale.values())
                assertEquals("Profilo medico incompatibile con ruolo gestionale.",assertThrows(UtenteRepository.ConflittoAccountException.class,
                        ()->f.accounts().updateRuolo(f.owner,f.effective,role)).getMessage());
            assertEquals(actorBefore,f.userSnapshot(f.owner));assertEquals(actorDoctor,medicoSnapshot(f,f.owner));
            assertEquals(targetBefore,f.userSnapshot(f.effective));assertEquals(targetDoctor,medicoSnapshot(f,f.effective));
            var listed=f.accounts().findGestionali().stream().filter(r->r.idUtente()==f.owner).findFirst().orElseThrow();
            assertTrue(listed.medico());assertEquals(RuoloGestionale.AMMINISTRATORE,listed.ruoloGestionale());
        }
    }
    @Test void lockTimeoutOverHttpIs503WithoutWritesOrResidualLock()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();String email=UUID.randomUUID()+"@example.invalid";var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var controller=new it.medbook.controller.UtenteController(new UtenteService(f.accounts(),new PasswordHasher()),mapper);
            String body=mapper.writeValueAsString(Map.of("nome","No","cognome","Timeout","email",email,"password",PASSWORD,"ruoloGestionale","OPERATORE_DESK"));
            var roleBefore=f.userSnapshot(f.operator);String name;
            try(Connection owner=f.factory.getConnection()){name=UtenteRepository.accountLockName(owner.getCatalog());
                assertEquals(1,scalar(owner,"SELECT GET_LOCK(?,0)",name));int ownerId=scalarInt(owner,"SELECT CONNECTION_ID()");
                for(String[] request:new String[][]{{"POST","/api/utenti",body},{"PUT","/api/utenti/"+f.operator+"/ruolo","{\"ruoloGestionale\":\"AMMINISTRATORE\"}"}}) {
                    var exchange=new it.medbook.support.AuthenticatedTestExchange(request[0],request[1],request[2],actor);long start=System.nanoTime();controller.handle(exchange);
                    assertEquals(503,exchange.exchange.status);assertEquals("5",exchange.exchange.responseHeaders.getFirst("Retry-After"));
                    assertTrue(System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(4),"timeout di acquisizione di 5 secondi");
                    assertEquals("{\"error\":\"Servizio temporaneamente non disponibile.\"}",exchange.exchange.body());assertTrue(exchange.exchange.closed);
                    assertEquals(ownerId,((Number)lockState(owner,"SELECT IS_USED_LOCK(?)",name)).intValue());
                }
                assertTrue(f.accounts().findByEmail(email).isEmpty());assertEquals(roleBefore,f.userSnapshot(f.operator));
                assertEquals(1,scalar(owner,"SELECT RELEASE_LOCK(?)",name));
            }
            try(Connection probe=f.factory.getConnection()){assertEquals(1,scalar(probe,"SELECT IS_FREE_LOCK(?)",name));}
            var exchange=new it.medbook.support.AuthenticatedTestExchange("POST","/api/utenti",body,actor);controller.handle(exchange);assertEquals(201,exchange.exchange.status);
            f.users.add(f.accounts().findByEmail(email).orElseThrow().identita().idUtente());
            try(Connection probe=f.factory.getConnection()){assertEquals(1,scalar(probe,"SELECT IS_FREE_LOCK(?)",name));}
        }
    }
    @Test void duplicateEmailWithItalianServerMessagesIs409AndOtherErrorsAreNot()throws Exception {
        try(Fixture f=new Fixture()){int actor=f.admin();String existing=f.accounts().findGestionali().stream().filter(r->r.idUtente()==f.operator).findFirst().orElseThrow().email();
            ConnectionFactory italian=new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection()throws SQLException{
                Connection c=f.factory.getConnection();try(Statement s=c.createStatement()){s.execute("SET SESSION lc_messages='it_IT'");}return c;}};
            try(Connection c=italian.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO utente (nome,cognome,email,password_hash) VALUES ('X','Y',?,'h')")) {
                s.setString(1,existing);var raw=assertThrows(SQLException.class,s::executeUpdate);
                assertEquals(1062,raw.getErrorCode());assertEquals("23000",raw.getSQLState());
                assertFalse(raw.getMessage().contains("Duplicate entry"),"il server deve produrre diagnostica non inglese: "+raw.getMessage());
            }
            var accounts=new UtenteRepository(italian);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var conflict=assertThrows(UtenteRepository.ConflittoAccountException.class,()->accounts.createInterno(actor,"Dup","Email",existing,"synthetic-test-hash",RuoloGestionale.OPERATORE_DESK));
            assertEquals(1062,((SQLException)conflict.getCause()).getErrorCode());
            var controller=new it.medbook.controller.UtenteController(new UtenteService(accounts,new PasswordHasher()),mapper);
            String body=mapper.writeValueAsString(Map.of("nome","Dup","cognome","Email","email",existing.toUpperCase(Locale.ROOT),"password",PASSWORD,"ruoloGestionale","AMMINISTRATORE"));
            var exchange=new it.medbook.support.AuthenticatedTestExchange("POST","/api/utenti",body,actor);controller.handle(exchange);
            assertEquals(409,exchange.exchange.status);assertEquals("{\"error\":\"Email già presente.\"}",exchange.exchange.body());
            // Another constraint failure in Italian (strict mode, 101 chars) stays a SQLException.
            String fresh=UUID.randomUUID()+"@example.invalid";
            var other=assertThrows(SQLException.class,()->accounts.createInterno(actor,"n".repeat(101),"Lungo",fresh,"synthetic-test-hash",RuoloGestionale.OPERATORE_DESK));
            assertFalse(other instanceof UtenteRepository.LockAccountTimeoutException);assertNotEquals(1062,other.getErrorCode());
            assertTrue(f.accounts().findByEmail(fresh).isEmpty());
            try(Connection probe=f.factory.getConnection()){assertEquals(1,scalar(probe,"SELECT IS_FREE_LOCK(?)",UtenteRepository.accountLockName(probe.getCatalog())));}
        }
    }
    @Test void concurrentlyDemotedActorGetsSameAnswerForExistingAndMissingTarget()throws Exception {
        for(boolean targetExists:new boolean[]{true,false}) {
            try(Fixture f=new Fixture()){
                /* Destinatario con ID inferiore all'attore, eliminato prima della creazione dell'attore. */
                int ghost=f.user();f.execute("DELETE FROM utente WHERE id_utente=?",ghost);f.users.remove(Integer.valueOf(ghost));
                int a=f.admin(),b=f.admin();int target=targetExists?f.operator:ghost;assertTrue(target<a);
                var before=targetExists?f.userSnapshot(target):null;
                var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
                var locked=Collections.synchronizedList(new ArrayList<Integer>());
                var first=new UtenteRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                    if(after&&sql.startsWith("SELECT GET_LOCK")){held.countDown();await(release);}}));
                var second=new UtenteRepository(PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                    if(!after&&sql.startsWith("SELECT GET_LOCK"))attempted.countDown();
                    if(!after&&sql.contains("FROM utente")&&sql.contains("FOR UPDATE"))locked.add(id);}));
                Throwable outcome;
                try(var pool=Executors.newFixedThreadPool(2)){
                    var demotion=pool.submit(()->first.updateRuolo(b,a,RuoloGestionale.OPERATORE_DESK));await(held);
                    var request=pool.submit(()->second.updateRuolo(a,target,RuoloGestionale.AMMINISTRATORE));await(attempted);
                    assertThrows(TimeoutException.class,()->request.get(100,TimeUnit.MILLISECONDS));release.countDown();
                    assertEquals(RuoloGestionale.OPERATORE_DESK,demotion.get(10,TimeUnit.SECONDS).ruoloGestionale());
                    outcome=assertThrows(ExecutionException.class,()->request.get(10,TimeUnit.SECONDS)).getCause();
                }finally{release.countDown();}
                assertInstanceOf(OperazioneNonAutorizzataException.class,outcome,"target esistente="+targetExists);
                assertEquals("Operazione non autorizzata.",outcome.getMessage());
                assertEquals(List.of(target,a),locked,"lock UTENTE in ordine crescente, senza inversione");
                if(targetExists)assertEquals(before,f.userSnapshot(target));else assertTrue(f.accounts().findById(target).isEmpty());
                assertEquals(RuoloGestionale.OPERATORE_DESK,f.accounts().findById(a).orElseThrow().ruoloGestionale());
                assertEquals(RuoloGestionale.AMMINISTRATORE,f.accounts().findById(b).orElseThrow().ruoloGestionale());
            }
        }
    }

}
