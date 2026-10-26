package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.exception.*;
import it.medbook.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MedicoAbilitazioniConcorrenzaIntegrationTest {
    static final int ROUNDS=3;
    @FunctionalInterface interface Action{void run()throws Exception;}
    static Throwable capture(Action action){try{action.run();return null;}catch(Throwable e){return e;}}
    static void cleanError(Throwable t){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());var pending=new ArrayDeque<Throwable>();if(t!=null)pending.add(t);
        while(!pending.isEmpty()){
            var x=pending.remove();if(!seen.add(x))continue;
            if(x instanceof SQLException s){assertNotEquals(1213,s.getErrorCode());assertNotEquals(1205,s.getErrorCode());if(s.getNextException()!=null)pending.add(s.getNextException());}
            if(x.getCause()!=null)pending.add(x.getCause());for(var suppressed:x.getSuppressed())pending.add(suppressed);
        }
    }
    static void await(CountDownLatch l)throws Exception{MedicoInattivoFlussiIntegrationTest.await(l);}
    static boolean medico(String sql,int id,int target){return id==target&&sql.contains("FROM medico WHERE")&&sql.contains("FOR ");}
    static Stream<Arguments> clinical(){return Stream.of("UC17","UC27","UC19_CREA","UC19_MODIFICA","UC19_ELIMINA").flatMap(op->Stream.of(Arguments.of(op,true),Arguments.of(op,false)));}
    @ParameterizedTest @MethodSource("clinical")
    void removalAndClinicalOperationBothOrders(String op,boolean removeFirst)throws Exception{
        for(int round=0;round<ROUNDS;round++)try(var f=new MedicoAbilitazioniIntegrationTest.F()){
            boolean uc19=op.startsWith("UC19");
            var start=MedicoInattivoFlussiIntegrationTest.futuro();int[] created={0};
            int target=op.equals("UC17")?f.effective:f.owner;
            int booking=op.equals("UC17")?f.booking(StatoPrenotazione.RICHIESTA,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(2)):0;
            int slot=(op.equals("UC27")||op.equals("UC19_MODIFICA")||op.equals("UC19_ELIMINA"))?MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,start,false):0;
            if(booking!=0)f.repository.segnalaIndisponibilitaAtomically(f.owner,List.of(booking));
            var before=booking!=0?f.snapshot(booking):MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
            var removal=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(medico(sql,id,target)){
                    if(after&&removeFirst){held.countDown();await(release);}if(!after&&!removeFirst)attempted.countDown();
                }
            });
            var clinical=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(medico(sql,id,target)){
                    if(after&&!removeFirst){held.countDown();await(release);}if(!after&&removeFirst)attempted.countDown();
                }
            });
            Action remove=()->new MedicoRepository(removal).rimuoviPrestazione(f.admin,target,f.prestazione);
            Action write=()->{
                if(op.equals("UC17"))new PrenotazioneRepository(clinical,MedicoInattivoFlussiIntegrationTest.CLOCK)
                        .sostituisciMedicoAtomically(booking,f.operator,f.effective,StatoPrenotazione.RICHIESTA,f.owner);
                else {
                    var slots=new DisponibilitaRepository(clinical,MedicoInattivoFlussiIntegrationTest.CLOCK);
                    switch(op) {
                        case "UC27"->slots.configuraAtomically(slot,f.prestazione,f.stanza);
                        case "UC19_CREA"->{var d=slots.save(target,start,start.plusHours(1));created[0]=d.getIdDisponibilita();f.slots.add(created[0]);}
                        case "UC19_MODIFICA"->slots.modificaOrarioAtomically(slot,target,start.plusHours(3),start.plusHours(4));
                        case "UC19_ELIMINA"->slots.eliminaAtomically(slot,target);
                        default->throw new AssertionError(op);
                    }
                }
            };
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                var first=pool.submit(()->capture(removeFirst?remove:write));await(held);
                var second=pool.submit(()->capture(removeFirst?write:remove));await(attempted);
                assertThrows(TimeoutException.class,()->second.get(200,TimeUnit.MILLISECONDS));release.countDown();
                Throwable a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);cleanError(a);cleanError(b);assertNull(a);
                Class<? extends RuntimeException> expected=removeFirst?(op.equals("UC17")?ConflittoPrenotazioneException.class:ConflittoConfigurazioneException.class):ConflittoMedicoException.class;
                if(uc19)assertNull(b);else assertInstanceOf(expected,b);
                assertEquals(uc19?0:(removeFirst?0:1),f.count(target,f.prestazione));
                if(uc19) {
                    var slots=new DisponibilitaRepository(f.factory);
                    if(op.equals("UC19_ELIMINA"))assertTrue(slots.findById(slot).isEmpty());
                    else {var d=slots.findById(op.equals("UC19_CREA")?created[0]:slot).orElseThrow();
                        assertNull(d.getPrestazione());assertNull(d.getStanza());
                        assertEquals(op.equals("UC19_CREA")?start:start.plusHours(3),d.getDataOraInizio());}
                }
                else if(removeFirst)assertEquals(before,booking!=0?f.snapshot(booking):MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot));
                else if(booking!=0){assertEquals("CONFERMATA",MedicoStatoIntegrationTest.bookingState(f,booking));assertFalse(f.flag(booking));}
                else{
                    var d=new DisponibilitaRepository(f.factory).findById(slot).orElseThrow();
                    assertEquals(f.prestazione,d.getPrestazione().getIdPrestazione());assertEquals(f.stanza,d.getStanza().getIdStanza());
                }
            }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
        }
    }
    static Stream<Arguments> administrative(){
        return Stream.of("ADD_REMOVE","F6","F5","DEMOTE").flatMap(op->Stream.of(Arguments.of(op,true),Arguments.of(op,false)));
    }
    @ParameterizedTest @MethodSource("administrative")
    void administrativeWritersSerializeOnAccountMutex(String op,boolean f7First)throws Exception{
        for(int round=0;round<ROUNDS;round++)try(var f=new MedicoAbilitazioniIntegrationTest.F()){
            int p=op.equals("ADD_REMOVE")?f.prestazione:f.prestazione();int target=f.effective;
            if(op.equals("ADD_REMOVE")&&f7First)f.execute("DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?",target,p);
            int demoter=f.user();f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",demoter);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
            ConnectionFactory firstFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(sql.contains("GET_LOCK")&&after){held.countDown();await(release);}
            });
            ConnectionFactory secondFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(sql.contains("GET_LOCK")&&!after)attempted.countDown();
            });
            String number="F7-"+UUID.randomUUID();
            Action first,second;
            if(op.equals("ADD_REMOVE")){
                first=()->{var r=new MedicoRepository(firstFactory);if(f7First)r.assegnaPrestazione(f.admin,target,p);else r.rimuoviPrestazione(f.admin,target,p);};
                second=()->{var r=new MedicoRepository(secondFactory);if(f7First)r.rimuoviPrestazione(f.admin,target,p);else r.assegnaPrestazione(f.admin,target,p);};
            }else{
                Action f7=()->new MedicoRepository(f7First?firstFactory:secondFactory).assegnaPrestazione(f.admin,target,p);
                Action other=()->{
                    var fac=f7First?secondFactory:firstFactory;
                    switch(op){
                        case "F6"->new MedicoRepository(fac).cambiaStatoMedico(f.admin,target,"INATTIVO");
                        case "F5"->new MedicoRepository(fac).aggiornaDatiProfessionali(f.admin,target,"Updated F7",number);
                        case "DEMOTE"->new UtenteRepository(fac).updateRuolo(demoter,f.admin,RuoloGestionale.OPERATORE_DESK);
                        default->throw new AssertionError(op);
                    }
                };
                first=f7First?f7:other;second=f7First?other:f7;
            }
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                var a=pool.submit(()->capture(first));await(held);var b=pool.submit(()->capture(second));await(attempted);
                assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));release.countDown();
                var x=a.get(10,TimeUnit.SECONDS);var y=b.get(10,TimeUnit.SECONDS);cleanError(x);cleanError(y);assertNull(x);
                if(op.equals("DEMOTE")&&!f7First){assertInstanceOf(OperazioneNonAutorizzataException.class,y);assertEquals(0,f.count(target,p));}
                else{assertNull(y);assertEquals(op.equals("ADD_REMOVE")?(f7First?0:1):1,f.count(target,p));}
                if(op.equals("F6"))assertEquals("INATTIVO",new MedicoRepository(f.factory).dettaglioPerAmministratore(f.admin,target).stato());
                if(op.equals("F5")){
                    var d=new MedicoRepository(f.factory).dettaglioPerAmministratore(f.admin,target);
                    assertEquals("Updated F7",d.specializzazione());assertEquals(number,d.numeroIscrizioneOrdine());
                }
            }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
        }
    }
    @org.junit.jupiter.api.Test void differentDoctorsSharePrestazioneWithoutFkLockInversion()throws Exception {
        for(int round=0;round<ROUNDS;round++)try(var f=new MedicoAbilitazioniIntegrationTest.F()) {
            int slot=MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,MedicoInattivoFlussiIntegrationTest.futuro(),false);
            var deleting=new CountDownLatch(1);var configuring=new CountDownLatch(1);
            var removeFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(after&&sql.contains("FROM medico_prestazione")&&sql.contains("FOR UPDATE")&&id==f.effective){deleting.countDown();await(configuring);}
            });
            var configFactory=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                if(after&&sql.contains("FROM medico_prestazione")&&sql.contains("FOR SHARE")&&id==f.owner){configuring.countDown();await(deleting);}
            });
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                var remove=pool.submit(()->capture(()->new MedicoRepository(removeFactory).rimuoviPrestazione(f.admin,f.effective,f.prestazione)));
                var config=pool.submit(()->capture(()->new DisponibilitaRepository(configFactory,MedicoInattivoFlussiIntegrationTest.CLOCK).configuraAtomically(slot,f.prestazione,f.stanza)));
                var a=remove.get(10,TimeUnit.SECONDS);var b=config.get(10,TimeUnit.SECONDS);cleanError(a);cleanError(b);assertNull(a);assertNull(b);
                assertEquals(0,f.count(f.effective,f.prestazione));assertEquals(1,f.count(f.owner,f.prestazione));
                var d=new DisponibilitaRepository(f.factory).findById(slot).orElseThrow();assertEquals(f.stanza,d.getStanza().getIdStanza());
            }finally{deleting.countDown();configuring.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
        }
    }
}
