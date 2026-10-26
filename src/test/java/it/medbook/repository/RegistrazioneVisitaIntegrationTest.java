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

class RegistrazioneVisitaIntegrationTest {
    ConnectionFactory factory; int desk,patient,doctor,slot,booking; final List<Integer> users=new ArrayList<>();
    final List<Integer> extraSlots=new ArrayList<>(),extraBookings=new ArrayList<>(),prestazioni=new ArrayList<>(),stanze=new ArrayList<>();
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
    int user(String role)throws SQLException {int id=insert("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale) VALUES ('FixtureUC21','UC21',?,'invalid-test-hash',?)",UUID.randomUUID()+"@example.invalid",role);users.add(id);return id;}
    int insert(String sql,Object... params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,params);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){assertTrue(r.next());return r.getInt(1);}}}
    void execute(String sql,Object...params)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement(sql)){bind(s,params);s.executeUpdate();}}
    static void bind(PreparedStatement s,Object[] params)throws SQLException{for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);}
    List<List<Object>> snapshot(String table)throws SQLException{assertTrue(Set.of("prenotazione","pagamento").contains(table));try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM "+table+" WHERE "+"id_prenotazione"+"=? ORDER BY 1")){s.setInt(1,booking);try(ResultSet r=s.executeQuery()){List<List<Object>> out=new ArrayList<>();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}return out;}}}
    @AfterEach void cleanup()throws Exception{if(factory==null)return; if(booking>0){execute("DELETE r FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento WHERE p.id_prenotazione=?",booking);execute("DELETE FROM pagamento WHERE id_prenotazione=?",booking);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",booking);}for(int id:extraBookings){execute("DELETE FROM pagamento WHERE id_prenotazione=?",id);execute("DELETE FROM prenotazione WHERE id_prenotazione=?",id);}if(slot>0)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",slot);for(int id:extraSlots)execute("DELETE FROM disponibilita WHERE id_disponibilita=?",id);for(int id:prestazioni){execute("DELETE FROM medico_prestazione WHERE id_prestazione=?",id);execute("DELETE FROM prestazione WHERE id_prestazione=?",id);}for(int id:stanze)execute("DELETE FROM stanza WHERE id_stanza=?",id);for(int id:users)execute("DELETE FROM utente WHERE id_utente=?",id);}
    static Object invoke(Object target,Method m,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    @FunctionalInterface interface Observer {void observe(String sql)throws SQLException;}
    ConnectionFactory observed(Observer observer,boolean commitFailure){return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
        @Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")&&commitFailure)throw new SQLException("synthetic commit failure");
            Object value=invoke(real,m,a);
            if(m.getName().equals("prepareStatement")){String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)value;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->{if(n.getName().startsWith("execute")){if(sql.contains("SET stato='EFFETTUATA'"))assertEquals(Connection.TRANSACTION_READ_COMMITTED,real.getTransactionIsolation());observer.observe(sql);}return invoke(statement,n,b);});}return value;});}
    };}

    static final LocalDateTime END=NOW.plusHours(2);
    static Clock at(LocalDateTime t){return Clock.fixed(t.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);}
    it.medbook.dto.RegistrazioneVisitaResponse visit(ConnectionFactory f,Clock c)throws SQLException{
        return new PrenotazioneRepository(f,c).registraVisitaAtomically(booking,doctor);
    }
    void visit()throws SQLException{visit(factory,at(END));}
    void assertOnlyStateChanged(List<List<Object>> before)throws SQLException{
        var after=snapshot("prenotazione");assertEquals(1,after.size());
        for(int i=0;i<before.getFirst().size();i++)if(i!=5)assertEquals(before.getFirst().get(i),after.getFirst().get(i),"column "+i);
        assertEquals("EFFETTUATA",after.getFirst().get(5));
    }
    int payment(StatoPagamento state)throws SQLException{
        return insert("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES (?,'ONLINE',80,?,?,?)",booking,state.name(),NOW,state==StatoPagamento.PAGATO?NOW:null);
    }
    @Test void effectiveDoctorAndOnlyStateUpdatedIncludingUnconfiguredSlot()throws Exception{
        var before=snapshot("prenotazione");var r=visit(factory,at(END));assertEquals(booking,r.getIdPrenotazione());assertEquals(StatoPrenotazione.EFFETTUATA,r.getStato());assertOnlyStateChanged(before);
        var after=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::visit);assertEquals(after,snapshot("prenotazione"));
        assertTrue(snapshot("pagamento").isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})void timeBoundary(int delta)throws Exception{
        var before=snapshot("prenotazione");if(delta<0){assertThrows(ConflittoPrenotazioneException.class,()->visit(factory,at(END.plusNanos(delta))));assertEquals(before,snapshot("prenotazione"));}
        else{visit(factory,at(END.plusNanos(delta)));assertOnlyStateChanged(before);}
    }
    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA"},mode=EnumSource.Mode.EXCLUDE)
    void otherStatesConflict(StatoPrenotazione state)throws Exception{
        execute("UPDATE prenotazione SET stato=? WHERE id_prenotazione=?",state.name(),booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::visit);assertEquals(before,snapshot("prenotazione"));
    }
    @ParameterizedTest @EnumSource(StatoPagamento.class)void onlyPendingBlocksAndPaymentsArePreserved(StatoPagamento state)throws Exception{
        payment(state);var before=snapshot("prenotazione");var payments=snapshot("pagamento");
        if(state==StatoPagamento.IN_ATTESA){assertThrows(ConflittoPrenotazioneException.class,this::visit);assertEquals(before,snapshot("prenotazione"));}else{visit();assertOnlyStateChanged(before);}
        assertEquals(payments,snapshot("pagamento"));
    }
    @Test void reportBlocksWithoutClearingIt()throws Exception{execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::visit);assertEquals(before,snapshot("prenotazione"));}
    @Test void incoherentManagementConflicts()throws Exception{execute("UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");assertThrows(ConflittoPrenotazioneException.class,this::visit);assertEquals(before,snapshot("prenotazione"));}
    @Test void missingUnassignedAndOtherDoctorAreHidden()throws Exception{
        var r=new PrenotazioneRepository(factory,at(END));int other=user(null);execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",other,"Sintetico",UUID.randomUUID().toString());
        assertThrows(RisorsaNonTrovataException.class,()->r.registraVisitaAtomically(Integer.MAX_VALUE,doctor));
        assertThrows(RisorsaNonTrovataException.class,()->r.registraVisitaAtomically(booking,other));
        execute("UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");assertThrows(RisorsaNonTrovataException.class,this::visit);assertEquals(before,snapshot("prenotazione"));
    }
    @Test void substitutedOwnerCannotRegisterButEffectiveCan()throws Exception{
        int substitute=user(null);execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",substitute,"Sintetico",UUID.randomUUID().toString());
        execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",substitute,booking);var before=snapshot("prenotazione");
        assertThrows(RisorsaNonTrovataException.class,this::visit);new PrenotazioneRepository(factory,at(END)).registraVisitaAtomically(booking,substitute);assertOnlyStateChanged(before);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void updateAndCommitFailureRollBack(boolean commit)throws Exception{
        var before=snapshot("prenotazione");var f=observed(sql->{if(!commit&&sql.contains("UPDATE prenotazione"))throw new SQLException("synthetic update failure");},commit);
        assertThrows(SQLException.class,()->visit(f,at(END)));assertEquals(before,snapshot("prenotazione"));assertTrue(snapshot("pagamento").isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})void jdbcStateRestoredAfterSuccessAndFailure(boolean fail)throws Exception{
        List<String> events=new ArrayList<>();ConnectionFactory traced=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){
            @Override public Connection getConnection()throws SQLException{Connection real=factory.getConnection();int original=real.getTransactionIsolation();return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(fail&&m.getName().equals("commit"))throw new SQLException("synthetic");Object result=invoke(real,m,a);
                if(m.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(a[0])){assertEquals(original,real.getTransactionIsolation());events.add("restored");}
                if(m.getName().equals("close")){assertTrue(real.isClosed());events.add("closed");}return result;});}
        };
        if(fail)assertThrows(SQLException.class,()->visit(traced,at(END)));else visit(traced,at(END));assertEquals(List.of("restored","closed"),events);
    }
    @Test void refundAndPaymentRemainExactlyUnchanged()throws Exception{
        int pay=payment(StatoPagamento.PAGATO);int refund=insert("INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione) VALUES (?,'RICHIESTO',80,?,'Fixture sintetica')",pay,NOW);
        List<Object> before=refundRow(refund);var payments=snapshot("pagamento");visit();assertEquals(before,refundRow(refund));assertEquals(payments,snapshot("pagamento"));
    }
    List<Object> refundRow(int id)throws SQLException{try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM rimborso WHERE id_rimborso=?")){s.setInt(1,id);try(ResultSet r=s.executeQuery()){assertTrue(r.next());List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));return row;}}}
    static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(10,TimeUnit.SECONDS))throw new SQLException("controlled wait timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
    @ParameterizedTest @ValueSource(strings={"visit-visit","visit-verify","verify-visit","visit-deskpay","deskpay-visit","visit-online","online-visit","visit-deskcancel","deskcancel-visit","visit-patientcancel","patientcancel-visit","visit-signal","signal-visit","visit-substitute","substitute-visit"})
    void controlledRaces(String mode)throws Exception{
        String[] actions=mode.split("-");int substitute=0;
        if(mode.contains("substitute")){substitute=user(null);execute("INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine) VALUES (?,?,?)",substitute,"Sintetico",UUID.randomUUID().toString());
            int prest=insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES ('Fixture UC21',60,80)");prestazioni.add(prest);
            int room=insert("INSERT INTO stanza (nome) VALUES (?)",UUID.randomUUID().toString());stanze.add(room);
            execute("INSERT INTO medico_prestazione VALUES (?,?)",doctor,prest);execute("INSERT INTO medico_prestazione VALUES (?,?)",substitute,prest);
            execute("UPDATE disponibilita SET id_prestazione=?,id_stanza=? WHERE id_disponibilita=?",prest,room,slot);
            if(actions[0].equals("substitute"))execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);
        }
        int replacement=substitute;CountDownLatch firstLocked=new CountDownLatch(1),secondAttempt=new CountDownLatch(1);
        Observer first=sql->{if(sql.contains("UPDATE prenotazione")||sql.contains("INSERT INTO pagamento")){firstLocked.countDown();await(secondAttempt);}};
        Observer second=sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))secondAttempt.countDown();};
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<String> a=pool.submit(()->action(observed(first,false),actions[0],true,replacement));await(firstLocked);
            Future<String> b=pool.submit(()->action(observed(second,false),actions[1],false,replacement));assertEquals("ok",a.get(15,TimeUnit.SECONDS));
            String expected=actions[0].equals("deskpay")||actions[0].equals("online")?"ok":actions[0].equals("substitute")?"hidden":"conflict";
            assertEquals(expected,b.get(15,TimeUnit.SECONDS));
            var row=snapshot("prenotazione").getFirst();String state=switch(actions[0]){case "verify"->"NON_EROGATA";case "deskcancel","patientcancel"->"ANNULLATA";case "signal","substitute"->"CONFERMATA";default->"EFFETTUATA";};
            assertEquals(state,row.get(5));assertEquals(actions[0].equals("signal"),row.get(6));
            assertEquals(actions[0].equals("deskpay")||actions[0].equals("online")?1:0,snapshot("pagamento").size());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    // Clock diversi rappresentano richieste prima dello slot e registrazione dopo la fine;
    // non rendono UC-17/20 o annullamenti validi alla fine dello slot.
    String action(ConnectionFactory f,String action,boolean first,int replacement)throws SQLException{
        try{
            PrenotazioneRepository repo=new PrenotazioneRepository(f,first?CLOCK:at(END));
            switch(action){
                case "visit"->visit(f,at(END));
                case "verify"->new PrenotazioneRepository(f,at(END)).verificaMancataErogazioneAtomically(booking,desk,StatoPrenotazione.CONFERMATA,StatoPrenotazione.NON_EROGATA);
                case "deskpay"->new PagamentoRepository(f,at(END)).registraInSedeAtomically(booking,desk,ModalitaPagamento.DESK_POS,new BigDecimal("80"));
                case "online"->new PagamentoRepository(f,at(END),new it.medbook.payment.GatewayPagamentoSimulato()).registraOnlineAtomically(booking,patient,new BigDecimal("80"));
                case "deskcancel"->repo.annullaAtomically(booking,desk,StatoPrenotazione.CONFERMATA);
                case "patientcancel"->repo.annullaPazienteAtomically(booking,patient,StatoPrenotazione.CONFERMATA);
                case "signal"->repo.segnalaIndisponibilitaAtomically(doctor,List.of(booking));
                case "substitute"->repo.sostituisciMedicoAtomically(booking,desk,replacement,StatoPrenotazione.CONFERMATA,doctor);
                default->throw new IllegalArgumentException();
            }return "ok";
        }catch(ConflittoPrenotazioneException e){return "conflict";}catch(RisorsaNonTrovataException e){return "hidden";}
        // SQL 1213/1205 non sono intercettati: fanno fallire il test.
    }
    @Test void distinctBookingsCanRegisterConcurrently()throws Exception{
        int otherSlot=insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine) VALUES (?,?,?)",doctor,NOW.minusHours(2),NOW.minusHours(1));extraSlots.add(otherSlot);
        int otherBooking=insert("INSERT INTO prenotazione (id_paziente,id_disponibilita,gestita_da,id_medico_effettivo,stato,data_ora_richiesta,data_ora_gestione,prezzo) VALUES (?,?,?,?,'CONFERMATA',?,?,80)",patient,otherSlot,desk,doctor,NOW.minusDays(1),NOW.minusHours(3));extraBookings.add(otherBooking);
        CountDownLatch both=new CountDownLatch(2);ConnectionFactory f=observed(sql->{if(sql.contains("UPDATE prenotazione")){both.countDown();await(both);}},false);
        ExecutorService pool=Executors.newFixedThreadPool(2);try{
            Future<?> a=pool.submit(()->new PrenotazioneRepository(f,at(END)).registraVisitaAtomically(booking,doctor));
            Future<?> b=pool.submit(()->new PrenotazioneRepository(f,at(END)).registraVisitaAtomically(otherBooking,doctor));
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            try(Connection c=factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT stato FROM prenotazione WHERE id_prenotazione IN (?,?)")){s.setInt(1,booking);s.setInt(2,otherBooking);try(ResultSet r=s.executeQuery()){int count=0;while(r.next()){assertEquals("EFFETTUATA",r.getString(1));count++;}assertEquals(2,count);}}
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void clockReadOnceAfterWaitingAndHttpMatchesState()throws Exception{
        CountDownLatch attempted=new CountDownLatch(1);var reads=new java.util.concurrent.atomic.AtomicInteger();var current=new java.util.concurrent.atomic.AtomicReference<>(END.minusNanos(1));
        Clock controlled=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return current.get().toInstant(ZoneOffset.UTC);}};
        ExecutorService pool=Executors.newSingleThreadExecutor();try(Connection held=factory.getConnection()){
            held.setAutoCommit(false);try(PreparedStatement s=held.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")){s.setInt(1,slot);try(ResultSet r=s.executeQuery()){assertTrue(r.next());}}
            var f=observed(sql->{if(sql.contains("FROM disponibilita")&&sql.contains("FOR UPDATE"))attempted.countDown();},false);
            var future=pool.submit(()->{var x=new it.medbook.support.AuthenticatedTestExchange("POST","/api/prenotazioni/"+booking+"/registra-visita","{}",doctor);new it.medbook.controller.PrenotazioneController(new it.medbook.service.PrenotazioneService(new PrenotazioneRepository(f,controlled)),new com.fasterxml.jackson.databind.ObjectMapper()).handle(x);return x;});
            await(attempted);assertEquals(0,reads.get());current.set(END.plusNanos(123));held.commit();
            var x=future.get(15,TimeUnit.SECONDS);assertEquals(200,x.exchange.status);assertTrue(x.exchange.closed);assertEquals(1,reads.get());assertEquals("EFFETTUATA",new com.fasterxml.jackson.databind.ObjectMapper().readTree(x.exchange.body()).get("stato").textValue());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void completedVisitIsVisibleAndStillOccupiesSlotAndEffectiveDoctor() throws Exception {
        int prest=insert("INSERT INTO prestazione (nome,durata,prezzo) VALUES ('Fixture UC21',60,80)");prestazioni.add(prest);
        int room=insert("INSERT INTO stanza (nome) VALUES (?)",UUID.randomUUID().toString());stanze.add(room);
        execute("INSERT INTO medico_prestazione VALUES (?,?)",doctor,prest);
        execute("UPDATE disponibilita SET id_prestazione=?,id_stanza=? WHERE id_disponibilita=?",prest,room,slot);
        visit();var repo=new PrenotazioneRepository(factory,at(END));
        var agenda=repo.findAgenda(doctor,NOW.toLocalDate(),NOW.toLocalDate());
        assertEquals(1,agenda.size());assertEquals(booking,agenda.getFirst().getIdPrenotazione());assertEquals(StatoPrenotazione.EFFETTUATA,agenda.getFirst().getStato());
        var appointments=repo.findByPaziente(patient,null,null,null,END);
        assertEquals(1,appointments.size());assertEquals(booking,appointments.getFirst().getIdPrenotazione());assertEquals(StatoPrenotazione.EFFETTUATA,appointments.getFirst().getStato());
        assertTrue(repo.findRichieste(null,null,null,null).isEmpty());
        assertTrue(new DisponibilitaRepository(factory).findPrenotabili(null,null,null,NOW).stream().noneMatch(d->d.getIdDisponibilita().equals(slot)));
        try(Connection c=factory.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement lock=c.prepareStatement("SELECT id_utente FROM medico WHERE id_utente=? FOR UPDATE")){
                lock.setInt(1,doctor);try(ResultSet rows=lock.executeQuery()){assertTrue(rows.next());}
            }
            assertTrue(ImpegniMedico.haAppuntamenti(c,doctor,NOW.plusHours(1),END,-1));c.commit();
        }
        assertThrows(ConflittoPrenotazioneException.class,()->new PrenotazioneRepository(factory,CLOCK).creaRichiestaAtomically(patient,slot));
    }
    @Test void priceZeroIsNotANewVisitPrecondition() throws Exception {
        execute("UPDATE prenotazione SET prezzo=0 WHERE id_prenotazione=?",booking);var before=snapshot("prenotazione");visit();assertOnlyStateChanged(before);
    }

}
