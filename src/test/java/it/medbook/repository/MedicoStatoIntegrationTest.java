package it.medbook.repository;

import it.medbook.exception.*;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/* MySQL isolato, esclusivamente sulle righe della fixture. */
class MedicoStatoIntegrationTest {
    static int admin(PrenotazioneIndisponibilitaIntegrationTest.Fixture f) throws Exception {
        int id=f.user(); f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",id); return id;
    }
    @Test void transitionsPreserveProfilesSlotsAndAbilitiesAndSupportSelfAdministration() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int actor=admin(f);
            f.execute("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,'1990-01-01')",f.owner,UUID.randomUUID().toString().replace("-","").substring(0,16));
            int slot=MedicoInattivoFlussiIntegrationTest.slot(f,f.owner,MedicoInattivoFlussiIntegrationTest.futuro(),true);
            var user=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"utente","id_utente",f.owner);
            var patient=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"paziente","id_utente",f.owner);
            var availability=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot);
            var repo=new MedicoRepository(f.factory);
            for(String state:List.of("ATTIVO","INATTIVO","INATTIVO","ATTIVO")) assertEquals(state,repo.cambiaStatoMedico(actor,f.owner,state).stato());
            assertEquals(user,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"utente","id_utente",f.owner));
            assertEquals(patient,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"paziente","id_utente",f.owner));
            assertEquals(availability,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"disponibilita","id_disponibilita",slot));
            assertEquals(1,MedicoInattivoFlussiIntegrationTest.conta(f.factory,"SELECT COUNT(*) FROM medico_prestazione WHERE id_medico=?",f.owner));
            f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",f.owner);
            assertEquals("INATTIVO",repo.cambiaStatoMedico(f.owner,f.owner,"INATTIVO").stato());
            assertEquals("ATTIVO",repo.cambiaStatoMedico(f.owner,f.owner,"ATTIVO").stato());
        }
    }
    @ParameterizedTest @EnumSource(StatoPrenotazione.class)
    void allStatesAndAllDatesUseHolderForRequestsAndEffectiveForConfirmed(StatoPrenotazione state) throws Exception {
        for(int days:new int[]{-10,10}) try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int actor=admin(f), booking=f.booking(state,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(days));
            var snapshot=f.snapshot(booking); var repo=new MedicoRepository(f.factory);
            int blocked=state==StatoPrenotazione.RICHIESTA?f.owner:f.effective;
            if(state==StatoPrenotazione.RICHIESTA || state==StatoPrenotazione.CONFERMATA) {
                f.execute("UPDATE prenotazione SET indisponibilita_segnalata=TRUE WHERE id_prenotazione=?",booking);
                snapshot=f.snapshot(booking);
                assertThrows(ConflittoMedicoException.class,()->repo.cambiaStatoMedico(actor,blocked,"INATTIVO"));
                /* L'altro medico non è responsabile della prenotazione in questo stato. */
                assertEquals("INATTIVO",repo.cambiaStatoMedico(actor,blocked==f.owner?f.effective:f.owner,"INATTIVO").stato());
            } else assertEquals("INATTIVO",repo.cambiaStatoMedico(actor,blocked,"INATTIVO").stato());
            assertEquals(snapshot,f.snapshot(booking));
        }
    }
    @Test void paidConfirmedAndNonCanonicalBookingsConservativelyBlockWithoutWrites() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a=admin(f),b=f.booking(StatoPrenotazione.CONFERMATA,MedicoInattivoFlussiIntegrationTest.NOW.minusDays(3));
            f.execute("INSERT INTO pagamento(id_prenotazione,modalita,importo,stato,creato_il,pagato_il) VALUES(?,'ONLINE',50,'PAGATO',?,?)",b,MedicoInattivoFlussiIntegrationTest.NOW,MedicoInattivoFlussiIntegrationTest.NOW);
            var before=f.snapshot(b);var repo=new MedicoRepository(f.factory);
            assertThrows(ConflittoMedicoException.class,()->repo.cambiaStatoMedico(a,f.effective,"INATTIVO"));
            f.execute("UPDATE prenotazione SET stato='confermata' WHERE id_prenotazione=?",b);
            var error=assertThrows(ConflittoMedicoException.class,()->repo.cambiaStatoMedico(a,f.effective,"INATTIVO"));
            assertEquals("Stato prenotazione non canonico.",error.getMessage());
            assertEquals(1,MedicoInattivoFlussiIntegrationTest.conta(f.factory,"SELECT COUNT(*) FROM pagamento WHERE id_prenotazione=? AND stato='PAGATO'",b));
            f.execute("UPDATE prenotazione SET stato='CONFERMATA' WHERE id_prenotazione=?",b);assertEquals(before,f.snapshot(b));
        }
    }
    @Test void authorizationMissingAndAnomalousStateDoNotWrite() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a=admin(f);var repo=new MedicoRepository(f.factory);
            assertThrows(OperazioneNonAutorizzataException.class,()->repo.cambiaStatoMedico(f.operator,f.owner,"ATTIVO"));
            assertThrows(RisorsaNonTrovataException.class,()->repo.cambiaStatoMedico(a,f.patient,"ATTIVO"));
            f.execute("UPDATE medico SET stato='attivo' WHERE id_utente=?",f.owner);
            assertThrows(ConflittoMedicoException.class,()->repo.cambiaStatoMedico(a,f.owner,"ATTIVO"));
            assertEquals("attivo",MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",f.owner).getLast());
        }
    }
    @Test void uc20AndUc21BothPrecedencesDoNotRequireLateDoctorLocks() throws Exception {
        for (boolean visita : new boolean[]{false,true}) for (boolean clinicoPrima : new boolean[]{false,true}) {
            try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                int actor=admin(f); int booking=f.booking(StatoPrenotazione.CONFERMATA,
                        MedicoInattivoFlussiIntegrationTest.NOW.plusDays(visita?-2:2));
                var held=new CountDownLatch(1); var release=new CountDownLatch(1);
                var observed=PrenotazioneIndisponibilitaIntegrationTest.observeQueries(f.factory,(sql,id,after)->{
                    if (clinicoPrima && after && sql.contains("FROM prenotazione") && sql.contains("FOR UPDATE") && id==booking) {
                        held.countDown();MedicoInattivoFlussiIntegrationTest.await(release);
                    }
                    if (!clinicoPrima && !after && sql.startsWith("SELECT pr.stato")) {
                        held.countDown();MedicoInattivoFlussiIntegrationTest.await(release);
                    }
                });
                var clinical=new PrenotazioneRepository(clinicoPrima?observed:f.factory,MedicoInattivoFlussiIntegrationTest.CLOCK);
                Callable<Object> action=()->visita?clinical.registraVisitaAtomically(booking,f.effective):
                        clinical.segnalaIndisponibilitaAtomically(f.effective,List.of(booking));
                ExecutorService pool=Executors.newFixedThreadPool(2);
                try {
                    if(clinicoPrima) {
                        var future=pool.submit(action);MedicoInattivoFlussiIntegrationTest.await(held);
                        /* Modifiche cliniche non confermate non equivalgono a impegni risolti. */
                        assertThrows(ConflittoMedicoException.class,()->new MedicoRepository(f.factory).cambiaStatoMedico(actor,f.effective,"INATTIVO"));
                        release.countDown();future.get(10,TimeUnit.SECONDS);
                        if(visita) assertEquals("INATTIVO",new MedicoRepository(f.factory).cambiaStatoMedico(actor,f.effective,"INATTIVO").stato());
                    } else {
                        var future=pool.submit(()->new MedicoRepository(observed).cambiaStatoMedico(actor,f.effective,"INATTIVO"));
                        MedicoInattivoFlussiIntegrationTest.await(held);pool.submit(action).get(10,TimeUnit.SECONDS);release.countDown();
                        if(visita) assertEquals("INATTIVO",future.get(10,TimeUnit.SECONDS).stato());
                        else assertInstanceOf(ConflittoMedicoException.class,MedicoInattivoConcorrenzaIntegrationTest.cause(
                                assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS))));
                    }
                    assertEquals(visita?StatoPrenotazione.EFFETTUATA.name():StatoPrenotazione.CONFERMATA.name(),
                            bookingState(f,booking));
                    assertEquals(!visita,f.flag(booking));
                } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
            }
        }
    }
    static String bookingState(PrenotazioneIndisponibilitaIntegrationTest.Fixture f,int id) throws Exception {
        try(var c=f.factory.getConnection();var q=c.prepareStatement("SELECT stato FROM prenotazione WHERE id_prenotazione=?")) {
            q.setInt(1,id);try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    @Test void substitutionAndReturnToHolderFollowCurrentEffectiveDoctor() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a=admin(f),b=f.booking(StatoPrenotazione.CONFERMATA,MedicoInattivoFlussiIntegrationTest.NOW.plusDays(2));
            var repo=new MedicoRepository(f.factory);
            assertEquals("INATTIVO",repo.cambiaStatoMedico(a,f.owner,"INATTIVO").stato());
            f.repository.segnalaIndisponibilitaAtomically(f.effective,List.of(b));
            assertThrows(ConflittoPrenotazioneException.class,()->f.repository.sostituisciMedicoAtomically(
                    b,f.operator,f.owner,StatoPrenotazione.CONFERMATA,f.effective));
            repo.cambiaStatoMedico(a,f.owner,"ATTIVO");
            f.repository.sostituisciMedicoAtomically(b,f.operator,f.owner,StatoPrenotazione.CONFERMATA,f.effective);
            assertEquals("INATTIVO",repo.cambiaStatoMedico(a,f.effective,"INATTIVO").stato());
            assertThrows(ConflittoMedicoException.class,()->repo.cambiaStatoMedico(a,f.owner,"INATTIVO"));
            assertFalse(f.flag(b));
        }
    }
    @Test void failureBeforeCommitRollsBackRealStateChange() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a=admin(f);var before=MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",f.owner);
            var error=new java.sql.SQLException("synthetic commit failure before commit");
            var factory=new it.medbook.config.ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var c=f.factory.getConnection();
                    return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class[]{java.sql.Connection.class},(p,m,args)->{
                                if(m.getName().equals("commit")) throw error;
                                try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                            });
                }
            };
            assertSame(error,assertThrows(java.sql.SQLException.class,()->new MedicoRepository(factory).cambiaStatoMedico(a,f.owner,"INATTIVO")));
            assertEquals(before,MedicoInattivoFlussiIntegrationTest.riga(f.factory,"medico","id_utente",f.owner));
            assertEquals("INATTIVO",new MedicoRepository(f.factory).cambiaStatoMedico(a,f.owner,"INATTIVO").stato());
        }
    }
    @Test void anomalousHistoricalStateIsNotSilentlyClassifiedAsTerminal() throws Exception {
        try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
            int a=admin(f),b=f.booking(StatoPrenotazione.EFFETTUATA,MedicoInattivoFlussiIntegrationTest.NOW.minusDays(2));
            f.execute("UPDATE prenotazione SET stato='effettuata' WHERE id_prenotazione=?",b);
            var before=f.snapshot(b);var repo=new MedicoRepository(f.factory);
            for(int doctor:new int[]{f.owner,f.effective}) {
                assertEquals("Stato prenotazione non canonico.",assertThrows(ConflittoMedicoException.class,
                        ()->repo.cambiaStatoMedico(a,doctor,"INATTIVO")).getMessage());
            }
            assertEquals(before,f.snapshot(b));
            f.execute("UPDATE prenotazione SET stato='EFFETTUATA' WHERE id_prenotazione=?",b);
            assertEquals("INATTIVO",repo.cambiaStatoMedico(a,f.effective,"INATTIVO").stato());
        }
    }
}
