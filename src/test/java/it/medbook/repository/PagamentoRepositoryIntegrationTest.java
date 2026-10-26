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

class PagamentoRepositoryIntegrationTest {
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
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('Fixture','UC14',?,'invalid-test-hash',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int insert(String sql,Object... params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,params);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,params);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] params)throws SQLException{for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);}
    List<List<Object>> snapshot(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento").contains(table));try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM "+table+" WHERE "+"id_prenotazione"+"=? ORDER BY 1")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    PagamentoRepository repo(){return new PagamentoRepository(factory,CLOCK);}
    void pay()throws SQLException{repo().registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80.00"));}
    @AfterEach void cleanup()throws Exception{if(factory==null)return; if(booking>0){execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int id:users)execute("DELETE FROM utente WHERE id_utente=?",id);}
    @ParameterizedTest @EnumSource(value=ModalitaPagamento.class,names={"DESK_POS","DESK_CONTANTI"})void createsWithSnapshotAndPreservesBooking(ModalitaPagamento mode)throws Exception{var before=snapshot("prenotazione");var r=repo().registraInSedeAtomically(booking,desk,mode,new BigDecimal("80"));assertEquals(StatoPagamento.PAGATO,r.getStato());assertEquals(desk,r.getIdOperatore());assertEquals(NOW,r.getCreatoIl());assertEquals(NOW,r.getPagatoIl());assertEquals(before,snapshot("prenotazione"));assertEquals(1,snapshot("pagamento").size());var row=snapshot("pagamento").getFirst();assertEquals(mode.name(),row.get(3));assertNull(row.get(6));assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(1,snapshot("pagamento").size());}
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA"},mode=EnumSource.Mode.EXCLUDE)void otherStatesRejected(StatoPrenotazione state)throws Exception{execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state.name(),booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::pay);assertTrue(snapshot("pagamento").isEmpty());assertEquals(before,snapshot("prenotazione"));}
    @ParameterizedTest @EnumSource(StatoPagamento.class)void previousAttempts(StatoPagamento state)throws Exception{insert("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES (?,'ONLINE',80,?,?,?)",booking,state.name(),NOW,state==StatoPagamento.PAGATO?NOW:null);var before=snapshot("pagamento");if(state==StatoPagamento.FALLITO){pay();assertEquals(2,snapshot("pagamento").size());assertEquals(before.getFirst(),snapshot("pagamento").getFirst());}else{assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(before,snapshot("pagamento"));}}
    @ParameterizedTest @ValueSource(strings={"flag","zero","price","management","effective"})void conflictsLeaveAllDataUnchanged(String kind)throws Exception{switch(kind){case "flag"->execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);case "zero"->execute("UPDATE prenotazione SET prezzo=0 WHERE id_prenotazione=?",booking);case "price"->execute("UPDATE prenotazione SET prezzo=81 WHERE id_prenotazione=?",booking);case "management"->execute("UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?",booking);case "effective"->execute("UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?",booking);}var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::pay);assertEquals(before,snapshot("prenotazione"));assertTrue(snapshot("pagamento").isEmpty());}
    @Test void currentCatalogPriceDoesNotChangeHistoricalAmount() throws Exception {
        int prestation=insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES ('Fixture UC14',60,99)");
        try {
            execute("INSERT INTO medico_prestazione VALUES (?,?)",doctor,prestation);
            execute("UPDATE disponibilita SET id_prestazione=? WHERE id_disponibilita=?",prestation,slot);
            var result=repo().registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80.00"));
            assertEquals(0,new BigDecimal("80.00").compareTo(result.getImporto()));
            assertEquals(0,new BigDecimal("80.00").compareTo((BigDecimal)snapshot("pagamento").getFirst().get(4)));
        } finally {
            execute("UPDATE disponibilita SET id_prestazione=NULL WHERE id_disponibilita=?",slot);
            execute("DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?",doctor,prestation);
            execute("DELETE FROM prestazione WHERE id_prestazione=?",prestation);
        }
    }
    @Test void pastConfirmedAllowed()throws Exception{execute("UPDATE disponibilita SET data_ora_inizio=?,data_ora_fine=? WHERE id_disponibilita=?",NOW.minusHours(2),NOW.minusHours(1),slot);pay();assertEquals(1,snapshot("pagamento").size());}
    @Test void missingAndWrongRole()throws Exception{assertThrows(RisorsaNonTrovataException.class,()->repo().registraInSedeAtomically(Integer.MAX_VALUE,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80")));assertThrows(RisorsaNonTrovataException.class,()->repo().registraInSedeAtomically(booking,Integer.MAX_VALUE,ModalitaPagamento.DESK_POS,new BigDecimal("80")));for(String role:new String[]{null,"AMMINISTRATORE"}){execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",role,desk);assertThrows(OperazioneNonAutorizzataException.class,this::pay);}assertTrue(snapshot("pagamento").isEmpty());}
    static Object invoke(Object target,Method m,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    @FunctionalInterface interface Observer {void observe(String sql)throws SQLException;}
    ConnectionFactory observed(Observer observer,boolean commitFailure){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
        @Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")&&commitFailure)throw new SQLException("synthetic commit failure");
            Object value=invoke(real,m,a);
            if(m.getName().equals("prepareStatement")){String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{if(n.getName().startsWith("execute")){if(sql.contains("INSERT INTO pagamento"))assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());observer.observe(sql);}return invoke(statement,n,b);});}return value;});}
    };}
    @ParameterizedTest @ValueSource(booleans={false,true})void sqlFailureRollsBack(boolean commit)throws Exception{var f=observed(sql->{if(!commit&&sql.contains("INSERT INTO pagamento"))throw new SQLException("synthetic insert failure");},commit);assertThrows(SQLException.class,()->new PagamentoRepository(f,CLOCK).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80")));assertTrue(snapshot("pagamento").isEmpty());}
    @Test void readsClockOnlyAfterWaitingOnLocks() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        var mutable = new it.medbook.support.MutableClock();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        Clock controlled = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { reads.incrementAndGet(); return mutable.instant(); }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = factory.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                statement.setInt(1,slot); try (ResultSet rows=statement.executeQuery()) { assertTrue(rows.next()); }
            }
            Future<it.medbook.dto.PagamentoResponse> future = pool.submit(() -> new PagamentoRepository(
                    observed(sql -> { if(sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) attempted.countDown(); },false),controlled)
                    .registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80")));
            await(attempted); assertEquals(0,reads.get());
            mutable.advance(Duration.ofHours(2));
            LocalDateTime expected = LocalDateTime.ofInstant(mutable.instant(),ZoneOffset.UTC);
            holder.commit();
            assertEquals(expected,future.get(15,TimeUnit.SECONDS).getPagatoIl()); assertEquals(1,reads.get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void restoresAutocommitAndClosesConnection(boolean fail) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean restored=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        ConnectionFactory traced=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection real=factory.getConnection();
                int originalIsolation=real.getTransactionIsolation();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    if(fail && m.getName().equals("commit"))throw new SQLException("synthetic failure");
                    Object value=invoke(real,m,a);
                    if(m.getName().equals("setAutoCommit") && Boolean.TRUE.equals(a[0])){assertEquals(originalIsolation,real.getTransactionIsolation());restored.set(true);}
                    if(m.getName().equals("close"))closed.set(true);
                    return value;
                });
            }
        };
        var repository=new PagamentoRepository(traced,CLOCK);
        if(fail)assertThrows(SQLException.class,()->repository.registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80")));
        else repository.registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));
        assertTrue(restored.get()); assertTrue(closed.get());
        assertEquals(fail?0:1,snapshot("pagamento").size());
    }

    static void await(CountDownLatch l)throws SQLException{try{if(!l.await(10,TimeUnit.SECONDS))throw new SQLException("controlled wait timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
    // Il primo scrittore resta sotto lock prima dell'INSERT/UPDATE; il secondo annuncia il tentativo sullo slot.
    @ParameterizedTest @ValueSource(strings={"pay-pay","pay-desk","desk-pay","pay-patient","patient-pay"})void controlledRaces(String mode)throws Exception{
        int secondDesk=mode.equals("pay-pay")?user("OPERATORE_DESK"):desk;
        CountDownLatch firstLocked=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        boolean firstPay=mode.startsWith("pay");
        Observer first=sql->{if((firstPay&&sql.contains("INSERT INTO pagamento"))||(!firstPay&&sql.contains("UPDATE prenotazione"))){firstLocked.countDown();await(secondAttempt);}};
        Observer second=sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();};
        ConnectionFactory f1=observed(first,false),f2=observed(second,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Boolean> a=pool.submit(()->action(f1,firstPay?"pay":mode.startsWith("desk")?"desk":"patient"));
            await(firstLocked);
            String last=mode.substring(mode.indexOf('-')+1);
            Future<Boolean> b=pool.submit(()->action(f2,last,secondDesk));
            assertTrue(a.get(15,TimeUnit.SECONDS));assertFalse(b.get(15,TimeUnit.SECONDS));
            assertEquals(firstPay?1:0,snapshot("pagamento").size());
            if(firstPay)assertEquals(desk,((Number)snapshot("pagamento").getFirst().get(2)).intValue());
            assertEquals(firstPay?"CONFERMATA":"ANNULLATA",snapshot("prenotazione").getFirst().get(5));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void independentBookingsCanInsertPaymentsConcurrently() throws Exception {
        int otherSlot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.plusHours(3),NOW.plusHours(4));
        int otherBooking=0;
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            otherBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'CONFERMATA',?,?,80)",patient,otherSlot,desk,doctor,NOW.minusDays(1),NOW.minusHours(1));
            int second=otherBooking;
            CountDownLatch inserting=new CountDownLatch(2);
            var traced=observed(sql -> { if(sql.contains("INSERT INTO pagamento")){inserting.countDown();await(inserting);} },false);
            Future<?> a=pool.submit(()->{new PagamentoRepository(traced,CLOCK).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));return null;});
            Future<?> b=pool.submit(()->{new PagamentoRepository(traced,CLOCK).registraInSedeAtomically(second,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));return null;});
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            for (int id : List.of(booking, second)) {
                try (Connection c = factory.getConnection(); PreparedStatement statement = c.prepareStatement(
                        "SELECT id_prenotazione,stato,importo FROM pagamento WHERE id_prenotazione=?")) {
                    statement.setInt(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "Pagamento mancante per prenotazione " + id);
                        assertEquals(id, rows.getInt("id_prenotazione"));
                        assertEquals("PAGATO", rows.getString("stato"));
                        assertEquals(0, new BigDecimal("80.00").compareTo(rows.getBigDecimal("importo")));
                        assertFalse(rows.next(), "Pagamento duplicato per prenotazione " + id);
                    }
                }
            }
        } finally {
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
            if(otherBooking>0){execute("DELETE FROM pagamento WHERE id_prenotazione=?",otherBooking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",otherBooking);}
            execute("DELETE FROM disponibilita WHERE id_disponibilita=?",otherSlot);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void isolationRestorationFailureHasNoPartialPersistence(boolean commitFails) throws Exception {
        var before = snapshot("prenotazione");
        List<String> events = new ArrayList<>();
        ConnectionFactory failing = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection real = factory.getConnection();
                int originalIsolation = real.getTransactionIsolation();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        events.add("commit");
                        if (commitFails) { throw new SQLException("synthetic commit failure"); }
                    }
                    if (method.getName().equals("rollback")) { events.add("rollback"); }
                    if (method.getName().equals("setTransactionIsolation") && (int) args[0] == originalIsolation) {
                        events.add("isolation restore");
                        throw new SQLException("synthetic isolation restore failure");
                    }
                    if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                        events.add("autocommit true");
                    }
                    Object value = invoke(real, method, args);
                    if (method.getName().equals("close")) { assertTrue(real.isClosed()); events.add("close"); }
                    return value;
                });
            }
        };
        var error = assertThrows(SQLException.class, () -> new PagamentoRepository(failing, CLOCK)
                .registraInSedeAtomically(booking, desk, ModalitaPagamento.DESK_POS, new BigDecimal("80")));
        if (commitFails) {
            assertEquals("synthetic commit failure", error.getMessage());
            assertEquals(1, error.getSuppressed().length);
            assertEquals("synthetic isolation restore failure", error.getSuppressed()[0].getMessage());
            assertTrue(events.indexOf("rollback") < events.indexOf("isolation restore"));
            assertTrue(snapshot("pagamento").isEmpty());
        } else {
            assertEquals("synthetic isolation restore failure", error.getMessage());
            assertEquals(0, error.getSuppressed().length);
            assertFalse(events.contains("rollback"));
            var payments = snapshot("pagamento");
            assertEquals(1, payments.size());
            assertEquals(booking, ((Number) payments.getFirst().get(1)).intValue());
            assertEquals("PAGATO", payments.getFirst().get(5));
            assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) payments.getFirst().get(4)));
            assertThrows(ConflittoPrenotazioneException.class, this::pay);
            assertEquals(payments, snapshot("pagamento"));
        }
        assertFalse(events.contains("autocommit true"));
        assertEquals("close", events.getLast());
        assertEquals(before, snapshot("prenotazione"));
    }

    // Il primo scrittore detiene slot e prenotazione prima della mutazione;
    // il secondo tenta il lock dello slot prima che il primo possa proseguire e committare.
    @ParameterizedTest @ValueSource(booleans={false,true})
    void paymentVersusIndisponibilitaRespectsBothPrecedences(boolean signalFirst) throws Exception {
        var before = snapshot("prenotazione").getFirst();
        CountDownLatch firstLocked = new CountDownLatch(1), secondAttempt = new CountDownLatch(1);
        Observer first = sql -> {
            if ((signalFirst && sql.contains("UPDATE prenotazione SET indisponibilita_segnalata"))
                    || (!signalFirst && sql.contains("INSERT INTO pagamento"))) {
                firstLocked.countDown(); await(secondAttempt);
            }
        };
        Observer second = sql -> {
            if (sql.contains("FROM disponibilita") && sql.contains("FOR UPDATE")) { secondAttempt.countDown(); }
        };
        ConnectionFactory f1 = observed(first, false), f2 = observed(second, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> paymentOrSignal(f1, signalFirst));
            await(firstLocked);
            Future<Boolean> b = pool.submit(() -> paymentOrSignal(f2, !signalFirst));
            assertTrue(a.get(15, TimeUnit.SECONDS));
            assertEquals(!signalFirst, b.get(15, TimeUnit.SECONDS));
            var after = snapshot("prenotazione").getFirst();
            for (int column = 0; column < before.size(); column++) {
                if (column != 6) { assertEquals(before.get(column), after.get(column)); }
            }
            assertTrue((Boolean) after.get(6));
            var payments = snapshot("pagamento");
            if (signalFirst) { assertTrue(payments.isEmpty()); }
            else {
                assertEquals(1, payments.size());
                assertEquals(booking, ((Number) payments.getFirst().get(1)).intValue());
                assertEquals("PAGATO", payments.getFirst().get(5));
                assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) payments.getFirst().get(4)));
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    boolean paymentOrSignal(ConnectionFactory connectionFactory, boolean signal) throws SQLException {
        try {
            if (signal) { new PrenotazioneRepository(connectionFactory, CLOCK)
                    .segnalaIndisponibilitaAtomically(doctor, List.of(booking)); }
            else { new PagamentoRepository(connectionFactory, CLOCK)
                    .registraInSedeAtomically(booking, desk, ModalitaPagamento.DESK_POS, new BigDecimal("80")); }
            return true;
        } catch (ConflittoPrenotazioneException conflict) {
            if (signal) { throw conflict; }
            return false;
        }
    }
    boolean action(ConnectionFactory f,String action)throws SQLException{return action(f,action,desk);}
    boolean action(ConnectionFactory f,String action,int operator)throws SQLException{try{if(action.equals("pay"))new PagamentoRepository(f,CLOCK).registraInSedeAtomically(booking,operator,ModalitaPagamento.DESK_POS,new BigDecimal("80"));else if(action.equals("desk"))new PrenotazioneRepository(f,CLOCK).annullaAtomically(booking,desk,StatoPrenotazione.CONFERMATA);else new PrenotazioneRepository(f,CLOCK).annullaPazienteAtomically(booking,patient,StatoPrenotazione.CONFERMATA);return true;}catch(ConflittoPrenotazioneException expected){return false;}}
}
