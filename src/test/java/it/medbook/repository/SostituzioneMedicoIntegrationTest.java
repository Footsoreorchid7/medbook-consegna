package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.*;
import it.medbook.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static it.medbook.repository.PrenotazioneIndisponibilitaIntegrationTest.observeQueries;

/** Fixture isolate: nessun accesso al database applicativo o credenziale nel codice. */
class SostituzioneMedicoIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static class Fixture extends PrenotazioneIndisponibilitaIntegrationTest.Fixture {
        Fixture() throws Exception { super(); }
        int candidate() throws SQLException {
            int id = doctor(); execute("INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?)", id, prestazione); return id;
        }
        int reported(StatoPrenotazione state, LocalDateTime start) throws SQLException {
            int id = booking(state, start);
            execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?", id); return id;
        }
        int free(int doctor, LocalDateTime start, boolean configured) throws SQLException {
            int id = insert("INSERT INTO disponibilita (id_medico,data_ora_inizio,data_ora_fine,id_prestazione,id_stanza) VALUES (?,?,?,?,?)",
                    doctor, start, start.plusHours(1), configured ? prestazione : null, configured ? stanza : null);
            slots.add(id); return id;
        }
        Map<String,Object> row(int id) throws SQLException {
            try (Connection c=factory.getConnection(); PreparedStatement s=c.prepareStatement("SELECT * FROM prenotazione WHERE id_prenotazione=?")) {
                s.setInt(1,id); try(ResultSet r=s.executeQuery()) { assertTrue(r.next()); Map<String,Object> values=new TreeMap<>();
                    for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
                        String name=r.getMetaData().getColumnLabel(i);
                        values.put(name,name.startsWith("id_")||name.equals("gestita_da") ? r.getObject(i,Integer.class) : r.getObject(i));
                    } return values; }
            }
        }
        List<List<Object>> payments(int booking) throws SQLException {
            try(Connection c=factory.getConnection();PreparedStatement statement=c.prepareStatement("SELECT * FROM pagamento WHERE id_prenotazione=? ORDER BY id_pagamento")) {
                statement.setInt(1,booking);try(ResultSet rows=statement.executeQuery()) {
                    List<List<Object>> values=new ArrayList<>();while(rows.next()) {List<Object> row=new ArrayList<>();
                        for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getObject(i));values.add(row);}return values;
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"RICHIESTA,NONE","RICHIESTA,FALLITO","RICHIESTA,IN_ATTESA","RICHIESTA,PAGATO",
                "CONFERMATA,NONE","CONFERMATA,FALLITO","CONFERMATA,IN_ATTESA","CONFERMATA,PAGATO"})
    void replacementChangesOnlyAdministrativeAssignmentAndPreservesPayments(StatoPrenotazione state,String payment) throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(state,NOW.plusHours(1)), candidate=f.candidate();
            int unavailable=state==StatoPrenotazione.RICHIESTA?f.owner:f.effective;
            if(!payment.equals("NONE")) f.execute("INSERT INTO pagamento (id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES (?,'ONLINE',50,?,?,?)",id,payment,NOW,payment.equals("PAGATO")?NOW:null);
            Map<String,Object> before=f.row(id);List<List<Object>> paymentsBefore=f.payments(id);
            var result=f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,state,unavailable);
            assertEquals(id,result.getIdPrenotazione()); assertEquals(candidate,result.getIdMedicoEffettivo());
            assertEquals(StatoPrenotazione.CONFERMATA,result.getStato()); assertFalse(result.isIndisponibilitaSegnalata());
            assertEquals(f.operator,result.getIdOperatore()); assertEquals(NOW,result.getDataOraGestione());
            Map<String,Object> after=f.row(id);assertEquals(paymentsBefore,f.payments(id));
            for(String key:before.keySet()) if(!Set.of("stato","gestita_da","data_ora_gestione","id_medico_effettivo","indisponibilita_segnalata").contains(key)) assertEquals(before.get(key),after.get(key),key);
            assertEquals(candidate,after.get("id_medico_effettivo")); assertEquals(f.operator,after.get("gestita_da"));
            assertEquals(NOW,after.get("data_ora_gestione")); assertFalse(f.flag(id));
            try(Connection c=f.factory.getConnection();PreparedStatement s=c.prepareStatement("SELECT stato,importo FROM pagamento WHERE id_prenotazione=?")) {
                s.setInt(1,id);try(ResultSet r=s.executeQuery()) {if(payment.equals("NONE")) assertFalse(r.next()); else {assertTrue(r.next());assertEquals(payment,r.getString(1));assertEquals(new java.math.BigDecimal("50.00"),r.getBigDecimal(2));assertFalse(r.next());}}
            }
            assertTrue(f.repository.findAgenda(candidate,NOW.toLocalDate(),NOW.toLocalDate()).stream().anyMatch(r->r.getIdPrenotazione()==id));
            assertTrue(f.repository.findAgenda(unavailable,NOW.toLocalDate(),NOW.toLocalDate()).stream().noneMatch(r->r.getIdPrenotazione()==id));
            assertTrue(new DisponibilitaRepository(f.factory).findPrenotabili(f.owner,null,null,NOW).isEmpty());
            var patientView=f.repository.findByPaziente(f.patient,null,null,null,NOW).stream().filter(r->r.getIdPrenotazione()==id).findFirst().orElseThrow();
            assertEquals(f.owner,patientView.getIdMedicoTitolare());assertEquals(candidate,patientView.getIdMedicoEffettivo());
            assertEquals(new java.math.BigDecimal("50.00"),patientView.getPrezzo());
            assertTrue(f.repository.findRichieste(null,null,null,null).stream().noneMatch(r->r.getIdPrenotazione()==id));
            assertTrue(f.repository.findSegnalazioniPendenti().stream().noneMatch(r->r.getIdPrenotazione()==id));
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,state,unavailable));
            assertEquals(after,f.row(id));
        }
    }

    @Test void newReportAllowsReturnToOwnerExcludingOnlyExactSlot() throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)), candidate=f.candidate();
            f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner);
            f.repository.segnalaIndisponibilitaAtomically(candidate,List.of(id));
            f.repository.sostituisciMedicoAtomically(id,f.operator,f.owner,StatoPrenotazione.CONFERMATA,candidate);
            assertEquals(f.owner,f.row(id).get("id_medico_effettivo"));assertFalse(f.flag(id));
            f.repository.segnalaIndisponibilitaAtomically(f.owner,List.of(id));
            f.free(candidate,NOW.plusMinutes(90),false);
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.CONFERMATA,f.owner));
            assertTrue(f.flag(id));
        }
    }

    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"RIFIUTATA","ANNULLATA","EFFETTUATA","PAZIENTE_ASSENTE","NON_EROGATA"})
    void terminalBookingsCannotBeReplaced(StatoPrenotazione state) throws Exception {
        try(Fixture f=new Fixture()) {int id=f.reported(state,NOW.plusHours(1)),candidate=f.candidate();var before=f.row(id);
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.CONFERMATA,f.effective));assertEquals(before,f.row(id));}
    }

    @ParameterizedTest @ValueSource(strings={"past","boundary","unconfigured","partial","duration","requestEffective","requestManaged","confirmedUnmanaged","confirmedNoEffective","noFlag","wrongState","wrongUnavailable","sameDoctor","notEnabled"})
    void invalidBookingOrCandidatePreservesEveryField(String condition) throws Exception {
        try(Fixture f=new Fixture()) {
            boolean confirmed=condition.startsWith("confirmed");StatoPrenotazione state=confirmed?StatoPrenotazione.CONFERMATA:StatoPrenotazione.RICHIESTA;
            int id=f.reported(state,NOW.plusHours(1)), candidate=f.candidate(); int slot=f.slots.getFirst();
            switch(condition) {
                case "past","boundary" -> f.execute("UPDATE disponibilita SET data_ora_inizio=?,data_ora_fine=? WHERE id_disponibilita=?",condition.equals("past")?NOW.minusHours(1):NOW,condition.equals("past")?NOW:NOW.plusHours(1),slot);
                case "unconfigured" -> f.execute("UPDATE disponibilita SET id_prestazione=NULL,id_stanza=NULL WHERE id_disponibilita=?",slot);
                case "partial" -> f.execute("UPDATE disponibilita SET id_stanza=NULL WHERE id_disponibilita=?",slot);
                case "duration" -> f.execute("UPDATE disponibilita SET data_ora_fine=? WHERE id_disponibilita=?",NOW.plusMinutes(150),slot);
                case "requestEffective" -> f.execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",f.effective,id);
                case "requestManaged" -> f.execute("UPDATE prenotazione SET gestita_da=?,data_ora_gestione=? WHERE id_prenotazione=?",f.operator,NOW,id);
                case "confirmedUnmanaged" -> f.execute("UPDATE prenotazione SET gestita_da=NULL,data_ora_gestione=NULL WHERE id_prenotazione=?",id);
                case "confirmedNoEffective" -> f.execute("UPDATE prenotazione SET id_medico_effettivo=NULL WHERE id_prenotazione=?",id);
                case "noFlag" -> f.execute("UPDATE prenotazione SET indisponibilita_segnalata=FALSE WHERE id_prenotazione=?",id);
                case "notEnabled" -> f.execute("DELETE FROM medico_prestazione WHERE id_medico=?",candidate);
            }
            int expectedDoctor=condition.equals("wrongUnavailable")?candidate:confirmed?f.effective:f.owner;
            int substitute=condition.equals("sameDoctor")?expectedDoctor:candidate;
            StatoPrenotazione expected=condition.equals("wrongState")?StatoPrenotazione.CONFERMATA:state;
            var before=f.row(id);
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,substitute,expected,expectedDoctor));assertEquals(before,f.row(id));
        }
    }

    @Test void missingResourcesAndOperatorRoleHaveDedicatedErrors() throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)), candidate=f.candidate();var before=f.row(id);
            assertThrows(RisorsaNonTrovataException.class,()->f.repository.sostituisciMedicoAtomically(Integer.MAX_VALUE,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner));
            assertThrows(RisorsaNonTrovataException.class,()->f.repository.sostituisciMedicoAtomically(id,Integer.MAX_VALUE,candidate,StatoPrenotazione.RICHIESTA,f.owner));
            assertThrows(RisorsaNonTrovataException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,Integer.MAX_VALUE,StatoPrenotazione.RICHIESTA,f.owner));
            for(String role:Arrays.asList(null,"AMMINISTRATORE")) {f.execute("UPDATE utente SET ruolo_gestionale=? WHERE id_utente=?",role,f.operator);
                assertThrows(OperazioneNonAutorizzataException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner));}
            assertEquals(before,f.row(id));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void ownAvailabilityBlocksEvenWhenFreeOrUnconfigured(boolean configured) throws Exception {
        try(Fixture f=new Fixture()) {int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)), candidate=f.candidate();f.free(candidate,NOW.plusMinutes(90),configured);var before=f.row(id);
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner)); assertEquals(before,f.row(id));}
    }

    @ParameterizedTest @EnumSource(StatoPrenotazione.class)
    void effectiveAppointmentOnAnotherOwnersSlotHasCorrectBlockingStates(StatoPrenotazione state) throws Exception {
        try(Fixture f=new Fixture()) {f.booking(state,NOW.plusMinutes(90));int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1));
            if(Set.of(StatoPrenotazione.CONFERMATA,StatoPrenotazione.EFFETTUATA,StatoPrenotazione.PAZIENTE_ASSENTE,StatoPrenotazione.NON_EROGATA).contains(state)) {
                assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(id,f.operator,f.effective,StatoPrenotazione.RICHIESTA,f.owner));assertTrue(f.flag(id));
            } else assertEquals(f.effective,f.repository.sostituisciMedicoAtomically(id,f.operator,f.effective,StatoPrenotazione.RICHIESTA,f.owner).getIdMedicoEffettivo());
        }
    }

    @ParameterizedTest @ValueSource(ints={0,2})
    void adjacentAppointmentsAndAvailabilityAreAllowed(int offset) throws Exception {
        try(Fixture f=new Fixture()) {int candidate=f.candidate();f.free(candidate,NOW.plusHours(offset),false);int other=f.booking(StatoPrenotazione.CONFERMATA,NOW.plusHours(offset));
            f.execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",candidate,other);
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1));
            assertEquals(candidate,f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner).getIdMedicoEffettivo());}
    }

    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"CONFERMATA","EFFETTUATA","PAZIENTE_ASSENTE","NON_EROGATA"})
    void uc19CannotCreateOrMoveAvailabilityOverAnAssignedForeignSlot(StatoPrenotazione state) throws Exception {
        try(Fixture f=new Fixture()) {f.booking(state,NOW.plusHours(1));DisponibilitaRepository repository=new DisponibilitaRepository(f.factory,CLOCK);
            Disponibilita a=slot(NOW.plusHours(1)),b=slot(NOW.plusHours(2));
            assertThrows(IllegalArgumentException.class,()->repository.saveAllAtomically(f.effective,List.of(a,b)));
            assertTrue(repository.findByMedico(f.effective).isEmpty());int free=f.free(f.effective,NOW.plusHours(4),false);
            assertThrows(ConflittoDisponibilitaException.class,()->repository.modificaOrarioAtomically(free,f.effective,NOW.plusHours(1),NOW.plusHours(2)));
            assertEquals(NOW.plusHours(4),repository.findById(free).orElseThrow().getDataOraInizio());}
    }

    private static Disponibilita slot(LocalDateTime start) {Disponibilita d=new Disponibilita();d.setDataOraInizio(start);d.setDataOraFine(start.plusHours(1));return d;}

    @Test void twoOrdinaryConfirmationsOnLegacyOverlappingSlotsHaveOneWinner() throws Exception {
        try(Fixture f=new Fixture()) {
            int a=f.booking(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),b=f.booking(StatoPrenotazione.RICHIESTA,NOW.plusMinutes(90));
            List<Throwable> outcomes=race(()->f.repository.confermaAtomically(a,f.operator),()->f.repository.confermaAtomically(b,f.operator));
            oneWinner(outcomes);assertEquals(1,List.of(a,b).stream().filter(id->{try{return f.row(id).get("stato").equals("CONFERMATA");}catch(SQLException e){throw new RuntimeException(e);}}).count());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void twoReplacementsOnSameCandidateHaveOneWinner(boolean reverseTimes) throws Exception {
        try(Fixture f=new Fixture()) {
            int a=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusMinutes(reverseTimes?90:60));
            int b=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusMinutes(reverseTimes?60:90)),candidate=f.candidate();
            f.execute("UPDATE disponibilita SET id_medico=? WHERE id_disponibilita=?",f.effective,f.slots.getLast());
            List<Throwable> outcomes=race(()->f.repository.sostituisciMedicoAtomically(a,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner),
                    ()->f.repository.sostituisciMedicoAtomically(b,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.effective));
            oneWinner(outcomes);assertNotEquals(f.flag(a),f.flag(b));
        }
    }

    @Test void ordinaryConfirmationAndReplacementSerializeOnAssignedDoctor() throws Exception {
        try(Fixture f=new Fixture()) {
            int candidate=f.candidate();int replacing=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1));
            int confirming=f.booking(StatoPrenotazione.RICHIESTA,NOW.plusMinutes(90));
            f.execute("UPDATE disponibilita SET id_medico=? WHERE id_disponibilita=?",candidate,f.slots.getLast());
            List<Throwable> outcomes=race(()->f.repository.sostituisciMedicoAtomically(replacing,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner),
                    ()->f.repository.confermaAtomically(confirming,f.operator));
            assertNull(outcomes.get(1));assertInstanceOf(ConflittoPrenotazioneException.class,outcomes.getFirst());
            assertEquals("CONFERMATA",f.row(confirming).get("stato"));assertTrue(f.flag(replacing));
        }
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void availabilityMutationAndReplacementRespectMutexInBothOrders(boolean edit,boolean mutationFirst) throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),candidate=f.candidate();
            int free=edit?f.free(candidate,NOW.plusHours(4),false):0;
            CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),waiting=new CountDownLatch(1);
            ConnectionFactory first=observeQueries(f.factory,(sql,param,after)->{if(isDoctorMutex(sql)&&param==candidate&&after){held.countDown();await(release);}});
            ConnectionFactory second=observeQueries(f.factory,(sql,param,after)->{if(isDoctorMutex(sql)&&param==candidate&&!after)waiting.countDown();});
            Callable<?> mutation=()->{
                DisponibilitaRepository repository=new DisponibilitaRepository(mutationFirst?first:second,CLOCK);
                if(edit) return repository.modificaOrarioAtomically(free,candidate,NOW.plusHours(1),NOW.plusHours(2));
                var saved=repository.saveAllAtomically(candidate,List.of(slot(NOW.plusHours(1)),slot(NOW.plusHours(2))));
                synchronized(f.slots) {for(var d:saved)f.slots.add(d.getIdDisponibilita());}return saved;
            };
            Callable<?> replacement=()->new PrenotazioneRepository(mutationFirst?second:first,CLOCK).sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner);
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                Future<Throwable> leading=workers.submit(()->capture(mutationFirst?mutation:replacement));await(held);
                Future<Throwable> following=workers.submit(()->capture(mutationFirst?replacement:mutation));await(waiting);release.countDown();
                assertNull(leading.get(15,TimeUnit.SECONDS));Throwable loser=following.get(15,TimeUnit.SECONDS);rejectSqlDeadlock(loser);
                Class<? extends RuntimeException> expected = mutationFirst ? ConflittoPrenotazioneException.class : edit ? ConflittoDisponibilitaException.class : IllegalArgumentException.class;
                assertInstanceOf(expected,loser);
                assertEquals(mutationFirst,f.flag(id));
            } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void multipleReportsAndReplacementPreserveAtomicGroup(boolean reverse) throws Exception {
        try(Fixture f=new Fixture()) {
            int a=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),b=f.booking(StatoPrenotazione.RICHIESTA,NOW.plusHours(2)),candidate=f.candidate();
            List<Throwable> outcomes=race(()->f.repository.segnalaIndisponibilitaAtomically(f.owner,reverse?List.of(b,a):List.of(a,b)),
                    ()->f.repository.sostituisciMedicoAtomically(a,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner));
            assertNull(outcomes.get(1));Throwable report=outcomes.getFirst();rejectSqlDeadlock(report);
            if(report!=null)assertInstanceOf(RisorsaNonTrovataException.class,report);
            assertFalse(f.flag(a));assertEquals(report==null,f.flag(b));assertEquals(candidate,f.row(a).get("id_medico_effettivo"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"reject","deskCancel","patientCancel"})
    void terminalDeskOrPatientOperationAndReplacementHaveOneWinner(String operation) throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),candidate=f.candidate();
            List<Throwable> outcomes=race(()->f.repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner),()->{
                return switch(operation) {case "reject"->f.repository.rifiutaAtomically(id,f.operator);
                    case "deskCancel"->f.repository.annullaAtomically(id,f.operator,StatoPrenotazione.RICHIESTA);
                    default->f.repository.annullaPazienteAtomically(id,f.patient,StatoPrenotazione.RICHIESTA);};
            });oneWinner(outcomes);assertFalse(f.flag(id));
            boolean replaced=f.row(id).get("stato").equals("CONFERMATA");assertEquals(replaced?candidate:null,f.row(id).get("id_medico_effettivo"));
        }
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void localIsolationAndAutocommitRestoredOnSuccessAndRollback(boolean ordinary,boolean failCommit) throws Exception {
        try(Fixture f=new Fixture()) {
            int id=ordinary?f.booking(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)):f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1));
            int candidate=f.candidate();var before=f.row(id);AtomicBoolean closed=new AtomicBoolean();
            ConnectionFactory wrapped=lifecycle(f.factory,failCommit,false,closed);
            PrenotazioneRepository repository=new PrenotazioneRepository(wrapped,CLOCK);
            Callable<?> task=()->ordinary?repository.confermaAtomically(id,f.operator):repository.sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner);
            if(failCommit){assertInstanceOf(SQLException.class,capture(task));assertEquals(before,f.row(id));}else {assertNull(capture(task));assertFalse(f.flag(id));}
            assertTrue(closed.get());
        }
    }

    @Test void failedRestoreIsReportedAndConnectionIsClosed() throws Exception {
        try(Fixture f=new Fixture()) {int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),candidate=f.candidate();AtomicBoolean closed=new AtomicBoolean();
            SQLException error=assertThrows(SQLException.class,()->new PrenotazioneRepository(lifecycle(f.factory,false,true,closed),CLOCK)
                    .sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner));
            assertEquals("Ripristino simulato fallito",error.getMessage());assertTrue(closed.get());
            // Commit già riuscito: un errore successivo di ripristino non può annullarlo.
            assertFalse(f.flag(id));assertEquals(candidate,f.row(id).get("id_medico_effettivo"));}
    }

    @Test void clockIsReadOnceAfterSlotWaitAndBoundaryPreservesFlag() throws Exception {
        try(Fixture f=new Fixture();Connection held=f.factory.getConnection()) {
            int id=f.reported(StatoPrenotazione.RICHIESTA,NOW.plusHours(1)),candidate=f.candidate();var before=f.row(id);
            held.setAutoCommit(false);try(PreparedStatement s=held.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")){s.setInt(1,f.slots.getFirst());try(ResultSet r=s.executeQuery()){assertTrue(r.next());}}
            AtomicInteger reads=new AtomicInteger();AtomicReference<Instant> instant=new AtomicReference<>(CLOCK.instant());
            Clock mutable=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){reads.incrementAndGet();return instant.get();}};
            CountDownLatch attempting=new CountDownLatch(1);ConnectionFactory observed=observeQueries(f.factory,(sql,param,after)->{if(sql.contains("FROM disponibilita WHERE")&&sql.contains("FOR UPDATE")&&!after)attempting.countDown();});
            ExecutorService workers=Executors.newSingleThreadExecutor();try {
                Future<Throwable> result=workers.submit(()->capture(()->new PrenotazioneRepository(observed,mutable).sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.RICHIESTA,f.owner)));
                await(attempting);assertEquals(0,reads.get());instant.set(NOW.plusHours(1).toInstant(ZoneOffset.UTC));held.commit();
                assertInstanceOf(ConflittoPrenotazioneException.class,result.get(15,TimeUnit.SECONDS));assertEquals(1,reads.get());assertEquals(before,f.row(id));
            }finally{held.rollback();workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @ParameterizedTest @ValueSource(strings={"effective","slot","owner"})
    void staleDiscoveryConflictsWithoutLockingNewDoctor(String change) throws Exception {
        try(Fixture f=new Fixture()) {
            int id=f.reported(StatoPrenotazione.CONFERMATA,NOW.plusHours(1)),candidate=f.candidate(),other=f.candidate();
            int otherSlot=f.free(other,NOW.plusHours(3),true);AtomicBoolean changed=new AtomicBoolean();
            AtomicBoolean unexpectedDoctorLock=new AtomicBoolean();
            ConnectionFactory observed=observeQueries(f.factory,(sql,param,after)->{
                if(isDoctorMutex(sql)&&param==other)unexpectedDoctorLock.set(true);
                if(sql.contains("FROM prenotazione pr JOIN disponibilita")&&after&&changed.compareAndSet(false,true)) {
                    switch(change) {
                        case "effective" -> f.execute("UPDATE prenotazione SET id_medico_effettivo=? WHERE id_prenotazione=?",other,id);
                        case "slot" -> f.execute("UPDATE prenotazione SET id_disponibilita=? WHERE id_prenotazione=?",otherSlot,id);
                        default -> f.execute("UPDATE disponibilita SET id_medico=? WHERE id_disponibilita=?",other,f.slots.getFirst());
                    }
                }
            });
            assertThrows(ConflittoPrenotazioneException.class,()->new PrenotazioneRepository(observed,CLOCK)
                    .sostituisciMedicoAtomically(id,f.operator,candidate,StatoPrenotazione.CONFERMATA,f.effective));
            assertTrue(changed.get());assertFalse(unexpectedDoctorLock.get());assertTrue(f.flag(id));
            assertEquals(change.equals("effective")?other:f.effective,f.row(id).get("id_medico_effettivo"));
        }
    }

    @ParameterizedTest @EnumSource(value=StatoPrenotazione.class,names={"RIFIUTATA","ANNULLATA"})
    void uc19AllowsAvailabilityOverNonBlockingForeignAppointment(StatoPrenotazione state) throws Exception {
        try(Fixture f=new Fixture()) {
            f.booking(state,NOW.plusHours(1));var created=new DisponibilitaRepository(f.factory,CLOCK).saveAllAtomically(f.effective,List.of(slot(NOW.plusHours(1))));
            f.slots.add(created.getFirst().getIdDisponibilita());assertEquals(1,created.size());
        }
    }

    private static ConnectionFactory lifecycle(ConnectionFactory delegate,boolean failCommit,boolean failRestore,AtomicBoolean closed) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c=delegate.getConnection();c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    String name=m.getName();
                    if(name.equals("commit")){assertEquals(Connection.TRANSACTION_READ_COMMITTED,c.getTransactionIsolation());assertFalse(c.getAutoCommit());if(failCommit)throw new SQLException("Commit simulato fallito");}
                    if(name.equals("setTransactionIsolation")&&(Integer)a[0]==Connection.TRANSACTION_REPEATABLE_READ&&failRestore)throw new SQLException("Ripristino simulato fallito");
                    if(name.equals("close")){closed.set(true);if(!failRestore){assertTrue(c.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());}}
                    try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
    }
    private static boolean isDoctorMutex(String sql){return sql.contains("FROM medico WHERE")&&sql.contains("FOR UPDATE");}
    private static void await(CountDownLatch latch)throws InterruptedException{assertTrue(latch.await(10,TimeUnit.SECONDS),"Sincronizzazione non raggiunta");}
    private static Throwable capture(Callable<?> task){try{task.call();return null;}catch(Throwable e){return e;}}
    private static void rejectSqlDeadlock(Throwable error){if(error instanceof SQLException sql)assertFalse(sql.getErrorCode()==1213||sql.getErrorCode()==1205,"Deadlock o timeout non accettabile: "+sql);}
    private static void oneWinner(List<Throwable> outcomes){outcomes.forEach(SostituzioneMedicoIntegrationTest::rejectSqlDeadlock);assertEquals(1,outcomes.stream().filter(Objects::isNull).count());outcomes.stream().filter(Objects::nonNull).forEach(e->assertInstanceOf(ConflittoPrenotazioneException.class,e));}
    private static List<Throwable> race(Callable<?> a,Callable<?> b)throws Exception{
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Throwable>> futures=new ArrayList<>();for(Callable<?> task:List.of(a,b))futures.add(workers.submit(()->{await(start);return capture(task);}));start.countDown();
            List<Throwable> outcomes=new ArrayList<>();for(Future<Throwable> future:futures)outcomes.add(future.get(20,TimeUnit.SECONDS));outcomes.forEach(SostituzioneMedicoIntegrationTest::rejectSqlDeadlock);return outcomes;
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
