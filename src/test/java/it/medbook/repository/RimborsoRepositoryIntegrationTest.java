package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.*;
import it.medbook.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RimborsoRepositoryIntegrationTest {
    ConnectionFactory factory;int desk,patient,doctor,slot,booking,payment;List<Integer> users=new ArrayList<>();
    static final LocalDateTime NOW=LocalDateTime.of(2030,1,1,12,0);
    static final Clock CLOCK=Clock.fixed(NOW.toInstant(ZoneOffset.UTC).plusNanos(987654321),ZoneOffset.UTC);
    static final String MOTIVE="  Prestazione 😀 non erogata\r\n\t  ";
    @BeforeEach void setup()throws Exception {
        assumeTrue(System.getenv("MEDBOOK_TEST_DB_SERVER_UUID")!=null&&!System.getenv("MEDBOOK_TEST_DB_SERVER_UUID").isBlank(),"UUID Docker isolato obbligatorio.");
        factory=TestDatabaseConnections.isolatedFactory();desk=user("OPERATORE_DESK");patient=user(null);doctor=user(null);
        execute("INSERT INTO paziente VALUES (?,?,?,NULL)",patient,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));
        execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",doctor,"Fixture",UUID.randomUUID().toString());
        slot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.minusHours(2),NOW.minusHours(1));
        booking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'NON_EROGATA',?,?,80)",patient,slot,desk,doctor,NOW.minusDays(1),NOW.minusMinutes(30));
        payment=pay("ONLINE","PAGATO",new BigDecimal("80.00"));
    }
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('FixtureUC16','Test',?,'invalid-fixture',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int pay(String mode,String state,BigDecimal amount)throws SQLException{return insert("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES (?,?,?,?,?,?)",booking,mode,amount,state,NOW.minusHours(2),state.equals("PAGATO")?NOW.minusHours(2):null);}
    int insert(String sql,Object...args)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,args);assertEquals(1,s.executeUpdate());try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...args)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,args);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    List<List<Object>> query(String sql,Object...args)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,args);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    List<List<Object>> snapshot(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento","rimborso").contains(table));return table.equals("rimborso")?query("SELECT r.* FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=? ORDER BY r.id_rimborso",booking):query("SELECT * FROM "+table+" WHERE id_prenotazione=? ORDER BY 1",booking);}
    RimborsoRepository repo(){return new RimborsoRepository(factory,CLOCK);}
    int request()throws SQLException{return repo().richiediAtomically(booking,patient,MOTIVE).getIdRimborso();}
    void noRefund()throws SQLException{assertTrue(snapshot("rimborso").isEmpty());}
    @AfterEach void cleanup()throws Exception {if(factory==null)return;if(booking>0){execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",booking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int u:users)execute("DELETE FROM utente WHERE id_utente=?",u);}
    @Test void requestPreservesEverythingAndTruncatesOneClockRead()throws Exception {
        var b=snapshot("prenotazione");var p=snapshot("pagamento");AtomicInteger reads=new AtomicInteger();
        Clock counting=new Clock(){@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){reads.incrementAndGet();return CLOCK.instant();}};
        var r=new RimborsoRepository(factory,counting).richiediAtomically(booking,patient,MOTIVE);
        assertEquals(1,reads.get());assertEquals(booking,r.getIdPrenotazione());assertEquals(payment,r.getIdPagamento());assertEquals(StatoRimborso.RICHIESTO,r.getStato());assertEquals(MOTIVE,r.getMotivazione());assertEquals(NOW,r.getRichiestoIl());assertNull(r.getVerificatoIl());assertEquals(new BigDecimal("80.00"),r.getImporto());
        var row=query("SELECT richiesto_il,motivazione,verificato_da,verificato_il,provider_refund_id,rimborsato_il FROM rimborso WHERE id_rimborso=?",r.getIdRimborso()).getFirst();assertEquals(Arrays.asList(NOW,MOTIVE,null,null,null,null),row);
        assertEquals(b,snapshot("prenotazione"));assertEquals(p,snapshot("pagamento"));
    }
    @ParameterizedTest @ValueSource(strings={"DESK_POS","DESK_CONTANTI"})void deskPaymentsNotRefundable(String mode)throws Exception{execute("UPDATE pagamento SET modalita=?,registrato_da=? WHERE id_pagamento=?",mode,desk,payment);assertThrows(ConflittoPrenotazioneException.class,this::request);noRefund();}
    @ParameterizedTest @ValueSource(strings={"IN_ATTESA","FALLITO"})void unpaidOnlineNotRefundable(String state)throws Exception{execute("UPDATE pagamento SET stato=?,pagato_il=NULL WHERE id_pagamento=?",state,payment);assertThrows(ConflittoPrenotazioneException.class,this::request);noRefund();}
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"NON_EROGATA"},mode=EnumSource.Mode.EXCLUDE)void otherBookingStatesRejected(StatoPrenotazione state)throws Exception{execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state.name(),booking);assertThrows(ConflittoPrenotazioneException.class,this::request);noRefund();}
    @ParameterizedTest @ValueSource(strings={"zero","mismatch","twoPaid","pending","report","noPayment"})void economicAnomaliesRejected(String mode)throws Exception{
        switch(mode){case "zero"->execute("UPDATE prenotazione SET prezzo=0 WHERE id_prenotazione=?",booking);case "mismatch"->execute("UPDATE pagamento SET importo=79 WHERE id_pagamento=?",payment);case "twoPaid"->pay("ONLINE","PAGATO",new BigDecimal("80"));case "pending"->pay("ONLINE","IN_ATTESA",new BigDecimal("80"));case "report"->execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);case "noPayment"->execute("DELETE FROM pagamento WHERE id_pagamento=?",payment);}
        assertThrows(ConflittoPrenotazioneException.class,this::request);noRefund();
    }
    @Test void failedAttemptsRetainedAndDoNotBlock()throws Exception{pay("ONLINE","FALLITO",new BigDecimal("80"));var before=snapshot("pagamento");request();assertEquals(before,snapshot("pagamento"));}
    @Test void ownershipAndMissingPatients()throws Exception{int other=user(null);execute("INSERT INTO paziente VALUES (?,?,?,NULL)",other,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));assertThrows(RisorsaNonTrovataException.class,()->repo().richiediAtomically(booking,other,MOTIVE));assertThrows(RisorsaNonTrovataException.class,()->repo().richiediAtomically(booking,Integer.MAX_VALUE,MOTIVE));assertThrows(RisorsaNonTrovataException.class,()->repo().richiediAtomically(Integer.MAX_VALUE,patient,MOTIVE));noRefund();}
    @ParameterizedTest @ValueSource(booleans={false,true})void decisionOnlyChangesThreeFields(boolean approve)throws Exception{int id=request();var before=snapshot("rimborso").getFirst();var b=snapshot("prenotazione");var p=snapshot("pagamento");var result=repo().decidiAtomically(id,desk,approve);var after=snapshot("rimborso").getFirst();for(int i=0;i<before.size();i++)if(!Set.of(2,3,6).contains(i))assertEquals(before.get(i),after.get(i),"column "+i);assertEquals(approve?"APPROVATO":"RESPINTO",after.get(3));assertEquals(desk,((Number)after.get(2)).intValue());assertEquals(NOW,after.get(6));assertEquals(NOW,result.getVerificatoIl());assertEquals(desk,result.getIdOperatore());assertEquals(b,snapshot("prenotazione"));assertEquals(p,snapshot("pagamento"));assertThrows(ConflittoPrenotazioneException.class,()->repo().decidiAtomically(id,desk,!approve));assertThrows(ConflittoPrenotazioneException.class,this::request);assertEquals(List.of(after),snapshot("rimborso"));}
    @ParameterizedTest @ValueSource(strings={"mode","state","price","amount","refundAmount","twoPaid","pending"})void approvalRechecksButRefusalClosesEconomicIncoherence(String mode)throws Exception{int id=request();switch(mode){case "mode"->execute("UPDATE pagamento SET modalita='DESK_POS',registrato_da=? WHERE id_pagamento=?",desk,payment);case "state"->execute("UPDATE prenotazione SET stato='EFFETTUATA' WHERE id_prenotazione=?",booking);case "price"->execute("UPDATE prenotazione SET prezzo=0 WHERE id_prenotazione=?",booking);case "amount"->execute("UPDATE pagamento SET importo=70 WHERE id_pagamento=?",payment);case "refundAmount"->execute("UPDATE rimborso SET importo=70 WHERE id_rimborso=?",id);case "twoPaid"->pay("ONLINE","PAGATO",new BigDecimal("80"));case "pending"->pay("ONLINE","IN_ATTESA",new BigDecimal("80"));}var before=snapshot("rimborso");assertThrows(ConflittoPrenotazioneException.class,()->repo().decidiAtomically(id,desk,true));assertEquals(before,snapshot("rimborso"));assertEquals(StatoRimborso.RESPINTO,repo().decidiAtomically(id,desk,false).getStato());assertEquals(MOTIVE,snapshot("rimborso").getFirst().get(7));}
    @Test void rolesAndMissingResources()throws Exception{int id=request();for(String role:new String[]{null,"AMMINISTRATORE"}){execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",role,desk);assertThrows(OperazioneNonAutorizzataException.class,()->repo().decidiAtomically(id,desk,true));}assertThrows(RisorsaNonTrovataException.class,()->repo().decidiAtomically(id,Integer.MAX_VALUE,true));execute("UPDATE utente SET ruolo_gestionale='OPERATORE_DESK' WHERE id_utente=?",desk);assertThrows(RisorsaNonTrovataException.class,()->repo().decidiAtomically(Integer.MAX_VALUE,desk,true));}
    @Test void patientAndDeskListsAreScopedAndReadOnly()throws Exception{int id=request();int other=user(null);execute("INSERT INTO paziente VALUES (?,?,?,NULL)",other,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));var before=snapshot("rimborso");assertTrue(repo().findByPaziente(other).isEmpty());var own=repo().findByPaziente(patient);assertEquals(1,own.size());assertEquals(id,own.getFirst().getIdRimborso());var deskList=repo().findRichieste().stream().filter(r->r.getIdRimborso()==id).toList();assertEquals(1,deskList.size());var r=deskList.getFirst();assertEquals(patient,r.getIdPaziente());assertEquals("FixtureUC16",r.getNomePaziente());assertEquals("Test",r.getCognomePaziente());assertEquals("ONLINE",r.getModalitaPagamento());assertEquals(NOW.minusHours(2),r.getDataOraInizio());assertNull(r.getIdPrestazione());assertEquals(before,snapshot("rimborso"));repo().decidiAtomically(id,desk,false);assertTrue(repo().findRichieste().stream().noneMatch(x->x.getIdRimborso()==id));assertEquals(StatoRimborso.RESPINTO,repo().findByPaziente(patient).getFirst().getStato());}
    static Object invoke(Object target,Method m,Object[] a)throws Throwable{try{return m.invoke(target,a);}catch(InvocationTargetException e){throw e.getCause();}}
    @FunctionalInterface interface Observer{void observe(String sql)throws SQLException;}
    ConnectionFactory observed(Observer observer,boolean failCommit){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(failCommit&&m.getName().equals("commit"))throw new SQLException("synthetic commit failure");Object value=invoke(real,m,a);if(m.getName().equals("prepareStatement")){String sql=(String)a[0];PreparedStatement s=(PreparedStatement)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(q,n,b)->{if(n.getName().startsWith("execute")){if(sql.contains("INSERT INTO rimborso")||sql.contains("UPDATE rimborso"))assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());observer.observe(sql);}return invoke(s,n,b);});}return value;});}};}
    @ParameterizedTest @ValueSource(booleans={false,true})void creationRollback(boolean failCommit)throws Exception{var before=snapshot("pagamento");var f=observed(sql->{if(!failCommit&&sql.contains("INSERT INTO rimborso"))throw new SQLException("synthetic insert");},failCommit);assertThrows(SQLException.class,()->new RimborsoRepository(f,CLOCK).richiediAtomically(booking,patient,MOTIVE));noRefund();assertEquals(before,snapshot("pagamento"));}
    @ParameterizedTest @ValueSource(booleans={false,true})void decisionRollback(boolean failCommit)throws Exception{int id=request();var before=snapshot("rimborso");var f=observed(sql->{if(!failCommit&&sql.contains("UPDATE rimborso"))throw new SQLException("synthetic update");},failCommit);assertThrows(SQLException.class,()->new RimborsoRepository(f,CLOCK).decidiAtomically(id,desk,true));assertEquals(before,snapshot("rimborso"));}
    static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("controlled timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
    @ParameterizedTest @ValueSource(strings={"create","approve-first","refuse-first"})void concurrentRequestsOrDecisionsHaveOneWinner(String mode)throws Exception {
        boolean decision=!mode.equals("create"),firstApproval=!mode.equals("refuse-first");
        int id=decision?request():0;int secondDesk=user("OPERATORE_DESK");CountDownLatch firstWriting=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        var first=observed(sql->{if(sql.contains(decision?"UPDATE rimborso":"INSERT INTO rimborso")){firstWriting.countDown();await(secondAttempt);}},false);
        var second=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();},false);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Boolean> a=pool.submit(()->race(first,id,desk,decision,firstApproval));await(firstWriting);Future<Boolean> b=pool.submit(()->race(second,id,secondDesk,decision,!firstApproval));assertTrue(a.get(15,TimeUnit.SECONDS));assertFalse(b.get(15,TimeUnit.SECONDS));assertEquals(1,snapshot("rimborso").size());var row=snapshot("rimborso").getFirst();assertEquals(decision?(firstApproval?"APPROVATO":"RESPINTO"):"RICHIESTO",row.get(3));if(decision){assertEquals(desk,((Number)row.get(2)).intValue());assertEquals(NOW,row.get(6));}assertEquals("PAGATO",query("SELECT stato FROM pagamento WHERE id_pagamento=?",payment).getFirst().getFirst());}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    boolean race(ConnectionFactory f,int id,int actor,boolean decision,boolean approve)throws SQLException{try{var r=new RimborsoRepository(f,CLOCK);if(decision)r.decidiAtomically(id,actor,approve);else r.richiediAtomically(booking,patient,MOTIVE);return true;}catch(ConflittoPrenotazioneException e){return false;}}
    @Test void clockReadAfterWaitingForSlot()throws Exception{AtomicInteger reads=new AtomicInteger();CountDownLatch attempt=new CountDownLatch(1);Clock counting=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return CLOCK.instant();}};ExecutorService pool=Executors.newSingleThreadExecutor();try(Connection blocker=factory.getConnection()){blocker.setAutoCommit(false);try(PreparedStatement s=blocker.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")){s.setInt(1,slot);try(ResultSet r=s.executeQuery()){assertTrue(r.next());}}var f=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))attempt.countDown();},false);Future<?> request=pool.submit(()->new RimborsoRepository(f,counting).richiediAtomically(booking,patient,MOTIVE));await(attempt);assertEquals(0,reads.get());blocker.commit();request.get(15,TimeUnit.SECONDS);assertEquals(1,reads.get());}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
    @ParameterizedTest @ValueSource(strings={"verify-request","request-verify","desk-request","request-desk","patient-request","request-patient","pay-request","request-pay","signal-request","request-signal"})
    void interleavingWithExistingFlows(String mode)throws Exception {
        execute("UPDATE prenotazione SET stato='CONFERMATA' WHERE id_prenotazione=?",booking);
        String[] order=mode.split("-");boolean cancellation=mode.contains("desk")||mode.contains("patient"),paying=mode.contains("pay");
        if(cancellation||paying)execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);
        CountDownLatch firstLocked=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        var first=observed(sql->{boolean stop=order[0].equals("request")?sql.contains("FROM prenotazione")&&sql.contains("FOR UPDATE"):sql.contains("UPDATE prenotazione")||sql.contains("INSERT INTO pagamento");if(stop){firstLocked.countDown();await(secondAttempt);}},false);
        var second=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();},false);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a=pool.submit(()->existingAction(first,order[0]));await(firstLocked);
            Future<Boolean> b=pool.submit(()->existingAction(second,order[1]));
            boolean firstSuccess=!order[0].equals("request");boolean secondSuccess=!order[1].equals("request")||order[0].equals("verify");
            assertEquals(firstSuccess,a.get(15,TimeUnit.SECONDS));assertEquals(secondSuccess,b.get(15,TimeUnit.SECONDS));
            assertEquals(mode.equals("verify-request")?1:0,snapshot("rimborso").size());
            var row=query("SELECT stato,indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione=?",booking).getFirst();
            assertEquals(mode.contains("verify")?"NON_EROGATA":cancellation?"ANNULLATA":"CONFERMATA",row.get(0));
            assertEquals(mode.contains("signal"),row.get(1));
            if(paying)assertEquals("PAGATO",query("SELECT stato FROM pagamento WHERE id_prenotazione=?",booking).getFirst().getFirst());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    boolean existingAction(ConnectionFactory f,String action)throws SQLException {
        Clock futureVisit=Clock.fixed(NOW.minusHours(3).toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        try {
            switch(action) {
                case "request" -> new RimborsoRepository(f,CLOCK).richiediAtomically(booking,patient,MOTIVE);
                case "verify" -> new PrenotazioneRepository(f,CLOCK).verificaMancataErogazioneAtomically(booking,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA);
                case "desk" -> new PrenotazioneRepository(f,futureVisit).annullaAtomically(booking,desk,StatoPrenotazione.CONFERMATA);
                case "patient" -> new PrenotazioneRepository(f,futureVisit).annullaPazienteAtomically(booking,patient,StatoPrenotazione.CONFERMATA);
                case "pay" -> new PagamentoRepository(f,CLOCK).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));
                case "signal" -> new PrenotazioneRepository(f,futureVisit).segnalaIndisponibilitaAtomically(doctor,List.of(booking));
                default -> throw new AssertionError(action);
            }
            return true;
        } catch(ConflittoPrenotazioneException e){return false;}
    }
    @Test void deskDetailsUseConfiguredPrestazioneWithoutSensitiveFields()throws Exception {
        int ps=insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES ('Fixture UC16',30,99)");
        try {
            execute("INSERT INTO medico_prestazione VALUES (?,?)",doctor,ps);
            execute("UPDATE disponibilita SET id_prestazione=? WHERE id_disponibilita=?",ps,slot);
            int id=request();var item=repo().findRichieste().stream().filter(r->r.getIdRimborso()==id).findFirst().orElseThrow();
            assertEquals(ps,item.getIdPrestazione());assertEquals("Fixture UC16",item.getNomePrestazione());assertEquals(new BigDecimal("80.00"),item.getImporto());
            String json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(item);
            for(String field:List.of("email","password","codiceFiscale","telefono","providerRefundId"))assertFalse(json.contains(field));
        } finally {execute("UPDATE disponibilita SET id_prestazione=NULL WHERE id_disponibilita=?",slot);execute("DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?",doctor,ps);execute("DELETE FROM prestazione WHERE id_prestazione=?",ps);}
    }

    @Test void listsScopeAllOwnStatesAndUseStableDeskOrdering()throws Exception {
        int other=user(null);execute("INSERT INTO paziente VALUES (?,?,?,NULL)",other,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));
        int otherBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,stato,data_ora_richiesta,prezzo) VALUES (?,?,'NON_EROGATA',?,80)",other,slot,NOW.minusDays(1));
        try {
            insert("INSERT INTO pagamento (id_prenotazione,modalita,stato,importo,creato_il,pagato_il) VALUES (?,'ONLINE','PAGATO',80,?,?)",otherBooking,NOW,NOW);
            int first=request();int second=repo().richiediAtomically(otherBooking,other,"Altro paziente").getIdRimborso();
            var pending=repo().findRichieste().stream().filter(r->Set.of(first,second).contains(r.getIdRimborso())).toList();
            assertEquals(List.of(first,second),pending.stream().map(r->r.getIdRimborso()).toList());
            assertEquals(List.of(first),repo().findByPaziente(patient).stream().map(r->r.getIdRimborso()).toList());
            assertEquals(List.of(second),repo().findByPaziente(other).stream().map(r->r.getIdRimborso()).toList());
            execute("UPDATE rimborso SET richiesto_il=? WHERE id_rimborso=?",NOW.minusMinutes(1),second);
            assertEquals(List.of(second,first),repo().findRichieste().stream().filter(r->Set.of(first,second).contains(r.getIdRimborso())).map(r->r.getIdRimborso()).toList());
            repo().decidiAtomically(first,desk,true);assertEquals(StatoRimborso.APPROVATO,repo().findByPaziente(patient).getFirst().getStato());
            assertTrue(repo().findRichieste().stream().noneMatch(r->r.getIdRimborso()==first));
        } finally {execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",otherBooking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",otherBooking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",otherBooking);}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void restoresIsolationAndAutocommitOnRealConnections(boolean fail)throws Exception {
        List<String> events=new ArrayList<>();ConnectionFactory traced=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection()throws SQLException {
                Connection real=factory.getConnection();int isolation=real.getTransactionIsolation();
                return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    if(fail&&m.getName().equals("commit"))throw new SQLException("synthetic");
                    Object out=invoke(real,m,a);
                    if(m.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(a[0])){assertEquals(isolation,real.getTransactionIsolation());events.add("restored");}
                    if(m.getName().equals("close")){assertTrue(real.isClosed());events.add("closed");}return out;
                });
            }
        };
        var r=new RimborsoRepository(traced,CLOCK);
        if(fail){assertThrows(SQLException.class,()->r.richiediAtomically(booking,patient,MOTIVE));noRefund();}
        else assertEquals(NOW,r.richiediAtomically(booking,patient,MOTIVE).getRichiestoIl());
        assertEquals(List.of("restored","closed"),events);
    }

    @Test void distinctBookingsCreateIndependentRefundsConcurrently()throws Exception {
        int secondSlot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.minusHours(4),NOW.minusHours(3));
        int secondBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,stato,data_ora_richiesta,prezzo) VALUES (?,?,'NON_EROGATA',?,50)",patient,secondSlot,NOW.minusDays(1));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            int secondPayment=insert("INSERT INTO pagamento (id_prenotazione,modalita,stato,importo,creato_il,pagato_il) VALUES (?,'ONLINE','PAGATO',50,?,?)",secondBooking,NOW,NOW);
            CountDownLatch bothReady=new CountDownLatch(2);
            ConnectionFactory f=observed(sql->{if(sql.contains("INSERT INTO rimborso")){bothReady.countDown();await(bothReady);}},false);
            Future<it.medbook.dto.RimborsoResponse> a=pool.submit(()->new RimborsoRepository(f,CLOCK).richiediAtomically(booking,patient,MOTIVE));
            Future<it.medbook.dto.RimborsoResponse> b=pool.submit(()->new RimborsoRepository(f,CLOCK).richiediAtomically(secondBooking,patient,MOTIVE));
            var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
            assertEquals(payment,first.getIdPagamento());assertEquals(secondPayment,second.getIdPagamento());
            assertEquals(new BigDecimal("80.00"),first.getImporto());assertEquals(new BigDecimal("50.00"),second.getImporto());
            for(var item:List.of(first,second)){var row=query("SELECT r.id_pagamento,r.importo,r.stato,p.id_prenotazione FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE r.id_rimborso=?",item.getIdRimborso()).getFirst();assertEquals(item.getIdPagamento(),((Number)row.get(0)).intValue());assertEquals(item.getImporto(),row.get(1));assertEquals("RICHIESTO",row.get(2));assertEquals(item.getIdPrenotazione(),((Number)row.get(3)).intValue());}
        } finally {
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
            execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",secondBooking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",secondBooking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",secondBooking);execute("DELETE FROM disponibilita WHERE id_disponibilita=?",secondSlot);
        }
    }

}
