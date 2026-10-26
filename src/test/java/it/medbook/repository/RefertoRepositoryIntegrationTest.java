package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.*;
import it.medbook.referto.*;
import it.medbook.service.RefertoService;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RefertoRepositoryIntegrationTest {
    @TempDir Path temp;
    ConnectionFactory factory; int desk,patient,doctor,other,slot,booking;
    final List<Integer> users=new ArrayList<>();
    static final Clock CLOCK=Clock.fixed(Instant.parse("2030-01-01T10:00:00.987654321Z"),ZoneOffset.UTC);
    @BeforeEach void setup()throws Exception {
        assumeTrue(System.getenv("MEDBOOK_TEST_DB_SERVER_UUID")!=null && !System.getenv("MEDBOOK_TEST_DB_SERVER_UUID").isBlank(),"UUID isolato obbligatorio.");
        factory=TestDatabaseConnections.isolatedFactory();temp=temp.toRealPath();
        desk=user("OPERATORE_DESK");patient=user(null);doctor=user(null);other=user(null);
        execute("INSERT INTO paziente VALUES (?,?,?,NULL)",patient,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));
        for(int med:new int[]{doctor,other})execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",med,"SinteticoUC22",UUID.randomUUID().toString());
        slot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?, '2030-01-01 08:00:00','2030-01-01 09:00:00')",doctor);
        booking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'EFFETTUATA','2029-12-01 08:00:00','2029-12-02 08:00:00',80)",patient,slot,desk,doctor);
    }
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('FixtureUC22','Sintetico',?,'invalid-test-hash',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int insert(String sql,Object... args)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,args);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...args)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,args);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    List<List<Object>> rows(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento","referto","rimborso").contains(table));String sql=table.equals("rimborso")?"SELECT r.* FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=? ORDER BY 1":"SELECT * FROM "+table+" WHERE id_prenotazione=? ORDER BY 1";try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    @AfterEach void cleanup()throws Exception{
        if(factory==null)return;if(booking>0){execute("DELETE FROM referto WHERE id_prenotazione=?",booking);execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",booking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int id:users)execute("DELETE FROM utente WHERE id_utente=?",id);
    }
    RefertoRepository repo(ConnectionFactory f){return new RefertoRepository(f,CLOCK);}
    it.medbook.dto.CaricamentoRefertoResponse save(RefertoRepository r,int med,RefertoRepository.Outcome outcome)throws SQLException{return r.saveAtomically(booking,med,"documents/"+UUID.randomUUID()+"/document.pdf","referto-test.pdf",outcome);}
    @Test void insertGeneratedKeyTimestampAndAllOtherDataPreserved()throws Exception{
        insert("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES (?,'ONLINE',80,'PAGATO','2029-12-02','2029-12-02')",booking);
        var before=rows("prenotazione");var payments=rows("pagamento");AtomicInteger reads=new AtomicInteger();
        Clock counted=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){reads.incrementAndGet();return CLOCK.instant();}};
        var outcome=new RefertoRepository.Outcome();var result=save(new RefertoRepository(factory,counted),doctor,outcome);
        assertFalse(outcome.safeToDelete());assertTrue(result.getIdReferto()>0);assertEquals(booking,result.getIdPrenotazione());assertEquals(1,reads.get());
        assertEquals(0,result.getCaricatoIl().getNano());try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM referto WHERE id_referto=?")){s.setInt(1,result.getIdReferto());try(ResultSet r=s.executeQuery()){assertTrue(r.next());assertEquals(doctor,r.getInt("id_medico_caricamento"));assertEquals(result.getCaricatoIl(),r.getObject("caricato_il",LocalDateTime.class));assertEquals(result.getNomeFile(),r.getString("nome_file"));assertFalse(Path.of(r.getString("percorso_file")).isAbsolute());}}
        assertEquals(before,rows("prenotazione"));assertEquals(payments,rows("pagamento"));assertTrue(rows("rimborso").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"RICHIESTA","CONFERMATA","RIFIUTATA","ANNULLATA","PAZIENTE_ASSENTE","NON_EROGATA"})
    void incompatibleStatesRollback(String state)throws Exception{execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state,booking);var before=rows("prenotazione");var outcome=new RefertoRepository.Outcome();assertThrows(ConflittoPrenotazioneException.class,()->save(repo(factory),doctor,outcome));assertTrue(outcome.safeToDelete());assertEquals(before,rows("prenotazione"));assertTrue(rows("referto").isEmpty());}
    @Test void foreignDoctorAndMissingBooking404()throws Exception{assertThrows(RisorsaNonTrovataException.class,()->save(repo(factory),other,new RefertoRepository.Outcome()));assertThrows(RisorsaNonTrovataException.class,()->repo(factory).saveAtomically(Integer.MAX_VALUE,doctor,"key","name",new RefertoRepository.Outcome()));assertTrue(rows("referto").isEmpty());}
    @Test void effectiveSubstituteOnly()throws Exception{execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",other,booking);assertThrows(RisorsaNonTrovataException.class,()->save(repo(factory),doctor,new RefertoRepository.Outcome()));save(repo(factory),other,new RefertoRepository.Outcome());assertEquals(1,rows("referto").size());}
    @Test void duplicate409AndUniqueConstraint()throws Exception{save(repo(factory),doctor,new RefertoRepository.Outcome());var snapshot=rows("referto");assertThrows(ConflittoPrenotazioneException.class,()->save(repo(factory),doctor,new RefertoRepository.Outcome()));assertEquals(snapshot,rows("referto"));assertThrows(SQLException.class,()->insert("INSERT INTO referto (id_prenotazione,id_medico_caricamento,nome_file,percorso_file,caricato_il) VALUES (?,?, 'other.pdf','other','2030-01-01')",booking,doctor));}
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    class Probe {
        String fault;boolean closed;boolean committed;int originalIsolation, isolationAtClose;boolean autoAtClose;List<String> events=new ArrayList<>();
        ConnectionFactory factory(){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{Connection real=RefertoRepositoryIntegrationTest.this.factory.getConnection();int original=real.getTransactionIsolation();originalIsolation=original;return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                String name=m.getName();synchronized(events){events.add(name+(a==null?"":Arrays.toString(a)));}
                if(name.equals("commit")){assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());if("commit-before".equals(fault))throw new SQLException("synthetic commit failure");Object value=invoke(real,m,a);committed=true;if("commit-after".equals(fault))throw new SQLException("synthetic ambiguous commit");return value;}
                if(name.equals("rollback")&&"rollback".equals(fault))throw new SQLException("synthetic rollback failure");
                if(name.equals("setTransactionIsolation")&&(int)a[0]==original&&("restore".equals(fault)||"restore-rollback".equals(fault)))throw new SQLException("synthetic restore failure");
                if(name.equals("close")){isolationAtClose=real.getTransactionIsolation();autoAtClose=real.getAutoCommit();closed=true;Object value=invoke(real,m,a);if("close".equals(fault))throw new SQLException("synthetic close failure");return value;}
                Object value=invoke(real,m,a);
                if(name.equals("prepareStatement")){String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{Object answer=invoke(statement,n,b);
                    if(sql.contains("INSERT INTO referto")&&n.getName().equals("executeUpdate")&&"zero-count".equals(fault))return 0;
                    if(n.getName().equals("getGeneratedKeys")&&"generated-key".equals(fault)) {
                        ResultSet realRows=(ResultSet)answer;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(z,k,args)->k.getName().equals("next")?false:invoke(realRows,k,args));
                    }
                    if(sql.contains("INSERT INTO referto")&&n.getName().equals("executeUpdate")&&Set.of("insert","rollback","restore-rollback").contains(fault==null?"":fault))throw new SQLException("synthetic insert failure");return answer;});}return value;});}
        };}
    }
    @ParameterizedTest @ValueSource(strings={"insert","commit-before","commit-after","restore","close","rollback","restore-rollback","generated-key","zero-count"})
    void faultOutcomesAndConnectionClosure(String fault)throws Exception{
        Probe probe=new Probe();probe.fault=fault;var outcome=new RefertoRepository.Outcome();SQLException error=assertThrows(SQLException.class,()->save(repo(probe.factory()),doctor,outcome));assertTrue(probe.closed);
        boolean persisted=Set.of("commit-after","restore","close").contains(fault);assertEquals(persisted?1:0,rows("referto").size());
        assertEquals(Set.of("insert","restore-rollback","generated-key","zero-count").contains(fault),outcome.safeToDelete());
        if(Set.of("rollback","restore-rollback").contains(fault))assertEquals(1,error.getSuppressed().length);
        if(Set.of("rollback","restore","restore-rollback").contains(fault)){assertFalse(probe.events.contains("setAutoCommit[true]"));assertFalse(probe.autoAtClose);}
        if(Set.of("restore","restore-rollback").contains(fault))assertEquals(Connection.TRANSACTION_READ_COMMITTED,probe.isolationAtClose);
        if("restore-rollback".equals(fault)){assertEquals("synthetic insert failure",error.getMessage());assertEquals("synthetic restore failure",error.getSuppressed()[0].getMessage());}
        if(!Set.of("rollback","restore","restore-rollback").contains(fault)){assertEquals(probe.originalIsolation,probe.isolationAtClose);assertTrue(probe.autoAtClose);}
    }
    @Test void isolationAndAutocommitRestoredAfterSuccessAndConflict()throws Exception{
        Probe first=new Probe();save(repo(first.factory()),doctor,new RefertoRepository.Outcome());assertTrue(first.closed);assertTrue(first.events.contains("setAutoCommit[true]"));assertEquals(Connection.TRANSACTION_REPEATABLE_READ,first.originalIsolation);assertEquals(first.originalIsolation,first.isolationAtClose);assertTrue(first.autoAtClose);
        Probe second=new Probe();assertThrows(ConflittoPrenotazioneException.class,()->save(repo(second.factory()),doctor,new RefertoRepository.Outcome()));assertTrue(second.closed);assertTrue(second.events.contains("setAutoCommit[true]"));assertEquals(first.originalIsolation,second.originalIsolation);assertEquals(second.originalIsolation,second.isolationAtClose);assertTrue(second.autoAtClose);
    }
    @Test void twoUploadsOneWinnerNoDeadlock()throws Exception{
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<String> upload=()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{save(repo(factory),doctor,new RefertoRepository.Outcome());return "created";}catch(ConflittoPrenotazioneException e){return "conflict";}};
            var a=executor.submit(upload);var b=executor.submit(upload);start.countDown();assertEquals(Set.of("created","conflict"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));assertEquals(1,rows("referto").size());
        }finally{start.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"insert","commit-before","commit-after","restore"})
    void servicePreservesPotentiallyReferencedFileAndCleansCertainRollback(String fault)throws Exception{
        Probe probe=new Probe();probe.fault=fault;var storage=new RefertoStorage(temp.resolve("private"));
        var validator=new PdfValidator(null,storage.scratch()){@Override public void validate(Path file){}};
        var service=new RefertoService(repo(probe.factory()),storage,validator);
        assertThrows(SQLException.class,()->service.upload(booking,doctor,new ByteArrayInputStream(new byte[]{1}),null));
        try(var dirs=Files.list(temp.resolve("private/documents"))){assertEquals(fault.equals("insert")?0:1,dirs.count());}
        if(Set.of("commit-after","restore").contains(fault)){try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT percorso_file FROM referto WHERE id_prenotazione=?")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){assertTrue(r.next());assertTrue(Files.exists(temp.resolve("private").resolve(r.getString(1))));}}}
    }
    @Test void originalPdfRealPipelineToMetadata()throws Exception{
        String executable=System.getenv("MEDBOOK_QPDF_EXECUTABLE");assumeTrue(executable!=null,"qpdf richiesto.");
        Path original=temp.resolve("original.pdf");try(PDDocument doc=new PDDocument()){PDPage page=new PDPage();page.setResources(new PDResources());doc.addPage(page);doc.save(original.toFile());}
        byte[] bytes=Files.readAllBytes(original);var storage=new RefertoStorage(temp.resolve("private"));var qpdf=new QpdfValidator(Path.of(executable),Duration.ofSeconds(10));qpdf.verifyVersion();
        var service=new RefertoService(repo(factory),storage,new PdfValidator(qpdf,storage.scratch()));
        var result=service.upload(booking,doctor,new ByteArrayInputStream(bytes),(long)bytes.length);assertTrue(result.getIdReferto()>0);
        try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT percorso_file FROM referto WHERE id_prenotazione=?")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){assertTrue(r.next());assertArrayEquals(bytes,Files.readAllBytes(temp.resolve("private").resolve(r.getString(1))));}}
    }
    @Test void secondUploadWaitsAndSeesFirstCommit()throws Exception {
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),secondSlot=new CountDownLatch(1);
        AtomicInteger readers=new AtomicInteger();
        ConnectionFactory first=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection()throws SQLException {
                Connection real=factory.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    Object value=invoke(real,m,a);
                    if(m.getName().equals("prepareStatement")) {
                        String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{
                            Object answer=invoke(statement,n,b);
                            if(n.getName().equals("executeQuery")&&sql.contains("FROM prenotazione")&&sql.contains("FOR UPDATE")) {
                                locked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("controlled timeout");
                            }
                            return answer;
                        });
                    }return value;
                });
            }
        };
        ConnectionFactory second=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection()throws SQLException {
                Connection real=factory.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("prepareStatement")&&((String)a[0]).contains("FROM disponibilita"))secondSlot.countDown();
                    return invoke(real,m,a);
                });
            }
        };
        Clock counted=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){readers.incrementAndGet();return CLOCK.instant();}};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var winner=executor.submit(()->save(new RefertoRepository(first,counted),doctor,new RefertoRepository.Outcome()));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var loser=executor.submit(()->{assertThrows(ConflittoPrenotazioneException.class,()->save(repo(second),doctor,new RefertoRepository.Outcome()));return null;});
            assertTrue(secondSlot.await(5,TimeUnit.SECONDS));assertEquals(0,readers.get());release.countDown();
            assertTrue(winner.get(10,TimeUnit.SECONDS).getIdReferto()>0);loser.get(10,TimeUnit.SECONDS);assertEquals(1,readers.get());assertEquals(1,rows("referto").size());
        }finally{release.countDown();}
    }

    @Test void uc21CommitBeforeUploadMakesReportEligible()throws Exception {
        execute("UPDATE prenotazione SET stato='CONFERMATA' WHERE id_prenotazione=?",booking);
        CountDownLatch slotLocked=new CountDownLatch(1),release=new CountDownLatch(1),uploadSlot=new CountDownLatch(1);
        ConnectionFactory visitFactory=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection()throws SQLException {
                Connection real=factory.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    Object value=invoke(real,m,a);
                    if(m.getName().equals("prepareStatement")) {
                        String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{
                            Object answer=invoke(statement,n,b);
                            if(n.getName().equals("executeQuery")&&sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE")) {
                                slotLocked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("controlled timeout");
                            }
                            return answer;
                        });
                    }return value;
                });
            }
        };
        ConnectionFactory observed=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection()throws SQLException {
                Connection real=factory.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("prepareStatement")&&((String)a[0]).contains("FROM disponibilita"))uploadSlot.countDown();
                    return invoke(real,m,a);
                });
            }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var visit=executor.submit(()->new PrenotazioneRepository(visitFactory,CLOCK).registraVisitaAtomically(booking,doctor));
            assertTrue(slotLocked.await(5,TimeUnit.SECONDS));
            var upload=executor.submit(()->save(repo(observed),doctor,new RefertoRepository.Outcome()));
            assertTrue(uploadSlot.await(5,TimeUnit.SECONDS));release.countDown();
            assertEquals(it.medbook.model.StatoPrenotazione.EFFETTUATA,visit.get(10,TimeUnit.SECONDS).getStato());
            assertTrue(upload.get(10,TimeUnit.SECONDS).getIdReferto()>0);
        }finally{release.countDown();}
    }

    @Test void preliminaryCompetenceClosesConnectionBeforeValidationAndFinalStateCheck()throws Exception {
        Probe probe=new Probe();var storage=new RefertoStorage(temp.resolve("private"));
        var validator=new PdfValidator(null,storage.scratch()){@Override public void validate(Path file)throws IOException {
            assertTrue(probe.closed);assertFalse(probe.events.stream().anyMatch(e->e.contains("FOR UPDATE")||e.contains("FOR SHARE")));
            try {execute("UPDATE prenotazione SET stato='CONFERMATA' WHERE id_prenotazione=?",booking);}catch(SQLException e){throw new IOException(e);}
        }};
        var service=new RefertoService(repo(probe.factory()),storage,validator);
        assertThrows(ConflittoPrenotazioneException.class,()->service.upload(booking,doctor,new ByteArrayInputStream(new byte[]{1}),null));
        assertTrue(rows("referto").isEmpty());try(var dirs=Files.list(temp.resolve("private/documents"))){assertEquals(0,dirs.count());}
        assertTrue(probe.closed);assertEquals(probe.originalIsolation,probe.isolationAtClose);assertTrue(probe.autoAtClose);
    }
    @Test void preliminaryCompetenceRejectsForeignAndMissingWithoutLocks()throws Exception {
        Probe probe=new Probe();var repository=repo(probe.factory());repository.verifyCompetence(booking,doctor);
        assertThrows(RisorsaNonTrovataException.class,()->repository.verifyCompetence(booking,other));
        assertThrows(RisorsaNonTrovataException.class,()->repository.verifyCompetence(Integer.MAX_VALUE,doctor));
        assertTrue(probe.closed);assertFalse(probe.events.stream().anyMatch(e->e.contains("FOR UPDATE")||e.contains("FOR SHARE")));assertTrue(rows("referto").isEmpty());
    }

    @Test void uc09OwnedReadPreservesRowsAndDoesNotFilterState() throws Exception {
        save(repo(factory), doctor, new RefertoRepository.Outcome());
        var before=rows("referto");var bookings=rows("prenotazione");
        var result=repo(factory).findForPatient(booking,patient);
        assertEquals(booking,result.idPrenotazione());assertEquals(((Number)before.get(0).get(0)).intValue(),result.idReferto().intValue());
        assertEquals("referto-test.pdf",result.nomeFile());assertTrue(result.chiaveStorage().startsWith("documents/"));
        assertEquals(before,rows("referto"));assertEquals(bookings,rows("prenotazione"));
        execute("UPDATE prenotazione SET stato='NON_EROGATA' WHERE id_prenotazione=?",booking);
        assertEquals(result,repo(factory).findForPatient(booking,patient));
    }
    @Test void uc09MissingForeignAndAbsentHaveSameException() throws Exception {
        var absent=assertThrows(RisorsaNonTrovataException.class,()->repo(factory).findForPatient(booking,patient));
        save(repo(factory),doctor,new RefertoRepository.Outcome());
        var foreign=assertThrows(RisorsaNonTrovataException.class,()->repo(factory).findForPatient(booking,other));
        var missing=assertThrows(RisorsaNonTrovataException.class,()->repo(factory).findForPatient(Integer.MAX_VALUE,patient));
        assertEquals(absent.getMessage(),foreign.getMessage());assertEquals(absent.getMessage(),missing.getMessage());
    }
    @Test void uc09ReadBeforeAndAfterCommitUsesNoExclusiveLock() throws Exception {
        try(Connection writer=factory.getConnection()) {
            writer.setAutoCommit(false);
            try(PreparedStatement statement=writer.prepareStatement("INSERT INTO referto (id_prenotazione,id_medico_caricamento,nome_file,percorso_file,caricato_il) VALUES (?,?,'fixture.pdf','documents/00000000-0000-0000-0000-000000000000/document.pdf','2030-01-01')")) {
                statement.setInt(1,booking);statement.setInt(2,doctor);statement.executeUpdate();
            }
            // INSERT già completato ma non committato: la SELECT non deve attendere il suo lock.
            try(var readers=Executors.newSingleThreadExecutor()) {
                readers.submit(()->assertThrows(RisorsaNonTrovataException.class,()->repo(factory).findForPatient(booking,patient))).get(3,TimeUnit.SECONDS);
                writer.commit();
                assertEquals(booking,readers.submit(()->repo(factory).findForPatient(booking,patient)).get(3,TimeUnit.SECONDS).idPrenotazione());
            } finally { writer.rollback(); }
        }
    }
    @Test void uc09ServiceReadsOriginalAndOrphanCannotBeReached() throws Exception {
        RefertoStorage storage=new RefertoStorage(temp.resolve("read-private"));
        byte[] bytes={0,1,(byte)255};var candidate=storage.publish(storage.receive(new ByteArrayInputStream(bytes),null));
        var service=new RefertoService(repo(factory),storage,null);
        assertThrows(RisorsaNonTrovataException.class,()->service.download(booking,patient));
        assertTrue(Files.exists(candidate.path()));
        repo(factory).saveAtomically(booking,doctor,candidate.key(),candidate.name(),new RefertoRepository.Outcome());
        try(var download=service.download(booking,patient)){assertArrayEquals(bytes,download.input().readAllBytes());}
        Files.delete(candidate.path());
        assertThrows(IOException.class,()->service.download(booking,patient));
        assertEquals(1,rows("referto").size());
    }

    @Test void uc09ClosesJdbcBeforeOpeningAndStreaming() throws Exception {
        RefertoStorage storage=new RefertoStorage(temp.resolve("closed-jdbc"));
        var candidate=storage.publish(storage.receive(new ByteArrayInputStream(new byte[]{1,2}),null));
        repo(factory).saveAtomically(booking,doctor,candidate.key(),candidate.name(),new RefertoRepository.Outcome());
        Probe probe=new Probe();
        RefertoStorage checked=new RefertoStorage(temp.resolve("closed-jdbc")) {
            @Override public Download openDocument(String key)throws IOException {
                assertTrue(probe.closed,"Connection must be closed before opening the PDF");
                assertFalse(probe.events.stream().anyMatch(e->e.contains("FOR UPDATE")||e.contains("FOR SHARE")));
                return super.openDocument(key);
            }
        };
        var service=new RefertoService(repo(probe.factory()),checked,null);
        try(var download=service.download(booking,patient)) {
            assertTrue(probe.closed);assertArrayEquals(new byte[]{1,2},download.input().readAllBytes());
        }
    }

}
