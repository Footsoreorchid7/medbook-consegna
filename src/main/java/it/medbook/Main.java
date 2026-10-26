package it.medbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import it.medbook.config.ConnectionFactory;
import it.medbook.config.AuthConfig;
import it.medbook.controller.AutenticazioneController;
import it.medbook.controller.RegistrazioneController;
import it.medbook.repository.RegistrazioneRepository;
import it.medbook.service.RegistrazioneService;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.LoginThrottle;
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import it.medbook.security.AutorizzazioneHandler;
import it.medbook.service.AutenticazioneService;
import it.medbook.controller.DisponibilitaController;
import it.medbook.controller.PrestazioneController;
import it.medbook.controller.StanzaController;
import it.medbook.service.StanzaService;
import it.medbook.repository.StanzaRepository;
import it.medbook.controller.PrenotazioneController;
import it.medbook.controller.RimborsoController;
import it.medbook.repository.RimborsoRepository;
import it.medbook.service.RimborsoService;
import it.medbook.repository.PrenotazioneRepository;
import it.medbook.service.PrenotazioneService;
import it.medbook.service.PagamentoService;
import it.medbook.repository.PagamentoRepository;
import it.medbook.repository.PrestazioneRepository;
import it.medbook.repository.DisponibilitaRepository;
import it.medbook.service.DisponibilitaService;
import it.medbook.service.PrestazioneService;

import java.io.IOException;
import it.medbook.config.ServerConfig;
import java.time.Clock;
import it.medbook.payment.GatewayPagamentoSimulato;

public class Main {
    public static void main(String[] args) throws IOException {
        AuthConfig authConfig = AuthConfig.fromEnvironment();
        ConnectionFactory connectionFactory = new ConnectionFactory();
        PrestazioneRepository prestazioneRepository = new PrestazioneRepository(connectionFactory);
        PrestazioneService prestazioneService = new PrestazioneService(prestazioneRepository);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PrestazioneController prestazioneController =
                new PrestazioneController(prestazioneService, objectMapper);

        StanzaController stanzaController = new StanzaController(
                new StanzaService(new StanzaRepository(connectionFactory)), objectMapper);

        /*
         * Un solo orologio per servizio e repository: il secondo controllo dell'inizio futuro
         * avviene dopo il mutex medico.
         */
        Clock disponibilitaClock = Clock.systemDefaultZone();
        DisponibilitaRepository disponibilitaRepository = new DisponibilitaRepository(connectionFactory, disponibilitaClock);
        DisponibilitaService disponibilitaService = new DisponibilitaService(disponibilitaRepository, disponibilitaClock);
        DisponibilitaController disponibilitaController =
                new DisponibilitaController(disponibilitaService, objectMapper);

        RimborsoService rimborsoService = new RimborsoService(
                new RimborsoRepository(connectionFactory));
        RimborsoController rimborsoController =
                new RimborsoController(rimborsoService, objectMapper);
        PrenotazioneRepository prenotazioneRepository = new PrenotazioneRepository(connectionFactory);
        PrenotazioneService prenotazioneService = new PrenotazioneService(prenotazioneRepository);
        String directory = System.getenv("MEDBOOK_REFERTI_DIRECTORY");
        String executable = System.getenv("MEDBOOK_QPDF_EXECUTABLE");
        if (directory == null || directory.isBlank() || executable == null || executable.isBlank()) {
            throw new IllegalStateException("Richiesti MEDBOOK_REFERTI_DIRECTORY e MEDBOOK_QPDF_EXECUTABLE.");
        }
        it.medbook.referto.RefertoStorage storage = new it.medbook.referto.RefertoStorage(java.nio.file.Path.of(directory));
        it.medbook.referto.QpdfValidator qpdf = new it.medbook.referto.QpdfValidator(
                java.nio.file.Path.of(executable), java.time.Duration.ofSeconds(30));
        qpdf.verifyVersion();
        it.medbook.service.RefertoService refertoService = new it.medbook.service.RefertoService(
                new it.medbook.repository.RefertoRepository(connectionFactory, Clock.systemDefaultZone()), storage,
                new it.medbook.referto.PdfValidator(qpdf, storage.scratch()));
        PrenotazioneController prenotazioneController =
                new PrenotazioneController(prenotazioneService, objectMapper,
                        new PagamentoService(
                                new PagamentoRepository(connectionFactory, Clock.systemDefaultZone(),
                                        GatewayPagamentoSimulato.fromEnvironment())), rimborsoService, refertoService);

        Clock authClock = Clock.systemUTC();
        SessionStore sessions = new SessionStore(authClock, 10_000);
        AutenticazioneService authService = new AutenticazioneService(
                new UtenteRepository(connectionFactory, Clock.systemDefaultZone()), new PasswordHasher(), sessions);
        /* Limitatore dedicato ai cambi password, per account, con gli stessi limiti del login. */
        AutenticazioneController authController = new AutenticazioneController(authService, sessions,
                new LoginThrottle(authClock, 10_000, 10), new LoginThrottle(authClock, 10_000, 10), authConfig, objectMapper);

        // Il listener è locale per default; un indirizzo diverso richiede un override esplicito.
        // Ogni contesto clinico passa dal controllo di metodo, percorso, sessione e permessi.
        HttpServer server = HttpServer.create(new ServerConfig().address(), 0);
        server.createContext("/api/auth", authController);
        // Contesto più specifico: il controller rifiuta ogni sottopercorso e metodo diverso da POST.
        server.createContext("/api/auth/register", new RegistrazioneController(
                new RegistrazioneService(new RegistrazioneRepository(connectionFactory, Clock.systemDefaultZone()), new PasswordHasher()),
                new LoginThrottle(authClock, 10_000, 10), authConfig, objectMapper));
        server.createContext("/api/prestazioni", new AutorizzazioneHandler(
                prestazioneController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/medici", new AutorizzazioneHandler(
                new it.medbook.controller.MedicoController(
                        new it.medbook.service.MedicoService(new it.medbook.repository.MedicoRepository(connectionFactory,Clock.systemDefaultZone()),new PasswordHasher(),sessions),
                        new UtenteRepository(connectionFactory, Clock.systemDefaultZone()),objectMapper),authService,sessions,authConfig,objectMapper));
        server.createContext("/api/pazienti", new AutorizzazioneHandler(
                new it.medbook.controller.PazienteDeskController(new it.medbook.service.PazienteDeskService(
                        new it.medbook.repository.PazienteDeskRepository(connectionFactory),new PasswordHasher(),sessions),objectMapper),
                authService,sessions,authConfig,objectMapper));
        server.createContext("/api/utenti", new AutorizzazioneHandler(
                new it.medbook.controller.UtenteController(new it.medbook.service.UtenteService(
                        new UtenteRepository(connectionFactory, Clock.systemDefaultZone()),new PasswordHasher(),sessions),objectMapper),authService,sessions,authConfig,objectMapper));
        server.createContext("/api/stanze", new AutorizzazioneHandler(
                stanzaController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/disponibilita", new AutorizzazioneHandler(
                disponibilitaController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/prenotazioni", new AutorizzazioneHandler(
                prenotazioneController, authService, sessions, authConfig, objectMapper));
        server.createContext("/api/rimborsi", new AutorizzazioneHandler(
                rimborsoController, authService, sessions, authConfig, objectMapper));
        server.setExecutor(null);
        server.start();

        System.out.println("MedBook avviato su http://localhost:8080");
    }
}
