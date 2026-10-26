package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.model.StatoPrenotazione;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Inattivazione contro ogni scrittura clinica protetta, in entrambi gli ordini, su connessioni
 * separate e lock medico osservato.
 */
class MedicoInattivoConcorrenzaIntegrationTest {
    static final LocalDateTime NOW = MedicoInattivoFlussiIntegrationTest.NOW;
    static final int ROUNDS = 3;

    /* Inattivazione reale osservata sul lock MEDICO, non una scrittura di stato sostitutiva. */
    static boolean inattiva(ConnectionFactory factory, int attore, int medico, CountDownLatch attempting,
                            MedicoInattivoFlussiIntegrationTest.Executable afterLock) throws Exception {
        var observed = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(factory, (sql, id, after) -> {
            /* Un paziente che prenota per sé possiede già UTENTE: l'inattivazione può attendere prima di MEDICO. */
            if (!after && sql.contains("FROM utente") && sql.contains("FOR UPDATE") && id == medico) { attempting.countDown(); }
            if (doctorLock(sql) && id == medico) {
                if (after) { afterLock.run(); } else { attempting.countDown(); }
            }
        });
        try { new MedicoRepository(observed).cambiaStatoMedico(attore, medico, "INATTIVO"); return true; }
        catch (it.medbook.exception.ConflittoMedicoException e) {
            assertEquals("Prenotazioni bloccanti presenti.", e.getMessage()); return false;
        }
    }

    enum Op {
        UC05(ConflittoPrenotazioneException.class, true), UC11(ConflittoPrenotazioneException.class, true),
        UC17(ConflittoPrenotazioneException.class, true), UC19_CREA(OperazioneNonAutorizzataException.class, false),
        UC19_MODIFICA(OperazioneNonAutorizzataException.class, false), UC19_ELIMINA(OperazioneNonAutorizzataException.class, false),
        UC22(OperazioneNonAutorizzataException.class, false), UC27(ConflittoConfigurazioneException.class, false);
        final Class<? extends RuntimeException> rifiuto; final boolean bloccaInattivazione;
        Op(Class<? extends RuntimeException> rifiuto, boolean bloccaInattivazione) { this.rifiuto = rifiuto; this.bloccaInattivazione = bloccaInattivazione; }
    }
    static Stream<Arguments> casi() {
        return Arrays.stream(Op.values()).flatMap(op -> Stream.of(Arguments.of(op, true), Arguments.of(op, false)));
    }

