package it.medbook.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.AuthConfig;
import it.medbook.config.ConnectionFactory;
import it.medbook.controller.AutenticazioneController;
import it.medbook.controller.DisponibilitaController;
import it.medbook.controller.MedicoController;
import it.medbook.controller.PrenotazioneController;
import it.medbook.controller.PrestazioneController;
import it.medbook.controller.RegistrazioneController;
import it.medbook.controller.RimborsoController;
import it.medbook.controller.StanzaController;
import it.medbook.controller.UtenteController;
import it.medbook.model.StatoPagamento;
import it.medbook.payment.GatewayPagamentoSimulato;
import it.medbook.repository.DisponibilitaRepository;
import it.medbook.repository.MedicoRepository;
import it.medbook.repository.PagamentoRepository;
import it.medbook.repository.PrenotazioneRepository;
import it.medbook.repository.PrestazioneRepository;
import it.medbook.repository.RefertoRepository;
import it.medbook.repository.RegistrazioneRepository;
import it.medbook.repository.RimborsoRepository;
import it.medbook.repository.StanzaRepository;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.AutorizzazioneHandler;
import it.medbook.security.LoginThrottle;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import it.medbook.service.AutenticazioneService;
import it.medbook.service.DisponibilitaService;
import it.medbook.service.MedicoService;
import it.medbook.service.PagamentoService;
import it.medbook.service.PrenotazioneService;
import it.medbook.service.PrestazioneService;
import it.medbook.service.RefertoService;
import it.medbook.service.RegistrazioneService;
import it.medbook.service.RimborsoService;
import it.medbook.service.StanzaService;
import it.medbook.service.UtenteService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;

/*
 * Server JDK reale sul loopback con porta dinamica, stessi contesti e controller applicativi,
 * database isolato e storage temporaneo. Differenze intenzionali: configurazione iniettata,
 * orologio di dominio condiviso, orologio sessioni separato, validatore PDF di fixture invece di
 * qpdf e gateway PAGATO esplicito. L'esecutore predefinito serve le richieste in sequenza; le prove
 * concorrenti richiedono un esecutore diverso dichiarato nei test.
 */
public final class E2EServer implements AutoCloseable {
    public static final Instant T0 = Instant.parse("2031-01-10T08:00:00Z");
    /* Istante iniziale delle sessioni deterministico e distinto dall'orologio di dominio. */
    public static final Instant AUTH_T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final HttpServer server;
    private final AuthConfig authConfig;
    private final ManualClock domainClock;
    private final ManualClock authClock;
    private final SessionStore sessions;
    private final FixturePdfValidator pdfValidator;
    private final java.util.concurrent.atomic.AtomicBoolean chiuso = new java.util.concurrent.atomic.AtomicBoolean();

    public E2EServer(ConnectionFactory factory, E2EStorage storage) throws IOException {
        this(factory, storage, E2EGuasti.NESSUNO);
    }

