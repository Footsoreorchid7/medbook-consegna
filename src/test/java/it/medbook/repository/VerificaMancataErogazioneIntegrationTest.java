package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.*;
import it.medbook.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VerificaMancataErogazioneIntegrationTest {
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
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('FixtureUC15','UC15',?,'invalid-test-hash',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int insert(String sql,Object... params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,params);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,params);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] params)throws SQLException{for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);}
    List<List<Object>> snapshot(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento").contains(table));try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM "+table+" WHERE "+"id_prenotazione"+"=? ORDER BY 1")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    @AfterEach void cleanup()throws Exception{if(factory==null)return; if(booking>0){execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",booking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int id:users)execute("DELETE FROM utente WHERE id_utente=?",id);}
    static Object invoke(Object target,Method m,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    @FunctionalInterface interface Observer {void observe(String sql)throws SQLException;}
    ConnectionFactory observed(Observer observer,boolean commitFailure){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
        @Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")&&commitFailure)throw new SQLException("synthetic commit failure");
            Object value=invoke(real,m,a);
            if(m.getName().equals("prepareStatement")){String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{if(n.getName().startsWith("execute")){if(sql.contains("SET stato=?,gestita_da=?,data_ora_gestione=?"))assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());observer.observe(sql);}return invoke(statement,n,b);});}return value;});}
    };}

    static final LocalDateTime END=NOW.plusHours(2);
    static Clock at(LocalDateTime time){return Clock.fixed(time.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);}
    it.medbook.dto.VerificaMancataErogazioneResponse verify(ConnectionFactory f,Clock clock,StatoPrenotazione outcome)throws SQLException{
        return new PrenotazioneRepository(f,clock).verificaMancataErogazioneAtomically(booking,desk,StatoPrenotazione.CONFERMATA,outcome);
    }
    void verify()throws SQLException{verify(factory,at(END),StatoPrenotazione.NON_EROGATA);}
    int refundCount()throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?")){s.setInt(1,booking);try(ResultSet rows=s.executeQuery()){assertTrue(rows.next());return rows.getInt(1);}}}
    void assertOnlyManagementAndOutcomeChanged(List<Object> before,StatoPrenotazione outcome,int operator,LocalDateTime time)throws SQLException{
        var after=snapshot("prenotazione").getFirst();
        for(int col=0;col<before.size();col++){if(!Set.of(3,5,6,8).contains(col))assertEquals(before.get(col),after.get(col),"column "+col);}
        assertEquals(operator,((Number)after.get(3)).intValue());assertEquals(outcome.name(),after.get(5));assertEquals(false,after.get(6));
        assertEquals(time,after.get(8));assertEquals(0,refundCount());
    }
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"PAZIENTE_ASSENTE","NON_EROGATA"})
    void bothOutcomesPreserveDataAndRecordLastManagement(StatoPrenotazione outcome)throws Exception{
        int previous=desk;desk=user("OPERATORE_DESK");var before=snapshot("prenotazione").getFirst();
        var response=verify(factory,at(END),outcome);assertEquals(booking,response.getIdPrenotazione());assertEquals(outcome,response.getStato());assertEquals(desk,response.getIdOperatore());assertEquals(END,response.getDataOraGestione());
        assertOnlyManagementAndOutcomeChanged(before,outcome,desk,END);assertNotEquals(previous,desk);
        var after=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,()->verify(factory,at(END),outcome));assertEquals(after,snapshot("prenotazione"));
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})void endBoundary(int seconds)throws Exception{
        var before=snapshot("prenotazione");if(seconds<0){assertThrows(ConflittoPrenotazioneException.class,()->verify(factory,at(END.plusSeconds(seconds)),StatoPrenotazione.NON_EROGATA));assertEquals(before,snapshot("prenotazione"));}
        else assertEquals(END.plusSeconds(seconds),verify(factory,at(END.plusSeconds(seconds)),StatoPrenotazione.NON_EROGATA).getDataOraGestione());
    }
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA"},mode=EnumSource.Mode.EXCLUDE)
    void otherStatesRejected(StatoPrenotazione state)throws Exception{execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state.name(),booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::verify);assertEquals(before,snapshot("prenotazione"));}
    @ParameterizedTest @EnumSource(StatoPagamento.class)
    void paymentsPreservedAndOnlyPendingBlocks(StatoPagamento state)throws Exception{
        insert("INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,creato_il,pagato_il) VALUES (?,?,'DESK_POS',80,?,?,?)",booking,desk,state.name(),NOW,state==StatoPagamento.PAGATO?NOW:null);
        var payments=snapshot("pagamento");var before=snapshot("prenotazione");
        if(state==StatoPagamento.IN_ATTESA){assertThrows(ConflittoPrenotazioneException.class,this::verify);assertEquals(before,snapshot("prenotazione"));}else verify();
        assertEquals(payments,snapshot("pagamento"));assertEquals(0,refundCount());
    }
    @ParameterizedTest @ValueSource(strings={"NON_EROGATA","PAZIENTE_ASSENTE"})
    void reportResolvedOnlyForNonDelivery(String name)throws Exception{
        execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");
        var outcome=StatoPrenotazione.valueOf(name);
        if(outcome==StatoPrenotazione.PAZIENTE_ASSENTE){assertThrows(ConflittoPrenotazioneException.class,()->verify(factory,at(END),outcome));assertEquals(before,snapshot("prenotazione"));}
        else{verify();assertOnlyManagementAndOutcomeChanged(before.getFirst(),outcome,desk,END);}
    }
    @Test void pendingPaymentPreservesReport()throws Exception{execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);insert("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il) VALUES (?,'ONLINE',80,'IN_ATTESA',?)",booking,NOW);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::verify);assertEquals(before,snapshot("prenotazione"));}
    @ParameterizedTest @ValueSource(strings={"management","effective"})void incoherentConfirmationRejected(String kind)throws Exception{execute(kind.equals("management")?"UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?":"UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::verify);assertEquals(before,snapshot("prenotazione"));}
    @Test void missingAndWrongRole()throws Exception{
        var repository=new PrenotazioneRepository(factory,at(END));
        assertThrows(RisorsaNonTrovataException.class,()->repository.verificaMancataErogazioneAtomically(Integer.MAX_VALUE,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA));
        assertThrows(RisorsaNonTrovataException.class,()->repository.verificaMancataErogazioneAtomically(booking,Integer.MAX_VALUE,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA));
        for(String role:new String[]{null,"AMMINISTRATORE"}){execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",role,desk);assertThrows(OperazioneNonAutorizzataException.class,this::verify);}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void failureRollsBackWholeOutcomeAndReport(boolean commit)throws Exception{
        execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");
        var f=observed(sql->{if(!commit&&sql.contains("UPDATE prenotazione"))throw new SQLException("synthetic update failure");},commit);
        assertThrows(SQLException.class,()->verify(f,at(END),StatoPrenotazione.NON_EROGATA));assertEquals(before,snapshot("prenotazione"));assertTrue(snapshot("pagamento").isEmpty());assertEquals(0,refundCount());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void restoresIsolationAndAutocommit(boolean fail)throws Exception{
        List<String> events=new ArrayList<>();ConnectionFactory traced=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();int original=real.getTransactionIsolation();return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(fail&&m.getName().equals("commit"))throw new SQLException("synthetic");
                Object result=invoke(real,m,a);
                if(m.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(a[0])){assertEquals(original,real.getTransactionIsolation());events.add("restored");}
                if(m.getName().equals("close")){assertTrue(real.isClosed());events.add("closed");}return result;});}
        };
        if(fail)assertThrows(SQLException.class,()->verify(traced,at(END),StatoPrenotazione.NON_EROGATA));else verify(traced,at(END),StatoPrenotazione.NON_EROGATA);
        assertEquals(List.of("restored","closed"),events);
    }
    static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("controlled wait timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
    @ParameterizedTest @ValueSource(strings={"verify-verify","verify-pay","pay-verify","verify-desk","desk-verify","verify-patient","patient-verify","verify-signal","signal-verify"})
    void controlledRaces(String mode)throws Exception{
        String[] actions=mode.split("-");CountDownLatch firstLocked=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        Observer first=sql->{if((actions[0].equals("pay")&&sql.contains("INSERT INTO pagamento"))||(!actions[0].equals("pay")&&sql.contains("UPDATE prenotazione"))){firstLocked.countDown();await(secondAttempt);}};
        Observer second=sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();};
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Boolean> a=pool.submit(()->action(observed(first,false),actions[0],true));await(firstLocked);
            Future<Boolean> b=pool.submit(()->action(observed(second,false),actions[1],false));assertTrue(a.get(15,TimeUnit.SECONDS));
            boolean secondSucceeds=mode.equals("pay-verify")||mode.equals("signal-verify");assertEquals(secondSucceeds,b.get(15,TimeUnit.SECONDS));
            var row=snapshot("prenotazione").getFirst();assertEquals(actions[0].equals("desk")||actions[0].equals("patient")?"ANNULLATA":"NON_EROGATA",row.get(5));assertEquals(false,row.get(6));
            assertEquals(actions[0].equals("pay")?1:0,snapshot("pagamento").size());assertEquals(0,refundCount());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    // Per annullamento/segnalazione che precedono UC-15 il Clock è prima dello slot;
    // UC-15 usa la fine slot: rappresenta il tempo trascorso durante l'attesa, senza estendere UC-20.
    boolean action(ConnectionFactory f,String name,boolean first)throws SQLException{
        try{
            switch(name){
                case "verify"->verify(f,at(END),StatoPrenotazione.NON_EROGATA);
                case "pay"->new PagamentoRepository(f,at(END)).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));
                case "desk"->new PrenotazioneRepository(f,first?CLOCK:at(END)).annullaAtomically(booking,desk,StatoPrenotazione.CONFERMATA);
                case "patient"->new PrenotazioneRepository(f,first?CLOCK:at(END)).annullaPazienteAtomically(booking,patient,StatoPrenotazione.CONFERMATA);
                case "signal"->new PrenotazioneRepository(f,first?CLOCK:at(END)).segnalaIndisponibilitaAtomically(doctor,List.of(booking));
                default->throw new IllegalArgumentException();
            }return true;
        }catch(ConflittoPrenotazioneException conflict){
            if(!first) {
                if(name.equals("verify"))assertEquals("Prenotazione non verificabile o incoerente.",conflict.getMessage());
                if(name.equals("signal"))assertEquals("Prenotazione non segnalabile.",conflict.getMessage());
                if(name.equals("desk")||name.equals("patient"))assertEquals("Prenotazione non più annullabile.",conflict.getMessage());
            }
            return false;
        }
    }
    @Test void fractionalClockMatchesHttpAndDatabaseWithOneRead() throws Exception {
        LocalDateTime precise=END.plusNanos(987654321);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        Clock controlled=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return precise.toInstant(ZoneOffset.UTC);}};
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var controller=new it.medbook.controller.PrenotazioneController(new it.medbook.service.PrenotazioneService(new PrenotazioneRepository(factory,controlled)),mapper);
        var exchange=new it.medbook.support.TestHttpExchange("POST","/api/prenotazioni/"+booking+"/verifica-mancata-erogazione","{\"idOperatore\":"+desk+",\"statoAtteso\":\"CONFERMATA\",\"esito\":\"NON_EROGATA\"}");
        controller.handle(exchange);assertEquals(200,exchange.status);assertTrue(exchange.closed);assertEquals(1,reads.get());
        LocalDateTime stored=(LocalDateTime)snapshot("prenotazione").getFirst().get(8);
        assertEquals(END,stored);assertEquals(0,stored.getNano());
        assertEquals(stored,LocalDateTime.parse(mapper.readTree(exchange.body()).get("dataOraGestione").textValue()));
    }
    @Test void fractionalTimeBeforeEndIsRejectedWithoutSecondClockRead() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        Clock controlled=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return END.minusNanos(1).toInstant(ZoneOffset.UTC);}};
        var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,()->verify(factory,controlled,StatoPrenotazione.NON_EROGATA));
        assertEquals(1,reads.get());assertEquals(before,snapshot("prenotazione"));
    }
    @Test void concurrentDifferentOperatorsAndOutcomesPreserveWinner() throws Exception {
        int loser=user("OPERATORE_DESK");CountDownLatch winnerLocked=new CountDownLatch(1),loserAttempts=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var winnerFactory=observed(sql->{if(sql.contains("UPDATE prenotazione")){winnerLocked.countDown();await(loserAttempts);}},false);
            Future<?> winner=pool.submit(()->verify(winnerFactory,at(END.plusNanos(123)),StatoPrenotazione.PAZIENTE_ASSENTE));await(winnerLocked);
            var loserFactory=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))loserAttempts.countDown();},false);
            Future<ConflittoPrenotazioneException> losing=pool.submit(()->assertThrows(ConflittoPrenotazioneException.class,()->new PrenotazioneRepository(loserFactory,at(END.plusSeconds(1))).verificaMancataErogazioneAtomically(booking,loser,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA)));
            winner.get(15,TimeUnit.SECONDS);assertEquals("Prenotazione non verificabile o incoerente.",losing.get(15,TimeUnit.SECONDS).getMessage());
            var row=snapshot("prenotazione").getFirst();assertEquals("PAZIENTE_ASSENTE",row.get(5));assertEquals(desk,((Number)row.get(3)).intValue());assertEquals(END,row.get(8));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void existingRefundRemainsIdentical() throws Exception {
        // Una richiesta di rimborso preesistente non implica che UC-15 ne abbia accertato la causa.
        int payment=insert("INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,creato_il,pagato_il) VALUES (?,?,'DESK_POS',80,'PAGATO',?,?)",booking,desk,NOW,NOW);
        int refund=insert("INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione) VALUES (?,'RICHIESTO',80,?,'Fixture fittizia: richiesta da verificare')",payment,NOW);
        var before=refundRow(refund);var paymentBefore=snapshot("pagamento");verify();
        assertEquals(before,refundRow(refund));assertEquals(paymentBefore,snapshot("pagamento"));assertEquals(1,refundCount());
    }
    List<Object> refundRow(int id) throws SQLException {
        try(Connection c=factory.getConnection();PreparedStatement statement=c.prepareStatement("SELECT * FROM rimborso WHERE id_rimborso=?")){
            statement.setInt(1,id);try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());List<Object> result=new ArrayList<>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)result.add(rows.getObject(i));assertFalse(rows.next());return result;}
        }
    }
    @Test void uc20CannotSignalAfterEndEvenWithoutUc15()throws Exception{var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,()->new PrenotazioneRepository(factory,at(END)).segnalaIndisponibilitaAtomically(doctor,List.of(booking)));assertEquals(before,snapshot("prenotazione"));}
    @Test void clockReadOnceAfterSlotLockWait()throws Exception{
        CountDownLatch attempt=new CountDownLatch(1);var mutable=new it.medbook.support.MutableClock();java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        Clock controlled=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return mutable.instant();}};
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection holder=factory.getConnection()){
            holder.setAutoCommit(false);try(PreparedStatement st=holder.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")){st.setInt(1,slot);try(ResultSet rows=st.executeQuery()){assertTrue(rows.next());}}
            // Porta il Clock controllato a un secondo prima della fine slot.
            mutable.advance(Duration.between(mutable.instant(),END.minusSeconds(1).toInstant(ZoneOffset.UTC)));
            Future<?> result=pool.submit(()->verify(observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))attempt.countDown();},false),controlled,StatoPrenotazione.NON_EROGATA));
            await(attempt);assertEquals(0,reads.get());mutable.advance(Duration.ofSeconds(1));holder.commit();result.get(15,TimeUnit.SECONDS);assertEquals(1,reads.get());assertEquals(END,snapshot("prenotazione").getFirst().get(8));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void distinctBookingsProceedInParallel()throws Exception{
        int otherSlot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.minusHours(1),NOW);int otherBooking=0;ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            otherBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'CONFERMATA',?,?,80)",patient,otherSlot,desk,doctor,NOW.minusDays(1),NOW.minusHours(2));int second=otherBooking;CountDownLatch updating=new CountDownLatch(2);
            var traced=observed(sql->{if(sql.contains("UPDATE prenotazione")){updating.countDown();await(updating);}},false);
            Future<?> a=pool.submit(()->verify(traced,at(END),StatoPrenotazione.NON_EROGATA));Future<?> b=pool.submit(()->new PrenotazioneRepository(traced,at(END)).verificaMancataErogazioneAtomically(second,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.PAZIENTE_ASSENTE));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            for(int id:List.of(booking,second)){try(Connection c=factory.getConnection();PreparedStatement st=c.prepareStatement("SELECT stato,gestita_da FROM prenotazione WHERE id_prenotazione=?")){st.setInt(1,id);try(ResultSet rows=st.executeQuery()){assertTrue(rows.next());assertEquals(id==booking?"NON_EROGATA":"PAZIENTE_ASSENTE",rows.getString(1));assertEquals(desk,rows.getInt(2));}}}
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));if(otherBooking>0)execute("DELETE FROM prenotazione WHERE id_prenotazione=?",otherBooking);execute("DELETE FROM disponibilita WHERE id_disponibilita=?",otherSlot);}
    }
}
