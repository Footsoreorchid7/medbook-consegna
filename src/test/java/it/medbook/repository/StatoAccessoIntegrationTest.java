package it.medbook.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.config.*;
import it.medbook.controller.UtenteController;
import it.medbook.exception.*;
import it.medbook.model.ModalitaPagamento;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.StatoPrenotazione;
import it.medbook.security.*;
import it.medbook.service.*;
import it.medbook.support.MutableClock;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Transizioni di accesso, generazione, sessioni e coerenza medico su MySQL isolato. Le attese si
 * osservano nel server, non si deducono dal tempo trascorso.
 */
class StatoAccessoIntegrationTest {
    static final LocalDateTime SENTINELLA = LocalDateTime.of(2000, 1, 1, 0, 0);
    static final LocalDateTime FUTURO = LocalDateTime.of(2030, 1, 20, 10, 0);
    static final Clock DESK_CLOCK = Clock.fixed(LocalDateTime.of(2030, 1, 15, 8, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    final MutableClock clock = new MutableClock();

    static final class F extends AccountInternoRepositoryIntegrationTest.Fixture {
        F() throws Exception { super(); }
    }
    UtenteRepository repo(F f) { return new UtenteRepository(f.factory, clock); }
    LocalDateTime ora() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }

    record Riga(String stato, BigInteger generazione, Integer da, LocalDateTime il, String ruolo, String hash) { }
    static Riga riga(F f, int id) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT stato_accesso, generazione_autenticazione, modificato_da, modificato_il, ruolo_gestionale, password_hash FROM utente WHERE id_utente=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next());
                return new Riga(r.getString(1), r.getObject(2, BigInteger.class), r.getObject(3, Integer.class),
                        r.getObject(4, LocalDateTime.class), r.getString(5), r.getString(6));
            }
        }
    }
    static void sentinella(F f, int id) throws SQLException { f.execute("UPDATE utente SET modificato_da=NULL, modificato_il=? WHERE id_utente=?", SENTINELLA, id); }
    static Riga con(Riga r, String stato, long generazione, Integer da, LocalDateTime il) {
        return new Riga(stato, BigInteger.valueOf(generazione), da, il, r.ruolo(), r.hash());
    }

    // A, B, G, L
    @Test void a_b_g_l_everyTransitionIncrementsOnceAndOnlyEffectiveOnesAreTracked() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); sentinella(f, f.patient); Riga iniziale = riga(f, f.patient);
            var noop = repo(f).cambiaStatoAccesso(admin, f.patient, "ATTIVO");
            assertNull(noop.nuovaGenerazione()); assertEquals("ATTIVO", noop.utente().statoAccesso());
            assertNotNull(noop.utente().codiceFiscale()); assertEquals(iniziale, riga(f, f.patient), "B: ATTIVO -> ATTIVO senza scrittura");

            clock.advance(Duration.ofMinutes(5));
            var spento = repo(f).cambiaStatoAccesso(admin, f.patient, "DISABILITATO");
            assertEquals(1L, spento.nuovaGenerazione()); assertEquals("DISABILITATO", spento.utente().statoAccesso());
            assertEquals(con(iniziale, "DISABILITATO", 1, admin, ora()), riga(f, f.patient), "G, L");
            Riga disabilitato = riga(f, f.patient);

            clock.advance(Duration.ofMinutes(5));
            assertNull(repo(f).cambiaStatoAccesso(admin, f.patient, "DISABILITATO").nuovaGenerazione());
            assertEquals(disabilitato, riga(f, f.patient), "B: DISABILITATO -> DISABILITATO senza scrittura");

            var riacceso = repo(f).cambiaStatoAccesso(admin, f.patient, "ATTIVO");
            assertEquals(2L, riacceso.nuovaGenerazione());
            assertEquals(con(iniziale, "ATTIVO", 2, admin, ora()), riga(f, f.patient));
        }
    }

    // C, D
    @Test void c_d_selfDisableIsRefusedWhileSelfNoOpIsAllowed() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); f.admin(); sentinella(f, admin); Riga prima = riga(f, admin);
            var e = assertThrows(UtenteRepository.ConflittoAccountException.class, () -> repo(f).cambiaStatoAccesso(admin, admin, "DISABILITATO"));
            assertEquals("Auto-disabilitazione non consentita.", e.getMessage()); assertEquals(prima, riga(f, admin));
            var noop = repo(f).cambiaStatoAccesso(admin, admin, "ATTIVO");
            assertNull(noop.nuovaGenerazione()); assertEquals(prima, riga(f, admin));
        }
    }

    /* Amministratori disabilitati non contano e non agiscono; il loro ruolo può comunque cambiare. */
    @Test void e_onlyActiveAdministratorsActAndCount() throws Exception {
        try (F f = new F()) {
            int a = f.admin(), b = f.admin();
            repo(f).cambiaStatoAccesso(a, b, "DISABILITATO");
            for (var azione : List.<Executable>of(() -> repo(f).cambiaStatoAccesso(b, a, "DISABILITATO"), () -> repo(f).cambiaStatoAccesso(b, b, "ATTIVO"),
                    () -> repo(f).updateRuolo(b, f.operator, RuoloGestionale.AMMINISTRATORE),
                    () -> repo(f).createInterno(b, "N", "C", UUID.randomUUID() + "@example.invalid", "hash", RuoloGestionale.OPERATORE_DESK),
                    () -> repo(f).cerca(b, new UtenteRepository.FiltriRicerca(a, null, null, null, null, null, 0, 20)))) {
                assertThrows(OperazioneNonAutorizzataException.class, azione::run, "amministratore disabilitato: 403");
            }
            /* Promuovere un desk disabilitato è ammesso ma non abilita l'account. */
            repo(f).cambiaStatoAccesso(a, f.operator, "DISABILITATO");
            assertEquals(RuoloGestionale.AMMINISTRATORE, repo(f).updateRuolo(a, f.operator, RuoloGestionale.AMMINISTRATORE).ruoloGestionale());
            assertThrows(OperazioneNonAutorizzataException.class, () -> repo(f).cambiaStatoAccesso(f.operator, b, "ATTIVO"));
            /* Un amministratore disabilitato può essere declassato: non conta come ultimo amministratore utilizzabile. */
            assertEquals(RuoloGestionale.OPERATORE_DESK, repo(f).updateRuolo(a, b, RuoloGestionale.OPERATORE_DESK).ruoloGestionale());
            // Con un altro amministratore attivo è consentito disabilitare un amministratore.
            int c = f.admin();
            assertEquals("DISABILITATO", repo(f).cambiaStatoAccesso(a, c, "DISABILITATO").utente().statoAccesso());
        }
    }
    @FunctionalInterface interface Executable { void run() throws Exception; }

    /* Due amministratori si disabilitano reciprocamente: serializzazione tramite AccountMutex. */
    @Test void f_mutualDisableLeavesExactlyOneActiveAdministrator() throws Exception {
        try (F f = new F()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 5; round++) {
                    int a = f.admin(), b = f.admin(); CyclicBarrier via = new CyclicBarrier(2);
                    Future<Object> ab = pool.submit(() -> { via.await(); return esito(() -> repo(f).cambiaStatoAccesso(a, b, "DISABILITATO")); });
                    Future<Object> ba = pool.submit(() -> { via.await(); return esito(() -> repo(f).cambiaStatoAccesso(b, a, "DISABILITATO")); });
                    Object r1 = ab.get(30, TimeUnit.SECONDS), r2 = ba.get(30, TimeUnit.SECONDS);
                    long riusciti = List.of(r1, r2).stream().filter(r -> r instanceof UtenteRepository.CambioStatoAccesso).count();
                    long rifiutati = List.of(r1, r2).stream().filter(r -> r instanceof OperazioneNonAutorizzataException).count();
                    assertEquals(1, riusciti, "round " + round + ": " + r1 + " / " + r2); assertEquals(1, rifiutati, "il secondo trova l'attore non più attivo");
                    long attivi = List.of(riga(f, a).stato(), riga(f, b).stato()).stream().filter("ATTIVO"::equals).count();
                    assertEquals(1, attivi);
                }
            } finally { pool.shutdownNow(); }
        }
    }
    static Object esito(Callable<Object> c) { try { return c.call(); } catch (Exception e) { return e; } }

    /* Destinatari anomali: 409 senza scritture. */
    @Test void h_generationLimitAndAnomalousTargetsAreConflictsWithoutChanges() throws Exception {
        try (F f = new F()) {
            int admin = f.admin();
            f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775806 WHERE id_utente=?", f.patient);
            assertEquals(Long.MAX_VALUE, repo(f).cambiaStatoAccesso(admin, f.patient, "DISABILITATO").nuovaGenerazione(), "MAX-1 -> MAX riuscito");
            Riga massimo = riga(f, f.patient);
            clock.advance(Duration.ofHours(1));
            var e = assertThrows(GenerazioneEsauritaException.class, () -> repo(f).cambiaStatoAccesso(admin, f.patient, "ATTIVO"));
            assertEquals("Operazione non disponibile per questo account.", e.getMessage());
            assertEquals(massimo, riga(f, f.patient), "nessun wrap-around, stato, hash e tracciamento invariati");
            assertNull(repo(f).cambiaStatoAccesso(admin, f.patient, "DISABILITATO").nuovaGenerazione(), "il no-op resta possibile al limite");

            f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775808 WHERE id_utente=?", f.operator);
            Riga fuori = riga(f, f.operator);
            var c = assertThrows(UtenteRepository.ConflittoAccountException.class, () -> repo(f).cambiaStatoAccesso(admin, f.operator, "DISABILITATO"));
            assertFalse(c.getMessage().contains("9223372036854775808")); assertEquals(fuori, riga(f, f.operator));
            assertThrows(UtenteRepository.ConflittoAccountException.class, () -> repo(f).updateRuolo(admin, f.operator, RuoloGestionale.AMMINISTRATORE),
                    "cambio ruolo con generazione anomala del destinatario: 409");
            assertEquals(fuori, riga(f, f.operator));

            int anomalo = f.user(); f.execute("UPDATE utente SET ruolo_gestionale='operatore_desk' WHERE id_utente=?", anomalo); Riga ruolo = riga(f, anomalo);
            assertThrows(UtenteRepository.ConflittoAccountException.class, () -> repo(f).cambiaStatoAccesso(admin, anomalo, "DISABILITATO"));
            assertEquals(ruolo, riga(f, anomalo));
            assertThrows(RisorsaNonTrovataException.class, () -> repo(f).cambiaStatoAccesso(admin, Integer.MAX_VALUE, "DISABILITATO"));
            /* La decisione sull'attore precede il destinatario: 403 anche se il destinatario non esiste. */
            assertThrows(OperazioneNonAutorizzataException.class, () -> repo(f).cambiaStatoAccesso(f.operator, Integer.MAX_VALUE, "DISABILITATO"));
        }
    }

    // I, J, K
    @Test void i_j_k_sessionsAreSupersededAndRevokeStaleKeepsLaterGenerations() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); var store = new SessionStore(Clock.systemUTC(), 100);
            var auth = new AutenticazioneService(repo(f), new PasswordHasher(), store);
            var service = new UtenteService(repo(f), new PasswordHasher(), store);
            var vecchia = store.create(f.patient, 0); var nonUsata = store.create(f.patient, 0);
            var successiva = store.create(f.patient, 7); var altra = store.create(f.operator, 0);
            assertTrue(auth.identity(vecchia).isPresent());
            service.cambiaStato(admin, f.patient, "DISABILITATO");
            assertTrue(store.find(vecchia.id()).isEmpty(), "revokeStale(id, 1) dopo il commit"); assertTrue(store.find(nonUsata.id()).isEmpty());
            assertTrue(store.find(successiva.id()).isPresent(), "K: generazione successiva mai rimossa da revokeStale");
            assertTrue(store.find(altra.id()).isPresent(), "altri utenti intatti");
            assertTrue(auth.identity(successiva).isEmpty(), "ma I3 la rifiuta comunque (generazione diversa)");
            /* Una sessione della generazione disabilitata resta invalida anche dopo riabilitazione. */
            var durante = store.create(f.patient, 1); assertTrue(auth.identity(durante).isEmpty());
            var pendente = store.create(f.patient, 1);
            service.cambiaStato(admin, f.patient, "ATTIVO");
            assertTrue(store.find(pendente.id()).isEmpty());
            assertTrue(auth.identity(store.create(f.patient, 1)).isEmpty(), "A1: generazione 1 superata dalla riabilitazione");
            assertTrue(auth.identity(store.create(f.patient, 2)).isPresent(), "nuovo accesso alla generazione corrente");
            /* Le richieste idempotenti non modificano lo store. */
            var corrente = store.create(f.patient, 2); service.cambiaStato(admin, f.patient, "ATTIVO");
            assertTrue(store.find(corrente.id()).isPresent());
        }
    }

    // M, N, O, P
    @Test void m_n_o_p_doctorProfileRulesAndUc23Coherence() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); var medici = new MedicoRepository(f.factory, clock);
            Riga prima = riga(f, f.owner);
            var e = assertThrows(UtenteRepository.ConflittoAccountException.class, () -> repo(f).cambiaStatoAccesso(admin, f.owner, "DISABILITATO"));
            assertEquals("Inattivare prima il profilo medico.", e.getMessage()); assertEquals(prima, riga(f, f.owner));
            assertEquals("ATTIVO", statoMedico(f, f.owner), "M: medico.stato mai modificato automaticamente");
            medici.cambiaStatoMedico(admin, f.owner, "INATTIVO");
            assertEquals("DISABILITATO", repo(f).cambiaStatoAccesso(admin, f.owner, "DISABILITATO").utente().statoAccesso(), "N");
            var o = assertThrows(ConflittoMedicoException.class, () -> medici.cambiaStatoMedico(admin, f.owner, "ATTIVO"));
            assertEquals(MedicoRepository.ACCOUNT_NON_ATTIVO, o.getMessage()); assertEquals("INATTIVO", statoMedico(f, f.owner), "O: nessuna modifica");
            /*
             * ATTIVO verso ATTIVO resta idempotente anche con accesso disabilitato, combinazione
             * raggiungibile soltanto con dati manuali.
             */
            f.execute("UPDATE medico SET stato='ATTIVO' WHERE id_utente=?", f.owner);
            assertEquals("ATTIVO", medici.cambiaStatoMedico(admin, f.owner, "ATTIVO").stato());
            assertEquals("INATTIVO", medici.cambiaStatoMedico(admin, f.owner, "INATTIVO").stato(), "inattivazione sempre ammessa");
            // P: no profile linked to a disabled account.
            int spento = f.user(); repo(f).cambiaStatoAccesso(admin, spento, "DISABILITATO");
            var p = assertThrows(ConflittoMedicoException.class, () -> medici.collega(admin, spento, "Cardiologia", "U5-" + UUID.randomUUID()));
            assertEquals(MedicoRepository.ACCOUNT_NON_ATTIVO, p.getMessage()); assertNull(statoMedico(f, spento));
            /* Un amministratore disabilitato non può leggere o modificare i medici. */
            int altro = f.admin(); repo(f).cambiaStatoAccesso(admin, altro, "DISABILITATO");
            assertThrows(OperazioneNonAutorizzataException.class, () -> medici.cambiaStatoMedico(altro, f.effective, "INATTIVO"));
            assertThrows(OperazioneNonAutorizzataException.class, () -> medici.dettaglioPerAmministratore(altro, f.effective));
        }
    }
    static String statoMedico(F f, int id) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT stato FROM medico WHERE id_utente=?")) {
            s.setInt(1, id); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    // Q
    @Test void q_disablingAPatientPreservesBookingsAndTheDeskKeepsManagingThem() throws Exception {
        try (F f = new F()) {
            int admin = f.admin();
            int richiesta = f.booking(StatoPrenotazione.RICHIESTA, FUTURO), confermata = f.booking(StatoPrenotazione.CONFERMATA, FUTURO.plusDays(1));
            var prima = List.of(f.snapshot(richiesta), f.snapshot(confermata)); var paziente = paziente(f);
            repo(f).cambiaStatoAccesso(admin, f.patient, "DISABILITATO");
            assertEquals(prima, List.of(f.snapshot(richiesta), f.snapshot(confermata)), "prenotazioni intatte");
            assertEquals(paziente, paziente(f), "profilo paziente intatto");
            /* Il desk può gestire prenotazioni future di un paziente disabilitato. */
            new PrenotazioneRepository(f.factory, DESK_CLOCK).confermaAtomically(richiesta, f.operator);
            assertNotEquals(prima.get(0), f.snapshot(richiesta));
        }
    }
    static List<Object> paziente(F f) throws SQLException {
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM paziente WHERE id_utente=?")) {
            s.setInt(1, f.patient);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); var l = new ArrayList<Object>(); for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) { l.add(r.getObject(i)); } return l; }
        }
    }

    // Un operatore disabilitato non può iniziare transazioni desk.
    @Test void disabledDeskOperatorIsRefusedUnderTheShareLock() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); int booking = f.booking(StatoPrenotazione.RICHIESTA, FUTURO); var prima = f.snapshot(booking);
            repo(f).cambiaStatoAccesso(admin, f.operator, "DISABILITATO");
            var prenotazioni = new PrenotazioneRepository(f.factory, DESK_CLOCK);
            assertThrows(OperazioneNonAutorizzataException.class, () -> prenotazioni.confermaAtomically(booking, f.operator));
            assertThrows(OperazioneNonAutorizzataException.class, () -> prenotazioni.rifiutaAtomically(booking, f.operator));
            assertThrows(OperazioneNonAutorizzataException.class, () -> new PagamentoRepository(f.factory, DESK_CLOCK)
                    .registraInSedeAtomically(booking, f.operator, ModalitaPagamento.DESK_CONTANTI, new BigDecimal("50.00")));
            assertThrows(OperazioneNonAutorizzataException.class, () -> new RimborsoRepository(f.factory, DESK_CLOCK).decidiAtomically(1, f.operator, true));
            assertEquals(prima, f.snapshot(booking));
            repo(f).cambiaStatoAccesso(admin, f.operator, "ATTIVO");
            prenotazioni.confermaAtomically(booking, f.operator);
        }
    }

    /* Sospende la prima istruzione corrispondente, prima della preparazione o dopo l'esecuzione. */
    static ConnectionFactory pausa(ConnectionFactory base, Predicate<String> sql, boolean dopoEsecuzione, CountDownLatch arrivato, CountDownLatch via) {
        return new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = base.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> {
                    Object result;
                    if (m.getName().equals("prepareStatement") && sql.test((String) a[0]) && !dopoEsecuzione && arrivato.getCount() > 0) { fermati(arrivato, via); }
                    try { result = m.invoke(c, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                    if (m.getName().equals("prepareStatement") && sql.test((String) a[0]) && dopoEsecuzione) {
                        PreparedStatement s = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (q, n, b) -> {
                            Object r; try { r = n.invoke(s, b); } catch (InvocationTargetException e) { throw e.getCause(); }
                            if (n.getName().equals("executeQuery") && arrivato.getCount() > 0) { fermati(arrivato, via); }
                            return r;
                        });
                    }
                    return result;
                });
            }
        };
    }
    static void fermati(CountDownLatch arrivato, CountDownLatch via) throws InterruptedException {
        arrivato.countDown(); assertTrue(via.await(30, TimeUnit.SECONDS));
    }
    /*
     * Nel catalogo operano soltanto le due transazioni della prova: ogni attesa UTENTE osservata
     * appartiene a loro.
     */
    static void attendiAttesaUtente(F f, Future<?> inAttesa) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection c = f.factory.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                WHERE r.OBJECT_SCHEMA = DATABASE() AND r.OBJECT_NAME = 'utente'""")) {
            while (true) {
                try (ResultSet r = s.executeQuery()) { r.next(); if (r.getLong(1) > 0) { return; } }
                assertFalse(inAttesa.isDone(), "la seconda transazione non deve concludersi prima del rilascio");
                assertTrue(System.nanoTime() < deadline, "nessuna attesa InnoDB osservata sulla riga utente");
                Thread.sleep(10); /* Attesa tramite polling. */
            }
        }
    }

    // V (concurrency): disabling an operator against a UC-11 confirmation, both orders, three rounds.
    @Test void v_disableAgainstDeskConfirmationInBothOrders() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); ExecutorService pool = Executors.newFixedThreadPool(2);
            Predicate<String> condivisa = sql -> sql.contains("stato_accesso FROM utente") && sql.contains("FOR SHARE");
            Predicate<String> dopoUpdate = sql -> sql.contains("LEFT JOIN paziente p ON p.id_utente = u.id_utente WHERE u.id_utente = ?");
            try {
                for (int round = 0; round < 3; round++) {
                    /* Desk prima: FOR SHARE sull'operatore; la disabilitazione attende e poi si applica. */
                    int b1 = f.booking(StatoPrenotazione.RICHIESTA, FUTURO.plusDays(round));
                    CountDownLatch arrivato = new CountDownLatch(1), via = new CountDownLatch(1);
                    var desk = new PrenotazioneRepository(pausa(f.factory, condivisa, true, arrivato, via), DESK_CLOCK);
                    Future<Object> conferma = pool.submit(() -> esito(() -> desk.confermaAtomically(b1, f.operator)));
                    assertTrue(arrivato.await(10, TimeUnit.SECONDS));
                    Future<Object> spegni = pool.submit(() -> esito(() -> repo(f).cambiaStatoAccesso(admin, f.operator, "DISABILITATO")));
                    try { attendiAttesaUtente(f, spegni); } finally { via.countDown(); }
                    assertInstanceOf(it.medbook.dto.ConfermaPrenotazioneResponse.class, conferma.get(30, TimeUnit.SECONDS), "round " + round);
                    assertInstanceOf(UtenteRepository.CambioStatoAccesso.class, spegni.get(30, TimeUnit.SECONDS));
                    assertEquals("DISABILITATO", riga(f, f.operator).stato());
                    repo(f).cambiaStatoAccesso(admin, f.operator, "ATTIVO");

                    /* Disabilitazione prima: FOR UPDATE fino al commit; la conferma attende e ottiene 403. */
                    int b2 = f.booking(StatoPrenotazione.RICHIESTA, FUTURO.plusDays(10 + round)); var prima = f.snapshot(b2);
                    CountDownLatch arrivato2 = new CountDownLatch(1), via2 = new CountDownLatch(1);
                    var lento = new UtenteRepository(pausa(f.factory, dopoUpdate, false, arrivato2, via2), clock);
                    Future<Object> spegni2 = pool.submit(() -> esito(() -> lento.cambiaStatoAccesso(admin, f.operator, "DISABILITATO")));
                    assertTrue(arrivato2.await(10, TimeUnit.SECONDS));
                    var desk2 = new PrenotazioneRepository(f.factory, DESK_CLOCK);
                    Future<Object> conferma2 = pool.submit(() -> esito(() -> desk2.confermaAtomically(b2, f.operator)));
                    try { attendiAttesaUtente(f, conferma2); } finally { via2.countDown(); }
                    assertInstanceOf(UtenteRepository.CambioStatoAccesso.class, spegni2.get(30, TimeUnit.SECONDS));
                    assertInstanceOf(OperazioneNonAutorizzataException.class, conferma2.get(30, TimeUnit.SECONDS), "round " + round);
                    assertEquals(prima, f.snapshot(b2));
                    repo(f).cambiaStatoAccesso(admin, f.operator, "ATTIVO");
                }
            } finally { pool.shutdownNow(); }
        }
    }

    /* Un errore dopo UPDATE lascia invariati stato, generazione e metadati tramite rollback. */
    @Test void v_failureAfterTheUpdateRollsBackEverything() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); sentinella(f, f.patient); Riga prima = riga(f, f.patient);
            ConnectionFactory guasto = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() throws SQLException {
                    Connection c = f.factory.getConnection(); boolean[] scritto = {false};
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> {
                        if (m.getName().equals("prepareStatement")) {
                            String sql = ((String) a[0]).strip();
                            if (sql.startsWith("UPDATE utente SET stato_accesso")) { scritto[0] = true; }
                            else if (scritto[0] && sql.startsWith("SELECT") && !sql.contains("_LOCK(")) { throw new SQLException("guasto iniettato"); }
                        }
                        try { return m.invoke(c, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
                }
            };
            var store = new SessionStore(Clock.systemUTC(), 10); var s = store.create(f.patient, 0);
            var service = new UtenteService(new UtenteRepository(guasto, clock), new PasswordHasher(), store);
            assertThrows(SQLException.class, () -> service.cambiaStato(admin, f.patient, "DISABILITATO"));
            assertEquals(prima, riga(f, f.patient)); assertTrue(store.find(s.id()).isPresent(), "nessuna pulizia senza commit confermato");
        }
    }

    /* Percorso reale attraverso autorizzazione, Origin, CSRF e controller. */
    @Test void r_s_httpContractAuthorizationAndManagementList() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(); var mapper = new ObjectMapper(); var store = new SessionStore(Clock.systemUTC(), 100);
            var config = new AuthConfig("local-http", "http://localhost:8080");
            var auth = new AutenticazioneService(repo(f), new PasswordHasher(), store);
            var handler = new AutorizzazioneHandler(new UtenteController(new UtenteService(repo(f), new PasswordHasher(), store), mapper), auth, store, config, mapper);
            var sessione = store.create(admin, 0);
            record Req(String method, String path, String body, SessionStore.Session s, boolean origin, boolean csrf, String type) { }
            java.util.function.Function<Req, TestHttpExchange> call = r -> {
                var x = new TestHttpExchange(r.method(), r.path(), r.body());
                if (r.type() == null) { x.requestHeaders.remove("Content-Type"); } else { x.requestHeaders.set("Content-Type", r.type()); }
                if (r.s() != null) { x.requestHeaders.set("Cookie", config.cookieName() + "=" + r.s().id()); if (r.csrf()) { x.requestHeaders.set("X-CSRF-Token", r.s().csrfToken()); } }
                if (r.origin()) { x.requestHeaders.set("Origin", config.origin()); }
                try { handler.handle(x); } catch (Exception e) { throw new AssertionError(e); }
                return x;
            };
            String path = "/api/utenti/" + f.patient + "/stato", json = "application/json";
            var ok = call.apply(new Req("PUT", path, "{\"statoAccesso\":\"DISABILITATO\"}", sessione, true, true, json));
            assertEquals(200, ok.status, ok.body());
            var body = mapper.readTree(ok.body()); Set<String> names = new HashSet<>(); body.fieldNames().forEachRemaining(names::add);
            assertEquals(Set.of("idUtente", "nome", "cognome", "codiceFiscale", "email", "ruoloGestionale", "statoAccesso"), names);
            assertEquals("DISABILITATO", body.get("statoAccesso").asText()); assertFalse(ok.body().contains("generazione"));
            assertEquals(200, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"DISABILITATO\"}", sessione, true, true, "application/json; charset=UTF-8")).status, "idempotente");
            /* Origin e CSRF obbligatori: nessuna scrittura se mancano. */
            assertEquals(403, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}", sessione, false, true, json)).status);
            assertEquals(403, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}", sessione, true, false, json)).status);
            assertEquals("DISABILITATO", riga(f, f.patient).stato());
            assertEquals(401, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}", null, true, false, json)).status);
            for (int altro : List.of(f.operator, f.owner, f.patient)) {
                var s = store.create(altro, riga(f, altro).generazione().longValueExact());
                assertEquals(altro == f.patient ? 401 : 403, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}", s, true, true, json)).status, "attore " + altro);
            }
            assertEquals(415, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}", sessione, true, true, "text/plain")).status);
            for (String bad : List.of("", "null", "[]", "{}", "{\"statoAccesso\":null}", "{\"statoAccesso\":\"attivo\"}", "{\"statoAccesso\":\"SOSPESO\"}",
                    "{\"statoAccesso\":1}", "{\"statoAccesso\":\"ATTIVO\",\"extra\":1}", "{\"statoAccesso\":\"ATTIVO\",\"statoAccesso\":\"ATTIVO\"}",
                    "{\"statoAccesso\":\"ATTIVO\"} {}", "{\"ruoloGestionale\":\"AMMINISTRATORE\"}", "{\"statoAccesso\":\"ATTIVO \"}")) {
                assertEquals(400, call.apply(new Req("PUT", path, bad, sessione, true, true, json)).status, bad);
            }
            assertEquals(400, call.apply(new Req("PUT", path, "{\"statoAccesso\":\"ATTIVO\"}" + " ".repeat(8192), sessione, true, true, json)).status, "oltre 8192 byte");
            assertEquals(400, call.apply(new Req("PUT", path + "?x=1", "{\"statoAccesso\":\"ATTIVO\"}", sessione, true, true, json)).status, "query vietata");
            assertEquals("DISABILITATO", riga(f, f.patient).stato());
            var get = call.apply(new Req("GET", path, "", sessione, false, false, json));
            assertEquals(405, get.status); assertEquals("PUT", get.responseHeaders.getFirst("Allow"));
            for (String p : List.of("/api/utenti/0/stato", "/api/utenti/01/stato", "/api/utenti/2147483648/stato", "/api/utenti/%31/stato", path + "/", "/api/utenti/" + f.patient + "/Stato")) {
                assertEquals(404, call.apply(new Req("PUT", p, "{\"statoAccesso\":\"ATTIVO\"}", sessione, true, true, json)).status, p);
            }
            assertEquals(404, call.apply(new Req("PUT", "/api/utenti/2147483647/stato", "{\"statoAccesso\":\"ATTIVO\"}", sessione, true, true, json)).status);
            var self = call.apply(new Req("PUT", "/api/utenti/" + admin + "/stato", "{\"statoAccesso\":\"DISABILITATO\"}", sessione, true, true, json));
            assertEquals(409, self.status); assertEquals("{\"error\":\"Auto-disabilitazione non consentita.\"}", self.body());
            var medico = call.apply(new Req("PUT", "/api/utenti/" + f.owner + "/stato", "{\"statoAccesso\":\"DISABILITATO\"}", sessione, true, true, json));
            assertEquals(409, medico.status); assertEquals("{\"error\":\"Inattivare prima il profilo medico.\"}", medico.body());
            f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775807 WHERE id_utente=?", f.operator);
            var limite = call.apply(new Req("PUT", "/api/utenti/" + f.operator + "/stato", "{\"statoAccesso\":\"DISABILITATO\"}", sessione, true, true, json));
            assertEquals(409, limite.status); assertEquals("{\"error\":\"Operazione non disponibile per questo account.\"}", limite.body());
            /* L'elenco account aggiunge soltanto statoAccesso, conservando gli altri campi. */
            var elenco = call.apply(new Req("GET", "/api/utenti", "", sessione, false, false, json)); assertEquals(200, elenco.status);
            boolean visto = false;
            for (var item : mapper.readTree(elenco.body())) {
                Set<String> campi = new HashSet<>(); item.fieldNames().forEachRemaining(campi::add);
                assertEquals(Set.of("idUtente", "nome", "cognome", "email", "ruoloGestionale", "paziente", "medico", "statoAccesso"), campi);
                if (item.get("idUtente").intValue() == admin) { visto = true; assertEquals("ATTIVO", item.get("statoAccesso").asText()); }
            }
            assertTrue(visto);
            int spento = f.admin(); repo(f).cambiaStatoAccesso(admin, spento, "DISABILITATO");
            var dopo = call.apply(new Req("GET", "/api/utenti", "", sessione, false, false, json));
            boolean disabilitatoVisto = false;
            for (var item : mapper.readTree(dopo.body())) { if (item.get("idUtente").intValue() == spento) { disabilitatoVisto = true; assertEquals("DISABILITATO", item.get("statoAccesso").asText()); } }
            assertTrue(disabilitatoVisto, "un amministratore disabilitato resta nell'elenco gestionale");
            assertEquals(400, call.apply(new Req("GET", "/api/utenti?statoAccesso=ATTIVO", "", sessione, false, false, json)).status);
        }
    }

    /* Generazione fuori da 0..Long.MAX_VALUE: conflitto anche per richieste idempotenti. */
    static final List<String> FUORI_DOMINIO = List.of("9223372036854775808", "18446744073709551615");

    /* Repository e servizio: anomalie, limiti di generazione, transizioni, idempotenza e concorrenza. */
    @Test void w_generationOutsideTheDomainIsAConflictAlsoForNoOps() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(), attivo = f.user(), spento = f.user();
            f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?", spento);
            var store = new SessionStore(Clock.systemUTC(), 100);
            var service = new UtenteService(repo(f), new PasswordHasher(), store);
            for (int destinatario : List.of(attivo, spento)) {
                String corrente = riga(f, destinatario).stato(), altro = "ATTIVO".equals(corrente) ? "DISABILITATO" : "ATTIVO";
                /* Una revoca con generazione almeno 1 rimuoverebbe la sessione testimone di generazione 0. */
                var testimone = store.create(destinatario, 0);
                for (String generazione : FUORI_DOMINIO) {
                    f.execute("UPDATE utente SET generazione_autenticazione=" + generazione + " WHERE id_utente=?", destinatario);
                    sentinella(f, destinatario); Riga prima = riga(f, destinatario); clock.advance(Duration.ofMinutes(1));
                    for (String richiesto : List.of(corrente, altro)) {
                        var e = assertThrows(UtenteRepository.ConflittoAccountException.class, () -> service.cambiaStato(admin, destinatario, richiesto),
                                corrente + " -> " + richiesto + " con " + generazione);
                        assertEquals("Operazione non disponibile per questo account.", e.getMessage()); assertFalse(e.getMessage().contains(generazione));
                        assertEquals(prima, riga(f, destinatario), "E: stato, generazione e tracciamento invariati");
                        assertTrue(store.find(testimone.id()).isPresent(), "F: nessuna revokeStale");
                    }
                }
                f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775807 WHERE id_utente=?", destinatario);
                sentinella(f, destinatario); Riga massimo = riga(f, destinatario); clock.advance(Duration.ofMinutes(1));
                var noop = service.cambiaStato(admin, destinatario, corrente);
                assertEquals(corrente, noop.statoAccesso(), "C: no-op a Long.MAX_VALUE");
                assertEquals(massimo, riga(f, destinatario)); assertTrue(store.find(testimone.id()).isPresent(), "F: il no-op non pulisce lo store");
                assertThrows(GenerazioneEsauritaException.class, () -> service.cambiaStato(admin, destinatario, altro), "D");
                assertEquals(massimo, riga(f, destinatario)); assertTrue(store.find(testimone.id()).isPresent());
                /* La transizione valida incrementa di uno e supera le sessioni precedenti. */
                f.execute("UPDATE utente SET generazione_autenticazione=41 WHERE id_utente=?", destinatario);
                var auth = new AutenticazioneService(repo(f), new PasswordHasher(), store);
                var vecchia = store.create(destinatario, 41);
                assertEquals(altro, service.cambiaStato(admin, destinatario, altro).statoAccesso());
                assertEquals(BigInteger.valueOf(42), riga(f, destinatario).generazione());
                assertTrue(store.find(testimone.id()).isEmpty() && store.find(vecchia.id()).isEmpty(), "revokeStale(id, 42) dopo il commit");
                assertTrue(auth.identity(store.create(destinatario, 41)).isEmpty());
            }
        }
    }

    /* Stessi casi attraverso autorizzazione e controller. */
    @Test void w_httpGenerationOutsideTheDomainForBothNoOps() throws Exception {
        try (F f = new F()) {
            int admin = f.admin(), attivo = f.user(), spento = f.user();
            f.execute("UPDATE utente SET stato_accesso='DISABILITATO' WHERE id_utente=?", spento);
            var mapper = new ObjectMapper(); var store = new SessionStore(Clock.systemUTC(), 100);
            var config = new AuthConfig("local-http", "http://localhost:8080");
            var handler = new AutorizzazioneHandler(new UtenteController(new UtenteService(repo(f), new PasswordHasher(), store), mapper),
                    new AutenticazioneService(repo(f), new PasswordHasher(), store), store, config, mapper);
            var sessione = store.create(admin, 0);
            for (int destinatario : List.of(attivo, spento)) {
                String corrente = riga(f, destinatario).stato(), altro = "ATTIVO".equals(corrente) ? "DISABILITATO" : "ATTIVO";
                java.util.function.Function<String, TestHttpExchange> put = stato -> {
                    var x = new TestHttpExchange("PUT", "/api/utenti/" + destinatario + "/stato", "{\"statoAccesso\":\"" + stato + "\"}");
                    x.requestHeaders.set("Cookie", config.cookieName() + "=" + sessione.id()); x.requestHeaders.set("X-CSRF-Token", sessione.csrfToken());
                    x.requestHeaders.set("Origin", config.origin());
                    try { handler.handle(x); } catch (Exception e) { throw new AssertionError(e); }
                    return x;
                };
                for (String generazione : FUORI_DOMINIO) {
                    f.execute("UPDATE utente SET generazione_autenticazione=" + generazione + " WHERE id_utente=?", destinatario);
                    sentinella(f, destinatario); Riga prima = riga(f, destinatario);
                    for (String richiesto : List.of(corrente, altro)) {
                        var r = put.apply(richiesto);
                        assertEquals(409, r.status, corrente + " -> " + richiesto + " con " + generazione);
                        assertEquals("{\"error\":\"Operazione non disponibile per questo account.\"}", r.body());
                        assertEquals(prima, riga(f, destinatario));
                    }
                }
                f.execute("UPDATE utente SET generazione_autenticazione=9223372036854775807 WHERE id_utente=?", destinatario);
                sentinella(f, destinatario); Riga massimo = riga(f, destinatario);
                var noop = put.apply(corrente);
                assertEquals(200, noop.status, noop.body()); assertEquals(corrente, mapper.readTree(noop.body()).get("statoAccesso").asText());
                assertEquals(massimo, riga(f, destinatario));
                var esaurita = put.apply(altro);
                assertEquals(409, esaurita.status); assertEquals("{\"error\":\"Operazione non disponibile per questo account.\"}", esaurita.body());
                assertEquals(massimo, riga(f, destinatario));
            }
            assertTrue(store.find(sessione.id()).isPresent(), "la sessione dell'amministratore resta valida");
        }
    }
}