    E2EServer(ConnectionFactory factory, E2EStorage storage, E2EGuasti guasti) throws IOException {
        java.util.Objects.requireNonNull(factory); java.util.Objects.requireNonNull(storage);
        this.domainClock = new ManualClock(T0);
        this.authClock = new ManualClock(AUTH_T0);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        /* Da qui il listener è associato: ogni errore deve rilasciarlo prima di propagarsi. */
        boolean avviato = false;
        try {
            guasti.fase(E2EGuasti.Fase.SERVER_IN_ASCOLTO, Integer.toString(server.getAddress().getPort()));
            this.authConfig = new AuthConfig("local-http", "http://127.0.0.1:" + server.getAddress().getPort());
            Componenti componenti = registra(factory, storage);
            this.sessions = componenti.sessions();
            this.pdfValidator = componenti.pdfValidator();
            server.setExecutor(null);
            server.start();
            avviato = true;
        } catch (IOException | RuntimeException e) {
            try { rilascia(server, avviato); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /*
     * Su JDK 21 stop non chiude il socket di un server mai avviato: occorre avviarlo e fermarlo
     * subito per rilasciare la porta con API pubbliche.
     */
    private static void rilascia(HttpServer server, boolean avviato) {
        if (!avviato) {
            try { server.start(); } catch (IllegalStateException giaAvviato) { /* start ha fallito dopo aver avviato il server. */ }
        }
        server.stop(0);
    }

    private record Componenti(SessionStore sessions, FixturePdfValidator pdfValidator) { }

    private Componenti registra(ConnectionFactory factory, E2EStorage storage) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        PrestazioneController prestazioneController =
                new PrestazioneController(new PrestazioneService(new PrestazioneRepository(factory)), objectMapper);
        StanzaController stanzaController =
                new StanzaController(new StanzaService(new StanzaRepository(factory)), objectMapper);

        DisponibilitaRepository disponibilitaRepository = new DisponibilitaRepository(factory, domainClock);
        DisponibilitaController disponibilitaController = new DisponibilitaController(
                new DisponibilitaService(disponibilitaRepository, domainClock), objectMapper);

        RimborsoService rimborsoService = new RimborsoService(new RimborsoRepository(factory, domainClock));
        RimborsoController rimborsoController = new RimborsoController(rimborsoService, objectMapper);
        PrenotazioneService prenotazioneService =
                new PrenotazioneService(new PrenotazioneRepository(factory, domainClock), domainClock);
        FixturePdfValidator pdfValidator = new FixturePdfValidator(storage.storage().scratch());
        RefertoService refertoService = new RefertoService(
                new RefertoRepository(factory, domainClock), storage.storage(), pdfValidator);
        PrenotazioneController prenotazioneController = new PrenotazioneController(prenotazioneService, objectMapper,
                new PagamentoService(new PagamentoRepository(factory, domainClock,
                        new GatewayPagamentoSimulato(StatoPagamento.PAGATO))), rimborsoService, refertoService);

        SessionStore sessions = new SessionStore(authClock, 10_000);
        AutenticazioneService authService = new AutenticazioneService(
                new UtenteRepository(factory, domainClock), new PasswordHasher(), sessions);
        AutenticazioneController authController = new AutenticazioneController(authService, sessions,
                new LoginThrottle(authClock, 10_000, 10), new LoginThrottle(authClock, 10_000, 10), authConfig, objectMapper);

        server.createContext("/api/auth", authController);
        server.createContext("/api/auth/register", new RegistrazioneController(
                new RegistrazioneService(new RegistrazioneRepository(factory, domainClock), new PasswordHasher(), domainClock),
                new LoginThrottle(authClock, 10_000, 10), authConfig, objectMapper));
        server.createContext("/api/prestazioni", new AutorizzazioneHandler(
                prestazioneController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/medici", new AutorizzazioneHandler(
                new MedicoController(new MedicoService(new MedicoRepository(factory, domainClock), new PasswordHasher()),
                        new UtenteRepository(factory, domainClock), objectMapper), authService, sessions, authConfig, objectMapper));
        server.createContext("/api/utenti", new AutorizzazioneHandler(
                new UtenteController(new UtenteService(new UtenteRepository(factory, domainClock), new PasswordHasher(), sessions),
                        objectMapper), authService, sessions, authConfig, objectMapper));
        server.createContext("/api/stanze", new AutorizzazioneHandler(
                stanzaController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/disponibilita", new AutorizzazioneHandler(
                disponibilitaController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/prenotazioni", new AutorizzazioneHandler(
                prenotazioneController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/rimborsi", new AutorizzazioneHandler(
                rimborsoController, authService, sessions, authConfig, objectMapper));
        return new Componenti(sessions, pdfValidator);
    }

    public URI base() { return URI.create(authConfig.origin()); }
    public AuthConfig authConfig() { return authConfig; }
    public ManualClock domainClock() { return domainClock; }
    public ManualClock authClock() { return authClock; }
    public FixturePdfValidator pdfValidator() { return pdfValidator; }
    /* Accesso in sola lettura, per esempio conteggio sessioni; la creazione passa soltanto dal login HTTP. */
    public int sessioniAttive() { return sessions.size(); }

    /* Chiusura idempotente: il listener viene rilasciato alla prima chiamata. */
    @Override public void close() { if (chiuso.compareAndSet(false, true)) { server.stop(0); } }
}
