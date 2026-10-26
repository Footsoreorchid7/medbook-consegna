package it.medbook.demo;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.AccountInternoRequest;
import it.medbook.dto.CollegamentoMedicoRequest;
import it.medbook.dto.NuovoMedicoRequest;
import it.medbook.dto.PrestazioneRequest;
import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.dto.StanzaRequest;
import it.medbook.model.Disponibilita;
import it.medbook.model.ModalitaPagamento;
import it.medbook.model.Prenotazione;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.StatoPagamento;
import it.medbook.model.StatoPrenotazione;
import it.medbook.payment.GatewayPagamentoSimulato;
import it.medbook.referto.PdfValidator;
import it.medbook.referto.QpdfValidator;
import it.medbook.referto.RefertoStorage;
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
import it.medbook.security.PasswordHasher;
import it.medbook.security.SessionStore;
import it.medbook.service.AutenticazioneService;
import it.medbook.service.BootstrapService;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
 * Caricamento esplicito dei dati fittizi della demo Docker. Usa i servizi applicativi con un
 * orologio controllato: disponibilità, richieste, esiti e referti attraversano le stesse regole
 * di dominio dell'interfaccia, in una cronologia calcolata rispetto al momento del caricamento.
 * Agisce solo su un catalogo marcato dall'inizializzazione della demo e non ripete il caricamento.
 */
public final class DatiDimostrativi {
    static final int OK = 0, ERRORE = 1, RESET_RICHIESTO = 3, DESTINAZIONE_NON_RICONOSCIUTA = 4;
    private static final String CATALOGO = "medbook";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DatiDimostrativi() { }

    public static void main(String[] args) {
        int esito;
        try {
            if (args.length == 1 && "stato".equals(args[0])) { esito = stato(); }
            else if (args.length == 0) { esito = carica(); }
            else { System.err.println("Uso: DatiDimostrativi [stato]"); esito = ERRORE; }
        } catch (Rifiuto e) {
            System.err.println(e.getMessage());
            esito = e.codice;
        } catch (Exception e) {
            System.err.println("Errore: " + descrivi(e));
            esito = ERRORE;
        }
        System.exit(esito);
    }

    /* Errore sintetico, senza SQL completo né valori sensibili. */
    static String descrivi(Throwable e) {
        String messaggio = e.getMessage() == null ? "" : ": " + e.getMessage().lines().findFirst().orElse("");
        return e.getClass().getSimpleName() + (messaggio.length() > 200 ? messaggio.substring(0, 200) : messaggio);
    }

    static final class Rifiuto extends Exception {
        private static final long serialVersionUID = 1L;
        final int codice;
        Rifiuto(int codice, String messaggio) { super(messaggio); this.codice = codice; }
    }

    private static String ambiente(String nome) throws Rifiuto {
        String valore = System.getenv(nome);
        if (valore == null || valore.isBlank()) { throw new Rifiuto(ERRORE, "Configurazione mancante: " + nome + "."); }
        return valore.trim();
    }

    private record Marcatore(String codice, String serverUuid, String stato, LocalDateTime aggiornatoIl) { }