    /* Scenario preparato: medico, scrittura ed effetti persistenti attesi. */
    static final class Scenario {
        final PrenotazioneIndisponibilitaIntegrationTest.Fixture f; final Op op; int medico, slot, booking, admin; LocalDateTime start;
        Scenario(PrenotazioneIndisponibilitaIntegrationTest.Fixture f, Op op) throws Exception {
            this.f = f; this.op = op; admin = f.user();
            f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?", admin); start = MedicoInattivoFlussiIntegrationTest.futuro();
            switch (op) {
                case UC05 -> { medico = f.owner; slot = MedicoInattivoFlussiIntegrationTest.slot(f, f.owner, start, true); }
                case UC11 -> { medico = f.owner; booking = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusDays(3)); }
                case UC17 -> { medico = f.effective; booking = f.booking(StatoPrenotazione.RICHIESTA, NOW.plusDays(3));
                    f.repository.segnalaIndisponibilitaAtomically(f.owner, List.of(booking)); }
                case UC19_CREA -> medico = f.owner;
                case UC19_MODIFICA, UC19_ELIMINA, UC27 -> { medico = f.owner; slot = MedicoInattivoFlussiIntegrationTest.slot(f, f.owner, MedicoInattivoFlussiIntegrationTest.futuro(), false); }
                case UC22 -> { medico = f.effective; booking = f.booking(StatoPrenotazione.EFFETTUATA, NOW.minusDays(2)); }
            }
        }
        Object run(ConnectionFactory factory) throws Exception {
            var prenotazioni = new PrenotazioneRepository(factory, MedicoInattivoFlussiIntegrationTest.CLOCK);
            var disponibilita = new DisponibilitaRepository(factory, MedicoInattivoFlussiIntegrationTest.CLOCK);
            return switch (op) {
                case UC05 -> { var p = prenotazioni.creaRichiestaAtomically(f.patient, slot); f.bookings.add(p.getIdPrenotazione()); yield p; }
                case UC11 -> prenotazioni.confermaAtomically(booking, f.operator);
                case UC17 -> prenotazioni.sostituisciMedicoAtomically(booking, f.operator, f.effective, StatoPrenotazione.RICHIESTA, f.owner);
                case UC19_CREA -> { var d = disponibilita.save(f.owner, start, start.plusHours(1)); f.slots.add(d.getIdDisponibilita()); yield d; }
                case UC19_MODIFICA -> disponibilita.modificaOrarioAtomically(slot, f.owner, start, start.plusHours(1));
                case UC19_ELIMINA -> { disponibilita.eliminaAtomically(slot, f.owner); yield Boolean.TRUE; }
                case UC22 -> new RefertoRepository(factory, MedicoInattivoFlussiIntegrationTest.CLOCK)
                        .saveAtomically(booking, f.effective, "documents/" + UUID.randomUUID() + "/document.pdf", "referto.pdf", new RefertoRepository.Outcome());
                case UC27 -> disponibilita.configuraAtomically(slot, f.prestazione, f.stanza);
            };
        }
        /* Impronta persistente invariata per rifiuto e modificata per successo. */
        List<Object> traccia() throws SQLException {
            var factory = f.factory;
            return switch (op) {
                case UC05 -> List.of(MedicoInattivoFlussiIntegrationTest.conta(factory, "SELECT COUNT(*) FROM prenotazione WHERE id_disponibilita=?", slot));
                case UC11, UC17 -> f.snapshot(booking);
                case UC19_CREA -> List.of(MedicoInattivoFlussiIntegrationTest.conta(factory, "SELECT COUNT(*) FROM disponibilita WHERE id_medico=?", f.owner));
                case UC19_MODIFICA, UC19_ELIMINA, UC27 -> MedicoInattivoFlussiIntegrationTest.riga(factory, "disponibilita", "id_disponibilita", slot);
                case UC22 -> List.of(MedicoInattivoFlussiIntegrationTest.conta(factory, "SELECT COUNT(*) FROM referto WHERE id_prenotazione=?", booking));
            };
        }
        void cleanup() throws SQLException { if (booking > 0) { f.execute("DELETE FROM referto WHERE id_prenotazione=?", booking); } }
    }

    static boolean doctorLock(String sql) { return sql.contains("FROM medico WHERE") && sql.contains("FOR "); }
    static Throwable cause(ExecutionException e) {
        Throwable t = e.getCause();
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof SQLException s && (s.getErrorCode() == 1213 || s.getErrorCode() == 1205)) {
                fail("deadlock o timeout di lock (" + s.getErrorCode() + "): " + s.getMessage(), s);
            }
        }
        return t;
    }

    @ParameterizedTest(name = "{0}, inattivazione prima: {1}") @MethodSource("casi")
    void bothOrdersAreSerializedOnTheDoctorLock(Op op, boolean inattivazionePrima) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (var f = new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                var scenario = new Scenario(f, op);
                try { if (inattivazionePrima) { inattivazionePrima(scenario); } else { operazionePrima(scenario); } }
                finally { scenario.cleanup(); }
            }
        }
    }

    /* L'inattivazione mantiene il lock medico; lo scrittore attende e poi legge il risultato confermato. */
    void inattivazionePrima(Scenario s) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempting = new CountDownLatch(1);
        var before = s.traccia();
        var observed = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(s.f.factory, (sql, id, after) -> {
            if (!after && id == s.medico && (doctorLock(sql) || (sql.contains("FROM paziente") && sql.contains("FOR SHARE")))) { attempting.countDown(); }
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> inactivation = pool.submit(() -> inattiva(s.f.factory, s.admin, s.medico, new CountDownLatch(1), () -> {
                held.countDown(); MedicoInattivoFlussiIntegrationTest.await(release); }));
            MedicoInattivoFlussiIntegrationTest.await(held);
            Future<Object> write = pool.submit(() -> s.run(observed));
            MedicoInattivoFlussiIntegrationTest.await(attempting);
            assertThrows(TimeoutException.class, () -> write.get(200, TimeUnit.MILLISECONDS), "la scrittura deve attendere il lock del medico");
            release.countDown();
            boolean inactivated;
            try { inactivated = inactivation.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { throw new AssertionError(cause(e)); }
            if (s.op == Op.UC11) {
                /* RICHIESTA pendente blocca già l'inattivazione; la conferma successiva riesce. */
                assertFalse(inactivated);
                try { write.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { throw new AssertionError(cause(e)); }
                assertNotEquals(before, s.traccia());
            } else {
                assertTrue(inactivated, "nessuna prenotazione bloccante");
                Throwable error = cause(assertThrows(ExecutionException.class, () -> write.get(10, TimeUnit.SECONDS)));
                assertInstanceOf(s.op.rifiuto, error); assertEquals("Medico non operativo.", error.getMessage());
                assertEquals(before, s.traccia(), "nessuna scrittura dopo il rifiuto");
            }
        } finally { release.countDown(); }
    }

    /* Lo scrittore mantiene il lock medico; l'inattivazione attende il commit. */
    void operazionePrima(Scenario s) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var attempting = new CountDownLatch(1);
        var before = s.traccia();
        var observed = PrenotazioneIndisponibilitaIntegrationTest.observeQueries(s.f.factory, (sql, id, after) -> {
            if (after && doctorLock(sql) && id == s.medico) { held.countDown(); MedicoInattivoFlussiIntegrationTest.await(release); }
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Object> write = pool.submit(() -> s.run(observed));
            MedicoInattivoFlussiIntegrationTest.await(held);
            Future<Boolean> inactivation = pool.submit(() -> inattiva(s.f.factory, s.admin, s.medico, attempting, () -> { }));
            MedicoInattivoFlussiIntegrationTest.await(attempting);
            assertThrows(TimeoutException.class, () -> inactivation.get(200, TimeUnit.MILLISECONDS), "l'inattivazione deve attendere il lock del medico");
            release.countDown();
            try { write.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { throw new AssertionError(cause(e)); }
            assertNotEquals(before, s.traccia(), "scrittura completata");
            boolean inactivated;
            try { inactivated = inactivation.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { throw new AssertionError(cause(e)); }
            /* RICHIESTA del titolare e CONFERMATA del medico effettivo bloccano l'inattivazione; slot liberi e referti no. */
            assertEquals(!s.op.bloccaInattivazione, inactivated);
        } finally { release.countDown(); }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void patientDoctorAndAdministratorDoctorSelfBookingBothOrders(boolean administratorDoctor) throws Exception {
        for(boolean first:new boolean[]{false,true}) for(int round=0;round<ROUNDS;round++) {
            try(var f=new PrenotazioneIndisponibilitaIntegrationTest.Fixture()) {
                f.execute("INSERT INTO paziente(id_utente,codice_fiscale,data_nascita) VALUES(?,?,'1990-01-01')",
                        f.owner,UUID.randomUUID().toString().replace("-","").substring(0,16));
                f.patient=f.owner;
                var scenario=new Scenario(f,Op.UC05);
                if(administratorDoctor) {
                    f.execute("UPDATE utente SET ruolo_gestionale='AMMINISTRATORE' WHERE id_utente=?",f.owner);
                    scenario.admin=f.owner;
                }
                if(first) inattivazionePrima(scenario);else operazionePrima(scenario);
            }
        }
    }
}
