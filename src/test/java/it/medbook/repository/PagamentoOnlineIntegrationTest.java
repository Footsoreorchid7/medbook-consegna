package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.*;
import it.medbook.model.*;
import it.medbook.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PagamentoOnlineIntegrationTest {
    ConnectionFactory factory; int desk,patient,doctor,slot,booking; final List<Integer> users=new ArrayList<>();
    static final LocalDateTime NOW=LocalDateTime.of(2030,1,1,8,0);
    static final Clock CLOCK=Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
    @BeforeEach void setup()throws Exception {
        assumeTrue(System.getenv("MEDBOOK_TEST_DB_SERVER_UUID")!=null && !System.getenv("MEDBOOK_TEST_DB_SERVER_UUID").isBlank(),"UUID isolato obbligatorio.");
        factory=TestDatabaseConnections.isolatedFactory();
        desk=user("OPERATORE_DESK");patient=user(null);doctor=user(null);
        execute("INSERT INTO paziente VALUES (?,?,?,NULL)",patient,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));
        execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",doctor,"Sintetico",UUID.randomUUID().toString());
        slot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.plusHours(1),NOW.plusHours(2));
        booking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'CONFERMATA',?,?,80)",patient,slot,desk,doctor,NOW.minusDays(1),NOW.minusHours(1));
    }
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('FixtureUC08','UC08',?,'invalid-test-hash',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int insert(String sql,Object... params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,params);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,params);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] params)throws SQLException{for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);}
    List<List<Object>> snapshot(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento").contains(table));try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM "+table+" WHERE "+"id_prenotazione"+"=? ORDER BY 1")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    PagamentoRepository repo(){return new PagamentoRepository(factory,CLOCK);}
    void pay()throws SQLException{repo().registraOnlineAtomically(booking,patient,new BigDecimal("80.00"));}
    @AfterEach void cleanup()throws Exception{if(factory==null)return; if(booking>0){execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",booking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int id:users)execute("DELETE FROM utente WHERE id_utente=?",id);}

    PagamentoRepository online(ConnectionFactory f,StatoPagamento outcome){return new PagamentoRepository(f,CLOCK,new GatewayPagamentoSimulato(outcome));}
    int attempt(ModalitaPagamento mode,StatoPagamento state,LocalDateTime created)throws SQLException {
        return insert("INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,creato_il,pagato_il) VALUES (?,?,?,?,?,?,?)",
                booking,mode==ModalitaPagamento.ONLINE?null:desk,mode.name(),new BigDecimal("80"),state.name(),created,state==StatoPagamento.PAGATO?created:null);
    }
    @ParameterizedTest @EnumSource(value=StatoPagamento.class,names={"PAGATO","FALLITO"})
    void terminalSimulationPersistsExactSnapshotAndNoPending(StatoPagamento state)throws Exception {
        var before=snapshot("prenotazione");AtomicInteger reads=new AtomicInteger();
        Clock fractional=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return CLOCK.instant().plusNanos(987654321);}};
        String[] supplied={null};var gateway=(GatewayPagamento)(id,amount)->{supplied[0]=id;assertEquals(0,new BigDecimal("80").compareTo(amount));return state;};
        var result=new PagamentoRepository(factory,fractional,gateway).registraOnlineAtomically(booking,patient,new BigDecimal("80.00"));
        assertEquals(1,reads.get());assertEquals(NOW,result.getCreatoIl());assertEquals(state,result.getStato());
        assertEquals(state==StatoPagamento.PAGATO?NOW:null,result.getPagatoIl());assertEquals(before,snapshot("prenotazione"));
        var row=snapshot("pagamento").getFirst();assertNull(row.get(2));assertEquals("ONLINE",row.get(3));assertEquals(state.name(),row.get(5));
        assertEquals(supplied[0],row.get(6));assertEquals("SIM-"+UUID.fromString(supplied[0].substring(4)),supplied[0]);
        assertEquals(result.getIdPagamento(),((Number)row.get(0)).intValue());assertEquals(NOW,row.get(7));assertEquals(result.getPagatoIl(),row.get(8));
        assertEquals(1,snapshot("pagamento").size());assertNotEquals("IN_ATTESA",row.get(5));
    }
    @Test void failedAttemptCanBeFollowedBySuccessAndHistoricalFailuresArePreserved()throws Exception {
        online(factory,StatoPagamento.FALLITO).registraOnlineAtomically(booking,patient,new BigDecimal("80"));
        var first=snapshot("pagamento").getFirst();pay();assertEquals(2,snapshot("pagamento").size());assertEquals(first,snapshot("pagamento").getFirst());
        assertNotEquals(first.get(6),snapshot("pagamento").getLast().get(6));assertThrows(ConflittoPrenotazioneException.class,this::pay);
    }
    @ParameterizedTest @EnumSource(ModalitaPagamento.class) void successfulOrPendingAttemptBlocksAnyNewOnlinePayment(ModalitaPagamento mode)throws Exception {
        attempt(mode,StatoPagamento.PAGATO,NOW);var before=snapshot("pagamento");assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(before,snapshot("pagamento"));
        execute("UPDATE pagamento SET stato='IN_ATTESA',pagato_il=NULL WHERE id_prenotazione=?",booking);
        before=snapshot("pagamento");assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(before,snapshot("pagamento"));
    }
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA"},mode=EnumSource.Mode.EXCLUDE)
    void nonConfirmedBookingsRejected(StatoPrenotazione state)throws Exception {
        execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state.name(),booking);
        assertThrows(ConflittoPrenotazioneException.class,this::pay);assertTrue(snapshot("pagamento").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"flag","zero","price","management","effective"}) void businessConflictsNeverInsert(String type)throws Exception {
        switch(type) {
            case "flag"->execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);
            case "zero"->execute("UPDATE prenotazione SET prezzo=0 WHERE id_prenotazione=?",booking);
            case "price"->execute("UPDATE prenotazione SET prezzo=81 WHERE id_prenotazione=?",booking);
            case "management"->execute("UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?",booking);
            case "effective"->execute("UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?",booking);
        }
        var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::pay);assertTrue(snapshot("pagamento").isEmpty());assertEquals(before,snapshot("prenotazione"));
    }
    @Test void ownershipIs404ForBothMethodsAndMissingResources()throws Exception {
        int other=user(null);execute("INSERT INTO paziente VALUES (?,?,?,NULL)",other,UUID.randomUUID().toString().replace("-","").substring(0,16),LocalDate.of(1990,1,1));
        for(int id:List.of(other,Integer.MAX_VALUE)) {
            assertThrows(RisorsaNonTrovataException.class,()->repo().registraOnlineAtomically(booking,id,new BigDecimal("80")));
            assertThrows(RisorsaNonTrovataException.class,()->repo().findByPrenotazionePaziente(booking,id));
        }
        assertThrows(RisorsaNonTrovataException.class,()->repo().registraOnlineAtomically(Integer.MAX_VALUE,patient,new BigDecimal("80")));
        assertThrows(RisorsaNonTrovataException.class,()->repo().findByPrenotazionePaziente(Integer.MAX_VALUE,patient));assertTrue(snapshot("pagamento").isEmpty());
    }
    @Test void pastConfirmedAndHistoricalPriceAreAllowed()throws Exception {
        int prestation=insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES ('FixtureUC08',60,99)");
        try {
            execute("INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?)",doctor,prestation);
            execute("UPDATE disponibilita SET id_prestazione=?,data_ora_inizio=?,data_ora_fine=? WHERE id_disponibilita=?",prestation,NOW.minusHours(2),NOW.minusHours(1),slot);
            var r=repo().registraOnlineAtomically(booking,patient,new BigDecimal("80"));assertEquals(0,new BigDecimal("80").compareTo(r.getImporto()));
        } finally {execute("UPDATE disponibilita SET id_prestazione=NULL WHERE id_disponibilita=?",slot);execute("DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?",doctor,prestation);execute("DELETE FROM prestazione WHERE id_prestazione=?",prestation);}
    }
    @Test void listIncludesAllModesStatesWithStableOrderAndNoMutation()throws Exception {
        assertTrue(repo().findByPrenotazionePaziente(booking,patient).isEmpty());
        int old=attempt(ModalitaPagamento.ONLINE,StatoPagamento.FALLITO,NOW.minusDays(1));
        int pos=attempt(ModalitaPagamento.DESK_POS,StatoPagamento.IN_ATTESA,NOW);
        int cash=attempt(ModalitaPagamento.DESK_CONTANTI,StatoPagamento.PAGATO,NOW);
        var before=snapshot("pagamento");var list=repo().findByPrenotazionePaziente(booking,patient);
        assertEquals(List.of(cash,pos,old),list.stream().map(r->r.getIdPagamento()).toList());assertEquals(before,snapshot("pagamento"));
        assertEquals(List.of(ModalitaPagamento.DESK_CONTANTI,ModalitaPagamento.DESK_POS,ModalitaPagamento.ONLINE),list.stream().map(r->r.getModalita()).toList());
    }
    static Object invoke(Object target,Method m,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    @FunctionalInterface interface Observer {void observe(String sql)throws SQLException;}
    ConnectionFactory observed(Observer observer,boolean commitFailure){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
        @Override public Connection getConnection()throws SQLException {
            Connection real=factory.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("commit")&&commitFailure)throw new SQLException("synthetic commit failure");
                Object value=invoke(real,m,a);
                if(m.getName().equals("prepareStatement")) {
                    String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{
                        if(n.getName().startsWith("execute")) {if(sql.contains("INSERT INTO pagamento"))assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());observer.observe(sql);}
                        return invoke(statement,n,b);
                    });
                }
                return value;
            });
        }
    };}
    @ParameterizedTest @ValueSource(strings={"insert","readback","gateway","commit"}) void technicalFailureRollsBackEverything(String stage)throws Exception {
        var before=snapshot("prenotazione");var f=observed(sql->{if((stage.equals("insert")&&sql.contains("INSERT INTO pagamento"))||(stage.equals("readback")&&sql.startsWith("SELECT * FROM pagamento")))throw new SQLException("synthetic failure");},stage.equals("commit"));
        GatewayPagamento gateway=stage.equals("gateway")?(id,a)->{throw new IllegalStateException("synthetic gateway");}:new GatewayPagamentoSimulato();
        org.junit.jupiter.api.function.Executable operation=()->new PagamentoRepository(f,CLOCK,gateway).registraOnlineAtomically(booking,patient,new BigDecimal("80"));
        if(stage.equals("gateway"))assertThrows(IllegalStateException.class,operation);else assertThrows(SQLException.class,operation);
        assertTrue(snapshot("pagamento").isEmpty());assertEquals(before,snapshot("prenotazione"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void restoresRealIsolationAndAutocommitAndCloses(boolean fail)throws Exception {
        List<String> events=new ArrayList<>();Connection real=factory.getConnection();int isolation=real.getTransactionIsolation();boolean auto=real.getAutoCommit();
        var f=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")&&fail)throw new SQLException("synthetic commit");
            if(m.getName().equals("setAutoCommit")||m.getName().equals("setTransactionIsolation"))events.add(m.getName()+":"+a[0]);
            if(m.getName().equals("close")){assertEquals(isolation,real.getTransactionIsolation());assertEquals(auto,real.getAutoCommit());}
            return invoke(real,m,a);
        });}};
        if(fail)assertThrows(SQLException.class,()->new PagamentoRepository(f,CLOCK).registraOnlineAtomically(booking,patient,new BigDecimal("80")));
        else new PagamentoRepository(f,CLOCK).registraOnlineAtomically(booking,patient,new BigDecimal("80"));
        assertTrue(real.isClosed());assertEquals(List.of("setTransactionIsolation:2","setAutoCommit:false","setTransactionIsolation:"+isolation,"setAutoCommit:"+auto),events);
        assertEquals(fail?0:1,snapshot("pagamento").size());
    }
    @Test void postCommitRestorationFailureIsAmbiguousButGetResolvesItWithoutDuplication()throws Exception {
        Connection real=factory.getConnection();int isolation=real.getTransactionIsolation();
        var f=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("setTransactionIsolation")&&(int)a[0]==isolation)throw new SQLException("synthetic restore");
            if(m.getName().equals("rollback"))fail("Rollback dopo commit non consentito");
            return invoke(real,m,a);
        });}};
        assertThrows(SQLException.class,()->new PagamentoRepository(f,CLOCK).registraOnlineAtomically(booking,patient,new BigDecimal("80")));
        assertTrue(real.isClosed());assertEquals(1,repo().findByPrenotazionePaziente(booking,patient).size());assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(1,snapshot("pagamento").size());
    }
    @Test void onlinePaymentCanFeedExistingUc15AndUc16WithoutChangingIncasso()throws Exception {
        pay();var payments=snapshot("pagamento");
        Clock end=Clock.fixed(NOW.plusHours(2).toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        new PrenotazioneRepository(factory,end).verificaMancataErogazioneAtomically(booking,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA);
        var refund=new RimborsoRepository(factory,end).richiediAtomically(booking,patient,"Prestazione non erogata");
        var decision=new RimborsoRepository(factory,end).decidiAtomically(refund.getIdRimborso(),desk,true);
        assertEquals(StatoRimborso.APPROVATO,decision.getStato());assertEquals(payments,snapshot("pagamento"));
    }
    static void await(CountDownLatch latch)throws SQLException {try{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("controlled timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
    boolean action(ConnectionFactory f,String name)throws SQLException {
        try {
            switch(name) {
                case "online"->new PagamentoRepository(f,CLOCK).registraOnlineAtomically(booking,patient,new BigDecimal("80"));
                case "desk"->new PagamentoRepository(f,CLOCK).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));
                case "patient"->new PrenotazioneRepository(f,CLOCK).annullaPazienteAtomically(booking,patient,StatoPrenotazione.CONFERMATA);
                case "cancel"->new PrenotazioneRepository(f,CLOCK).annullaAtomically(booking,desk,StatoPrenotazione.CONFERMATA);
                case "verify"->new PrenotazioneRepository(f,Clock.fixed(NOW.plusHours(2).toInstant(ZoneOffset.UTC),ZoneOffset.UTC)).verificaMancataErogazioneAtomically(booking,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA);
                case "signal"->new PrenotazioneRepository(f,CLOCK).segnalaIndisponibilitaAtomically(doctor,List.of(booking));
                default->throw new AssertionError(name);
            }
            return true;
        } catch(ConflittoPrenotazioneException e) {return false;}
    }
    @ParameterizedTest @ValueSource(strings={"online-online","online-desk","desk-online","online-patient","patient-online","online-cancel","cancel-online","online-verify","verify-online","online-signal","signal-online"})
    void controlledInterleavingsRespectExistingContracts(String pair)throws Exception {
        String[] names=pair.split("-");CountDownLatch firstLocked=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        Observer first=sql->{if(sql.contains("INSERT INTO pagamento")||sql.contains("UPDATE prenotazione")){firstLocked.countDown();await(secondAttempt);}};
        Observer second=sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();};
        var f1=observed(first,false);var f2=observed(second,false);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->action(f1,names[0]));await(firstLocked);var b=pool.submit(()->action(f2,names[1]));
            assertTrue(a.get(15,TimeUnit.SECONDS));
            boolean both=pair.equals("online-verify")||pair.equals("online-signal");assertEquals(both,b.get(15,TimeUnit.SECONDS));
            int expected=names[0].equals("online")||names[0].equals("desk")?1:0;assertEquals(expected,snapshot("pagamento").size());
            var list=repo().findByPrenotazionePaziente(booking,patient);assertEquals(expected,list.size());
            if(expected==1)assertEquals(StatoPagamento.PAGATO,list.getFirst().getStato());
            var book=snapshot("prenotazione").getFirst();
            String finalState=names[0].equals("patient")||names[0].equals("cancel")?"ANNULLATA":pair.contains("verify")?"NON_EROGATA":"CONFERMATA";
            assertEquals(finalState,book.get(5));assertEquals(pair.contains("signal"),book.get(6));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void distinctBookingsInsertConcurrentlyWithoutGapDeadlock()throws Exception {
        int otherSlot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.plusHours(3),NOW.plusHours(4));int otherBooking=0;var pool=Executors.newFixedThreadPool(2);
        try {
            otherBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'CONFERMATA',?,?,80)",patient,otherSlot,desk,doctor,NOW.minusDays(1),NOW.minusHours(1));int id=otherBooking;
            CountDownLatch inserts=new CountDownLatch(2);var f=observed(sql->{if(sql.contains("INSERT INTO pagamento")){inserts.countDown();await(inserts);}},false);
            var a=pool.submit(()->new PagamentoRepository(f,CLOCK).registraOnlineAtomically(booking,patient,new BigDecimal("80")));
            var b=pool.submit(()->new PagamentoRepository(f,CLOCK).registraOnlineAtomically(id,patient,new BigDecimal("80")));
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            for(int key:List.of(booking,id)) {var rows=repo().findByPrenotazionePaziente(key,patient);assertEquals(1,rows.size());assertEquals(key,rows.getFirst().getIdPrenotazione());assertEquals(StatoPagamento.PAGATO,rows.getFirst().getStato());assertEquals(0,new BigDecimal("80").compareTo(rows.getFirst().getImporto()));}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));if(otherBooking>0){execute("DELETE FROM pagamento WHERE id_prenotazione=?",otherBooking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",otherBooking);}execute("DELETE FROM disponibilita WHERE id_disponibilita=?",otherSlot);}
    }
    @Test void clockIsReadOnlyOnceAfterWaitingOnSlot()throws Exception {
        AtomicInteger reads=new AtomicInteger();Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return CLOCK.instant();}};
        CountDownLatch attempted=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try(Connection blocker=factory.getConnection()) {
            blocker.setAutoCommit(false);try(PreparedStatement s=blocker.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")){s.setInt(1,slot);try(ResultSet r=s.executeQuery()){assertTrue(r.next());}}
            var f=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))attempted.countDown();},false);
            var result=pool.submit(()->new PagamentoRepository(f,clock).registraOnlineAtomically(booking,patient,new BigDecimal("80")));
            await(attempted);assertEquals(0,reads.get());blocker.commit();assertEquals(NOW,result.get(15,TimeUnit.SECONDS).getCreatoIl());assertEquals(1,reads.get());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