    /* Il catalogo deve essere quello creato dall'inizializzazione di questa demo, sullo stesso server. */
    private static Marcatore marcatore(ConnectionFactory database, String codiceAtteso) throws Rifiuto, SQLException {
        try (Connection c = database.getConnection()) {
            String catalogo, serverUuid;
            try (PreparedStatement s = c.prepareStatement("SELECT DATABASE(), @@server_uuid"); ResultSet r = s.executeQuery()) {
                r.next(); catalogo = r.getString(1); serverUuid = r.getString(2);
            }
            if (!CATALOGO.equals(catalogo)) {
                throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA, "Catalogo inatteso: è ammesso soltanto «" + CATALOGO + "». Nessuna modifica.");
            }
            Marcatore m;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT codice, server_uuid, stato, aggiornato_il FROM medbook_demo.installazione WHERE id = 1");
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA, "Marcatore della demo assente. Nessuna modifica."); }
                m = new Marcatore(r.getString(1), r.getString(2), r.getString(3), r.getObject(4, LocalDateTime.class));
            } catch (SQLException e) {
                throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA,
                        "Database non riconosciuto come demo MedBook (marcatore non leggibile). Nessuna modifica.");
            }
            if (!m.codice().equals(codiceAtteso) || !m.serverUuid().equals(serverUuid)) {
                throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA,
                        "Il database raggiunto non appartiene a questa configurazione della demo. Nessuna modifica.");
            }
            return m;
        }
    }

    private static int stato() throws Exception {
        Marcatore m = marcatore(new ConnectionFactory(), ambiente("MEDBOOK_DEMO_INSTALLAZIONE"));
        System.out.println("Stato della demo: " + m.stato() + " (aggiornato il " + m.aggiornatoIl() + ").");
        return OK;
    }

    private static boolean transizione(ConnectionFactory database, String codice, String da, String a, String messaggio)
            throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE medbook_demo.installazione SET stato = ?, aggiornato_il = ?, messaggio = ? "
                        + "WHERE id = 1 AND codice = ? AND stato = ?")) {
            s.setString(1, a); s.setObject(2, LocalDateTime.now().withNano(0)); s.setString(3, messaggio);
            s.setString(4, codice); s.setString(5, da);
            return s.executeUpdate() == 1;
        }
    }

    private static int carica() throws Exception {
        String codice = ambiente("MEDBOOK_DEMO_INSTALLAZIONE");
        Path credenziali = Path.of(ambiente("MEDBOOK_DEMO_CREDENZIALI"));
        Path referti = Path.of(ambiente("MEDBOOK_REFERTI_DIRECTORY"));
        Path qpdf = Path.of(ambiente("MEDBOOK_QPDF_EXECUTABLE"));
        ConnectionFactory database = new ConnectionFactory();

        Marcatore m = marcatore(database, codice);
        switch (m.stato()) {
            case "DATI_COMPLETATI" -> {
                System.out.println("Dati dimostrativi già caricati il " + m.aggiornatoIl() + ": nessuna modifica.");
                return OK;
            }
            case "SCHEMA_PRONTO" -> { }
            default -> throw new Rifiuto(RESET_RICHIESTO, "Stato della demo «" + m.stato()
                    + "»: un caricamento precedente non è terminato. Eseguire il reset esplicito (scripts/demo/reset.sh).");
        }
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM utente) + (SELECT COUNT(*) FROM prestazione) + (SELECT COUNT(*) FROM stanza)");
             ResultSet r = s.executeQuery()) {
            r.next();
            if (r.getLong(1) != 0) {
                throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA, "Il catalogo contiene dati non attesi. Nessuna modifica.");
            }
        }
        Path documenti = referti.resolve("documents");
        if (Files.isDirectory(documenti)) {
            try (var elenco = Files.list(documenti)) {
                if (elenco.findAny().isPresent()) {
                    throw new Rifiuto(DESTINAZIONE_NON_RICONOSCIUTA, "L'archivio dei referti non è vuoto. Nessuna modifica.");
                }
            }
        }
        Map<String, Account> account = Account.leggi(credenziali);

        if (!transizione(database, codice, "SCHEMA_PRONTO", "DATI_IN_CORSO", null)) {
            throw new Rifiuto(RESET_RICHIESTO, "Lo stato della demo è cambiato durante la verifica. Nessuna modifica.");
        }
        try {
            Scenario scenario = new Scenario(database, account, referti, qpdf, ZoneId.systemDefault());
            String riepilogo = scenario.esegui();
            if (!transizione(database, codice, "DATI_IN_CORSO", "DATI_COMPLETATI", null)) {
                throw new IllegalStateException("Marcatore modificato durante il caricamento.");
            }
            System.out.println(riepilogo);
            return OK;
        } catch (Exception e) {
            String motivo = descrivi(e);
            try { transizione(database, codice, "DATI_IN_CORSO", "DATI_FALLITI", motivo.length() > 255 ? motivo.substring(0, 255) : motivo); }
            catch (SQLException registrazione) { e.addSuppressed(registrazione); }
            System.err.println("Caricamento interrotto: la demo resta nello stato DATI_FALLITI. Eseguire il reset esplicito.");
            throw e;
        }
    }

    /* Account dimostrativi letti dal file locale generato da scripts/demo/configura.sh. */
    record Account(String ruolo, String email, String password) {
        static final List<String> RUOLI = List.of("amministratore", "desk", "cardiologia", "dermatologia",
                "medicina-generale", "paziente", "paziente2", "medico-paziente");

        static Map<String, Account> leggi(Path file) throws IOException, Rifiuto {
            Map<String, Account> esito = new LinkedHashMap<>();
            for (String riga : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String testo = riga.strip();
                if (testo.isEmpty() || testo.startsWith("#")) { continue; }
                String[] parti = testo.split("\\s+");
                if (parti.length != 3 || !RUOLI.contains(parti[0]) || esito.containsKey(parti[0])) {
                    throw new Rifiuto(ERRORE, "File delle credenziali dimostrative non valido.");
                }
                esito.put(parti[0], new Account(parti[0], parti[1], parti[2]));
            }
            if (!esito.keySet().containsAll(RUOLI)) { throw new Rifiuto(ERRORE, "File delle credenziali dimostrative incompleto."); }
            return esito;
        }
    }

    /* Orologio impostato esplicitamente: i servizi leggono il momento simulato di ogni operazione. */
    static final class Orologio extends Clock {
        private final ZoneId zona;
        private volatile Instant istante;
        Orologio(ZoneId zona, LocalDateTime inizio) { this.zona = zona; imposta(inizio); }
        void imposta(LocalDateTime momento) { istante = momento.atZone(zona).toInstant(); }
        @Override public ZoneId getZone() { return zona; }
        @Override public Clock withZone(ZoneId altra) { return Clock.fixed(istante, altra); }
        @Override public Instant instant() { return istante; }
    }

    static final class Scenario {
        private final ConnectionFactory database;
        private final Map<String, Account> account;
        private final Orologio orologio;
        private final LocalDate oggi;
        private final PasswordHasher hasher = new PasswordHasher();
        private final UtenteRepository utenti;
        private final AutenticazioneService autenticazione;
        private final MedicoService medici;
        private final DisponibilitaService disponibilita;
        private final PrenotazioneService prenotazioni;
        private final PagamentoService pagamenti, pagamentiFalliti;
        private final RimborsoService rimborsi;
        private final RefertoService refertiService;
        private final RegistrazioneService registrazioni;
        private final Map<String, Integer> id = new HashMap<>();
        private final Map<String, Integer> slot = new HashMap<>();
        private final Map<Integer, BigDecimal> dovuti = new HashMap<>();
        private int contaPrenotazioni, contaPagamenti, contaRimborsi;

        Scenario(ConnectionFactory database, Map<String, Account> account, Path referti, Path qpdf, ZoneId zona)
                throws IOException {
            this.database = database;
            this.account = account;
            this.oggi = LocalDate.now(zona);
            this.orologio = new Orologio(zona, lavorativo(-8).atTime(8, 0));
            utenti = new UtenteRepository(database, orologio);
            autenticazione = new AutenticazioneService(utenti, hasher, new SessionStore(orologio, 100));
            medici = new MedicoService(new MedicoRepository(database, orologio), hasher);
            disponibilita = new DisponibilitaService(new DisponibilitaRepository(database, orologio), orologio);
            prenotazioni = new PrenotazioneService(new PrenotazioneRepository(database, orologio), orologio);
            pagamenti = new PagamentoService(new PagamentoRepository(database, orologio, new GatewayPagamentoSimulato(StatoPagamento.PAGATO)));
            pagamentiFalliti = new PagamentoService(new PagamentoRepository(database, orologio, new GatewayPagamentoSimulato(StatoPagamento.FALLITO)));
            rimborsi = new RimborsoService(new RimborsoRepository(database, orologio));
            registrazioni = new RegistrazioneService(new RegistrazioneRepository(database, orologio), hasher, orologio);
            RefertoStorage storage = new RefertoStorage(referti);
            QpdfValidator validatore = new QpdfValidator(qpdf, Duration.ofSeconds(30));
            validatore.verifyVersion();
            refertiService = new RefertoService(new RefertoRepository(database, orologio), storage,
                    new PdfValidator(validatore, storage.scratch()));
        }

        /* Giorni feriali (lunedì-sabato) contati da oggi: negativi nel passato, positivi nel futuro. */
        private LocalDate lavorativo(int n) {
            LocalDate giorno = oggi;
            for (int passi = Math.abs(n); passi > 0; ) {
                giorno = n < 0 ? giorno.minusDays(1) : giorno.plusDays(1);
                if (giorno.getDayOfWeek() != DayOfWeek.SUNDAY) { passi--; }
            }
            return giorno;
        }

        private void alle(LocalDateTime momento) { orologio.imposta(momento); }

        String esegui() throws Exception {
            anagrafiche();
            catalogo();
            calendario();
            storico();
            prossimiAppuntamenti();
            return "Dati dimostrativi caricati: " + account.size() + " account, " + slot.size() + " disponibilità, "
                    + contaPrenotazioni + " prenotazioni, " + contaPagamenti + " pagamenti, "
                    + contaRimborsi + " rimborsi, 1 referto. Giorno di riferimento " + oggi.format(DATA)
                    + ", fuso " + orologio.getZone() + ".";
        }

        private int accedi(String ruolo) throws SQLException {
            Account a = account.get(ruolo);
            Optional<SessionStore.Session> sessione = autenticazione.login(a.email(), a.password().toCharArray());
            return sessione.orElseThrow(() -> new IllegalStateException("Accesso dimostrativo non riuscito: " + ruolo + ".")).idUtente();
        }

        private void anagrafiche() throws Exception {
            Account admin = account.get("amministratore");
            // Primo amministratore con lo stesso servizio del comando interattivo, conferma inclusa.
            List<String> risposte = new ArrayList<>(List.of("Alessandra", "Ferri", admin.email(), CATALOGO));
            int esito = new BootstrapService(utenti, hasher).esegui(new BootstrapService.Terminale() {
                @Override public String leggiRiga(String richiesta) { return risposte.isEmpty() ? null : risposte.remove(0); }
                @Override public char[] leggiPassword(String richiesta) { return admin.password().toCharArray(); }
                @Override public void scrivi(String messaggio) { }
            });
            if (esito != BootstrapService.CREATO) { throw new IllegalStateException("Bootstrap amministratore: codice " + esito + "."); }
            int amministratore = accedi("amministratore");
            id.put("amministratore", amministratore);

            UtenteService gestione = new UtenteService(utenti, hasher);
            Account desk = account.get("desk");
            id.put("desk", gestione.crea(amministratore, new AccountInternoRequest("Davide", "Sartori", desk.email(),
                    desk.password(), RuoloGestionale.OPERATORE_DESK)).idUtente());

            medico("cardiologia", "Marta", "Galli", "Cardiologia", "ORD-DEMO-0001");
            medico("dermatologia", "Luca", "Moretti", "Dermatologia", "ORD-DEMO-0002");
            medico("medicina-generale", "Elena", "Conti", "Medicina generale", "ORD-DEMO-0003");

            // Codici fiscali sintetici: formalmente validi, con codice comune Z999 non assegnato.
            paziente("paziente", "Giulia", "Neri", "NREGLI91D45Z999E", LocalDate.of(1991, 4, 5), "+39 000 0000001");
            paziente("paziente2", "Paolo", "Rinaldi", "RNLPLA78M12Z999N", LocalDate.of(1978, 8, 12), null);
            paziente("medico-paziente", "Sara", "Fontana", "FNTSRA85B50Z999Q", LocalDate.of(1985, 2, 10), "+39 000 0000003");
            medici.collega(amministratore, id.get("medico-paziente"),
                    new CollegamentoMedicoRequest("Medicina generale", "ORD-DEMO-0004"));
        }

        private void medico(String ruolo, String nome, String cognome, String specializzazione, String ordine) throws SQLException {
            Account a = account.get(ruolo);
            id.put(ruolo, medici.crea(id.get("amministratore"),
                    new NuovoMedicoRequest(nome, cognome, a.email(), a.password(), specializzazione, ordine)).idUtente());
        }

        private void paziente(String ruolo, String nome, String cognome, String cf, LocalDate nascita, String telefono)
                throws SQLException {
            Account a = account.get(ruolo);
            try (RegistrazionePazienteRequest richiesta = new RegistrazionePazienteRequest(nome, cognome, a.email(),
                    a.password().toCharArray(), cf, nascita, telefono)) {
                registrazioni.registra(richiesta);
            }
            id.put(ruolo, accedi(ruolo));
        }

        private void catalogo() throws SQLException {
            int amministratore = id.get("amministratore");
            StanzaService stanze = new StanzaService(new StanzaRepository(database));
            stanza(stanze, "ambulatorio-1", "Ambulatorio 1", "Piano terra, area cardiologica");
            stanza(stanze, "ambulatorio-2", "Ambulatorio 2", "Primo piano, area dermatologica");
            stanza(stanze, "ambulatorio-3", "Ambulatorio 3", null);
            PrestazioneService catalogo = new PrestazioneService(new PrestazioneRepository(database));
            prestazione(catalogo, "cardiologica", "Visita cardiologica", "Visita specialistica con valutazione clinica.", 30, "90.00");
            prestazione(catalogo, "ecg", "Elettrocardiogramma", "Esame strumentale di base.", 20, "45.00");
            prestazione(catalogo, "dermatologica", "Visita dermatologica", "Visita specialistica della pelle.", 30, "80.00");
            prestazione(catalogo, "nei", "Controllo dei nei", null, 20, "60.00");
            prestazione(catalogo, "generale", "Visita di medicina generale", "Visita ambulatoriale di base.", 30, "50.00");
            abilita(amministratore, "cardiologia", "cardiologica", "ecg");
            abilita(amministratore, "dermatologia", "dermatologica", "nei");
            abilita(amministratore, "medicina-generale", "generale", "cardiologica", "ecg");
            abilita(amministratore, "medico-paziente", "generale");
        }

        private void stanza(StanzaService servizio, String chiave, String nome, String descrizione) throws SQLException {
            StanzaRequest r = new StanzaRequest(); r.setNome(nome); r.setDescrizione(descrizione);
            id.put("stanza:" + chiave, servizio.creaStanza(r).getIdStanza());
        }

        private void prestazione(PrestazioneService servizio, String chiave, String nome, String descrizione, int durata,
                                 String prezzo) throws SQLException {
            PrestazioneRequest r = new PrestazioneRequest();
            r.setNome(nome); r.setDescrizione(descrizione); r.setDurata(durata); r.setPrezzo(new BigDecimal(prezzo));
            id.put("prestazione:" + chiave, servizio.creaPrestazione(r).getIdPrestazione());
        }

        private void abilita(int amministratore, String medico, String... prestazioniAbilitate) throws SQLException {
            for (String p : prestazioniAbilitate) { medici.assegnaPrestazione(amministratore, id.get(medico), id.get("prestazione:" + p)); }
        }

        /* Disponibilità create dai medici e configurate dalla segreteria, nel passato recente e nelle prossime settimane. */
        private void calendario() throws SQLException {
            for (int g = -6; g <= 12; g++) {
                if (g == 0) { continue; }
                LocalDate giorno = lavorativo(g);
                crea("cardiologia", giorno, 9, 13);
                crea("dermatologia", giorno, 14, 18);
                for (int ora = 9; ora < 13; ora++) {
                    configura("cardiologia", giorno, ora, ora < 11 ? "cardiologica" : "ecg", "ambulatorio-1");
                }
                // Gli ultimi pomeriggi della dermatologia restano da configurare per la segreteria.
                if (g <= 10) {
                    for (int ora = 14; ora < 18; ora++) {
                        configura("dermatologia", giorno, ora, ora < 16 ? "dermatologica" : "nei", "ambulatorio-2");
                    }
                }
                if (g > 0) {
                    crea("medicina-generale", giorno, 15, 18);
                    for (int ora = 15; ora < 18; ora++) { configura("medicina-generale", giorno, ora, "generale", "ambulatorio-3"); }
                }
            }
            for (int g : new int[] {2, 4, 6}) {
                LocalDate giorno = lavorativo(g);
                crea("medico-paziente", giorno, 9, 11);
                if (g == 6) { for (int ora = 9; ora < 11; ora++) { configura("medico-paziente", giorno, ora, "generale", "ambulatorio-3"); } }
            }
        }

        private void crea(String medico, LocalDate giorno, int da, int a) throws SQLException {
            for (Disponibilita d : disponibilita.creaDisponibilita(id.get(medico), giorno.atTime(da, 0), giorno.atTime(a, 0))) {
                slot.put(medico + "@" + d.getDataOraInizio(), d.getIdDisponibilita());
            }
        }

        private int slotDi(String medico, LocalDate giorno, int ora) {
            Integer s = slot.get(medico + "@" + giorno.atTime(ora, 0));
            if (s == null) { throw new IllegalStateException("Disponibilità attesa assente: " + medico + " " + giorno + " " + ora + "."); }
            return s;
        }

        private void configura(String medico, LocalDate giorno, int ora, String prestazione, String stanza) throws SQLException {
            disponibilita.configuraDisponibilita(slotDi(medico, giorno, ora), id.get("prestazione:" + prestazione), id.get("stanza:" + stanza));
        }

        private int richiedi(String paziente, String medico, LocalDate giorno, int ora) throws SQLException {
            Prenotazione p = prenotazioni.richiediAppuntamento(id.get(paziente), slotDi(medico, giorno, ora));
            dovuti.put(p.getIdPrenotazione(), p.getPrezzo());
            contaPrenotazioni++;
            return p.getIdPrenotazione();
        }

        private void conferma(int prenotazione) throws SQLException { prenotazioni.confermaAppuntamento(prenotazione, id.get("desk")); }

        private void pagaOnline(String paziente, int prenotazione) throws SQLException {
            pagamenti.pagaOnline(prenotazione, id.get(paziente), dovuti.get(prenotazione)); contaPagamenti++;
        }

        /* Appuntamenti già trascorsi con tutti gli esiti previsti. */
        private void storico() throws Exception {
            LocalDate g1 = lavorativo(-6), g2 = lavorativo(-5), g3 = lavorativo(-4), g4 = lavorativo(-3), g5 = lavorativo(-2), g6 = lavorativo(-1);
            alle(lavorativo(-8).atTime(9, 0));
            int effettuata = richiedi("paziente", "cardiologia", g1, 9);
            int nonErogataApprovata = richiedi("paziente", "dermatologia", g2, 14);
            int nonErogataRespinta = richiedi("paziente2", "cardiologia", g3, 10);
            int assente = richiedi("paziente2", "cardiologia", g4, 11);
            int effettuataInSede = richiedi("paziente", "dermatologia", g5, 16);
            int rimborsoInAttesa = richiedi("paziente", "cardiologia", g5, 9);
            int daRegistrare = richiedi("paziente2", "cardiologia", g6, 10);
            int esitoDaRegistrare = richiedi("paziente", "cardiologia", g6, 11);
            int effettuataContanti = richiedi("medico-paziente", "dermatologia", g6, 15);
            int annullataPaziente = richiedi("paziente", "dermatologia", g3, 14);
            alle(lavorativo(-8).atTime(10, 30));
            for (int p : new int[] {effettuata, nonErogataApprovata, nonErogataRespinta, assente, effettuataInSede,
                    rimborsoInAttesa, daRegistrare, esitoDaRegistrare, effettuataContanti}) { conferma(p); }
            prenotazioni.annullaAppuntamentoPaziente(annullataPaziente, id.get("paziente"), StatoPrenotazione.RICHIESTA);
            alle(lavorativo(-8).atTime(18, 0));
            pagaOnline("paziente", effettuata);
            pagaOnline("paziente", nonErogataApprovata);
            pagaOnline("paziente2", nonErogataRespinta);
            pagaOnline("paziente", rimborsoInAttesa);

            alle(g1.atTime(10, 15));
            prenotazioni.registraVisita(effettuata, id.get("cardiologia"));
            alle(g1.atTime(12, 30));
            byte[] pdf = refertoPdf("Giulia Neri", "Visita cardiologica", g1, "Marta Galli");
            refertiService.upload(effettuata, id.get("cardiologia"), new ByteArrayInputStream(pdf), (long) pdf.length);

            alle(g2.atTime(16, 0));
            esito(nonErogataApprovata, StatoPrenotazione.NON_EROGATA);
            alle(g2.atTime(19, 0));
            int primo = rimborsi.richiedi(nonErogataApprovata, id.get("paziente"),
                    "La visita non è stata svolta per indisponibilità dell'ambulatorio.").getIdRimborso();
            contaRimborsi++;
            alle(g3.atTime(9, 30));
            rimborsi.approva(primo, id.get("desk"));

            alle(g3.atTime(12, 0));
            esito(nonErogataRespinta, StatoPrenotazione.NON_EROGATA);
            alle(g3.atTime(20, 0));
            int secondo = rimborsi.richiedi(nonErogataRespinta, id.get("paziente2"),
                    "Richiedo il rimborso della visita non svolta.").getIdRimborso();
            contaRimborsi++;
            alle(g4.atTime(9, 45));
            rimborsi.respingi(secondo, id.get("desk"));

            alle(g4.atTime(13, 0));
            esito(assente, StatoPrenotazione.PAZIENTE_ASSENTE);

            alle(g5.atTime(8, 30));
            new PagamentoService(new PagamentoRepository(database, orologio)).registraPagamentoInSede(effettuataInSede, id.get("desk"),
                    ModalitaPagamento.DESK_POS, dovuti.get(effettuataInSede));
            contaPagamenti++;
            alle(g5.atTime(10, 30));
            esito(rimborsoInAttesa, StatoPrenotazione.NON_EROGATA);
            alle(g5.atTime(17, 20));
            prenotazioni.registraVisita(effettuataInSede, id.get("dermatologia"));
            alle(g5.atTime(18, 30));
            rimborsi.richiedi(rimborsoInAttesa, id.get("paziente"), "Appuntamento non erogato: chiedo la restituzione dell'importo.");
            contaRimborsi++;

            alle(g6.atTime(14, 50));
            new PagamentoService(new PagamentoRepository(database, orologio)).registraPagamentoInSede(effettuataContanti, id.get("desk"),
                    ModalitaPagamento.DESK_CONTANTI, dovuti.get(effettuataContanti));
            contaPagamenti++;
            alle(g6.atTime(16, 5));
            prenotazioni.registraVisita(effettuataContanti, id.get("dermatologia"));
            // daRegistrare ed esitoDaRegistrare restano confermate e trascorse: visita o esito si registrano nella demo.
        }

        private void esito(int prenotazione, StatoPrenotazione esito) throws SQLException {
            prenotazioni.verificaMancataErogazione(prenotazione, id.get("desk"), StatoPrenotazione.CONFERMATA, esito);
        }

        /* Richieste e appuntamenti futuri in tutti gli stati gestibili dalla demo. */
        private void prossimiAppuntamenti() throws SQLException {
            LocalDate f2 = lavorativo(2), f3 = lavorativo(3), f4 = lavorativo(4), f5 = lavorativo(5), f6 = lavorativo(6), f7 = lavorativo(7);
            LocalDateTime base = oggi.minusDays(1).atTime(9, 0);
            alle(base);
            richiedi("paziente", "cardiologia", f2, 9);
            richiedi("paziente2", "dermatologia", f3, 14);
            richiedi("medico-paziente", "cardiologia", f2, 10);
            int daPagare = richiedi("paziente", "cardiologia", f3, 11);
            int pagamentoFallito = richiedi("paziente2", "dermatologia", f4, 16);
            int segnalataPendente = richiedi("paziente", "cardiologia", f5, 9);
            int sostituita = richiedi("paziente2", "cardiologia", f5, 10);
            int rifiutata = richiedi("paziente", "dermatologia", f6, 14);
            int annullataDesk = richiedi("paziente2", "cardiologia", f6, 9);
            int annullataPaziente = richiedi("paziente", "dermatologia", f7, 15);
            int pagataMedicoPaziente = richiedi("medico-paziente", "cardiologia", f4, 9);

            alle(base.plusHours(1));
            for (int p : new int[] {daPagare, pagamentoFallito, segnalataPendente, sostituita, annullataDesk, pagataMedicoPaziente}) {
                conferma(p);
            }
            prenotazioni.rifiutaAppuntamento(rifiutata, id.get("desk"));
            alle(base.plusHours(2));
            pagamentiFalliti.pagaOnline(pagamentoFallito, id.get("paziente2"), dovuti.get(pagamentoFallito));
            contaPagamenti++;
            pagaOnline("paziente", segnalataPendente);
            pagaOnline("medico-paziente", pagataMedicoPaziente);
            prenotazioni.annullaAppuntamentoPaziente(annullataPaziente, id.get("paziente"), StatoPrenotazione.RICHIESTA);
            alle(base.plusHours(3));
            prenotazioni.segnalaIndisponibilita(id.get("cardiologia"), List.of(segnalataPendente, sostituita));
            alle(base.plusHours(4));
            prenotazioni.sostituisciMedico(sostituita, id.get("desk"), id.get("medicina-generale"),
                    StatoPrenotazione.CONFERMATA, id.get("cardiologia"));
            prenotazioni.annullaAppuntamento(annullataDesk, id.get("desk"), StatoPrenotazione.CONFERMATA);
        }

        /* PDF sintetico generato al momento: nessun documento o dato clinico reale. */
        private static byte[] refertoPdf(String paziente, String prestazione, LocalDate data, String medico) throws IOException {
            try (PDDocument documento = new PDDocument(); ByteArrayOutputStream uscita = new ByteArrayOutputStream()) {
                PDPage pagina = new PDPage(PDRectangle.A4);
                documento.addPage(pagina);
                try (PDPageContentStream testo = new PDPageContentStream(documento, pagina)) {
                    testo.beginText();
                    testo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                    testo.newLineAtOffset(60, 770);
                    testo.showText("MedBook - Referto dimostrativo");
                    testo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    for (String riga : List.of("", "Documento sintetico generato per la demo locale.",
                            "Non contiene dati clinici reali e non ha valore sanitario.", "",
                            "Paziente: " + paziente, "Prestazione: " + prestazione, "Data della visita: " + data.format(DATA),
                            "Medico: " + medico, "", "Esito: nessun reperto rilevante (testo di esempio).")) {
                        testo.newLineAtOffset(0, -18);
                        testo.showText(riga);
                    }
                    testo.endText();
                }
                documento.save(uscita);
                return uscita.toByteArray();
            }
        }
    }
}
