package it.medbook.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PasswordHasher;
import it.medbook.service.BootstrapService;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/*
 * Fixture clinica per classe su catalogo esclusivo. Primo amministratore tramite bootstrap, tutto
 * il resto tramite API e login reali, senza INSERT SQL. Orologio di dominio prima a T0, poi solo in
 * avanti a T1; autenticazione separata. Le prove modificative richiedono una fixture propria.
 * Preparazione fallita: client chiusi ed errore propagato; l'ambiente rimuove catalogo e storage
 * anche parziali.
 */
public final class E2EFixtureClinica implements AutoCloseable {
    public static final Instant T0 = E2EServer.T0;
    public static final Instant T1 = Instant.parse("2031-01-20T13:30:00Z");

    /* Attori sintetici: pazienti, medici, profili misti, desk e amministratore. */
    public static final List<String> ATTORI = List.of("AD", "DK", "P1", "P2", "MT", "MS", "MX", "PM", "MI");
    static final Map<String, String> RUOLO_EMAIL = Map.of("AD", "admin", "DK", "desk", "P1", "pazienteuno",
            "P2", "pazientedue", "MT", "titolare", "MS", "sostituto", "MX", "estraneo", "PM", "doppio", "MI", "inattivabile");

    /* Abilitazioni: MT, MS, PM e MI su Q1; MX solo su Q2. */
    public static final Map<String, String> ABILITAZIONI = Map.of("MT", "Q1", "MS", "Q1", "PM", "Q1", "MI", "Q1", "MX", "Q2");

    /* Slot di 60 minuti: titolare, inizio e stanza; stanza null significa non configurato. */
    public record DefSlot(String codice, String titolare, LocalDateTime inizio, String stanza) { }

    public static final List<DefSlot> SLOT = List.of(
            new DefSlot("s1", "MT", LocalDateTime.parse("2031-01-20T08:00"), "S1"),
            new DefSlot("s2", "MT", LocalDateTime.parse("2031-01-20T09:00"), "S1"),
            new DefSlot("s3", "MT", LocalDateTime.parse("2031-01-20T10:00"), "S1"),
            new DefSlot("s4", "MT", LocalDateTime.parse("2031-01-20T11:00"), "S1"),
            new DefSlot("s6", "MT", LocalDateTime.parse("2031-01-20T12:00"), "S1"),
            new DefSlot("s5", "PM", LocalDateTime.parse("2031-01-20T08:00"), "S2"),
            new DefSlot("s14", "MI", LocalDateTime.parse("2031-01-20T09:00"), "S3"),
            new DefSlot("s7", "MT", LocalDateTime.parse("2031-02-05T09:00"), "S1"),
            new DefSlot("s8", "MT", LocalDateTime.parse("2031-02-05T10:00"), "S1"),
            new DefSlot("s13", "MT", LocalDateTime.parse("2031-02-05T11:00"), "S1"),
            new DefSlot("s9", "MT", LocalDateTime.parse("2031-02-06T09:00"), null),
            new DefSlot("s10", "MT", LocalDateTime.parse("2031-02-06T10:00"), null),
            new DefSlot("s12", "MI", LocalDateTime.parse("2031-02-06T09:00"), null));

    /* Prenotazione con stati attesi a T0 e T1 e medico effettivo a T1. */
    public record DefPrenotazione(String codice, String slot, String paziente, String statoT0, String statoT1,
                                  String effettivoT1, boolean sostituita, String visitaDa, String referto) { }

    public static final List<DefPrenotazione> PRENOTAZIONI = List.of(
            new DefPrenotazione("B-R1", "s1", "P1", "CONFERMATA", "EFFETTUATA", "MS", true, "MS", "A"),
            new DefPrenotazione("B-R2", "s2", "P2", "CONFERMATA", "EFFETTUATA", "MT", false, "MT", "B"),
            new DefPrenotazione("B-C1", "s3", "P1", "CONFERMATA", "CONFERMATA", "MS", true, null, null),
            new DefPrenotazione("B-E1", "s4", "P1", "CONFERMATA", "EFFETTUATA", "MS", true, "MS", null),
            new DefPrenotazione("B-C2", "s7", "P2", "CONFERMATA", "CONFERMATA", "MT", false, null, null),
            new DefPrenotazione("B-Q1", "s8", "P1", "RICHIESTA", "RICHIESTA", null, false, null, null),
            new DefPrenotazione("B-Q2", "s13", "P2", "RICHIESTA", "RICHIESTA", null, false, null, null),
            new DefPrenotazione("B-PM", "s5", "PM", "CONFERMATA", "EFFETTUATA", "PM", false, "PM", "R-PM"),
            new DefPrenotazione("B-EC", "s6", "P2", "CONFERMATA", "NON_EROGATA", "MT", false, null, null),
            new DefPrenotazione("B-IN", "s14", "P1", "CONFERMATA", "EFFETTUATA", "MI", false, "MI", null));

    /* PDF sintetici ammessi dal validatore di fixture: tre caricati e due conservati per altre prove. */
    public static final List<String> DOCUMENTI = List.of("A", "B", "R-PM", "B-E1", "B-IN");

    /* Q1 dura 30 minuti e costa 50,00; Q2 dura 45 minuti. */
    public static final BigDecimal PREZZO_Q1 = new BigDecimal("50.00");

    /* Identità e credenziali distinte per attore; toString non mostra la password. */
    public record Attore(String codice, int id, String email, String password, E2EClient client) {
        @Override public String toString() { return "Attore[" + codice + ", id=" + id + "]"; }
    }

    public enum Fase { NUOVA, T0, T1 }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final E2EAmbiente ambiente;
    private final E2EManifest manifest;
    private final E2EAccessi accessi;
    private final Consumer<String> passo;
    private final Map<String, Attore> attori = new LinkedHashMap<>();
    private final Map<String, Integer> prestazioni = new LinkedHashMap<>();
    private final Map<String, Integer> stanze = new LinkedHashMap<>();
    private final Map<String, Integer> slot = new LinkedHashMap<>();
    private final Map<String, Integer> prenotazioni = new LinkedHashMap<>();
    private final Map<String, Integer> referti = new LinkedHashMap<>();
    private final Map<String, byte[]> documenti = new LinkedHashMap<>();
    private final List<E2EClient> clienti = new ArrayList<>();
    private final List<String> bootstrapOutput = new ArrayList<>();
    private int bootstrapEsito = -1;
    private Integer pagamentoEC;
    private Integer rimborsoEC;
    private E2EClient anonimo;
    private Fase fase = Fase.NUOVA;

    private E2EFixtureClinica(E2EAmbiente ambiente, E2EManifest manifest, Consumer<String> passo) {
        this.ambiente = Objects.requireNonNull(ambiente);
        this.manifest = Objects.requireNonNull(manifest);
        this.passo = Objects.requireNonNull(passo);
        this.accessi = new E2EAccessi(ambiente.server());
    }

    /* Costruisce T0 su un ambiente nuovo, con orologio di dominio esattamente a T0. */
    public static E2EFixtureClinica preparaT0(E2EAmbiente ambiente, E2EManifest manifest) throws Exception {
        return preparaT0(ambiente, manifest, nome -> { });
    }

    /* Il gancio viene chiamato prima di ogni passo per iniettare guasti. */
    static E2EFixtureClinica preparaT0(E2EAmbiente ambiente, E2EManifest manifest, Consumer<String> passo) throws Exception {
        E2EFixtureClinica f = new E2EFixtureClinica(ambiente, manifest, passo);
        try {
            f.costruisciT0();
            return f;
        } catch (Exception | Error e) {
            f.close();
            throw e;
        }
    }

    /* Prepara entrambe le fasi fino allo stato T1 usato dalle prove. */
    public static E2EFixtureClinica prepara(E2EAmbiente ambiente, E2EManifest manifest) throws Exception {
        E2EFixtureClinica f = preparaT0(ambiente, manifest);
        try {
            f.passaAT1();
            return f;
        } catch (Exception | Error e) {
            f.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------------ T0

    private void costruisciT0() throws Exception {
        ManualClock dominio = ambiente.server().domainClock();
        if (!T0.equals(dominio.instant())) { throw new IllegalStateException("La fixture richiede un ambiente nuovo, al tempo T0."); }
        for (String d : DOCUMENTI) {
            byte[] pdf = FixturePdfValidator.documento("SEC-CLIN-01-" + d);
            ambiente.server().pdfValidator().accetta(pdf);
            documenti.put(d, pdf);
        }

        passo.accept("bootstrap");
        bootstrap();
        Attore ad = attori.get("AD");

        passo.accept("attori");
        Attore dk = creaAccount("DK", "Desk", "OPERATORE_DESK");
        for (String m : List.of("MT", "MS", "MX")) { creaMedico(m); }
        for (String p : List.of("P1", "P2", "PM", "MI")) { registraPaziente(p); }
        /* I profili misti collegano il medico all'account paziente esistente. */
        for (String doppio : List.of("PM", "MI")) {
            richiedi(ad, "POST", "/api/medici/" + id(doppio) + "/collegamento", Map.of(
                    "specializzazione", "Medicina generale", "numeroIscrizioneOrdine", manifest.nuovoIdentificativo("ordine")), 201);
        }

        passo.accept("catalogo");
        prestazioni.put("Q1", idDi(richiedi(ad, "POST", "/api/prestazioni", prestazione("Q1", 30, PREZZO_Q1), 201), "idPrestazione"));
        prestazioni.put("Q2", idDi(richiedi(ad, "POST", "/api/prestazioni", prestazione("Q2", 45, new BigDecimal("80.00")), 201), "idPrestazione"));
        for (String s : List.of("S1", "S2", "S3", "S4")) {
            Map<String, Object> stanza = new LinkedHashMap<>();
            stanza.put("nome", manifest.nuovoIdentificativo("stanza" + s)); stanza.put("descrizione", "Stanza sintetica " + s);
            stanze.put(s, idDi(richiedi(ad, "POST", "/api/stanze", stanza, 201), "idStanza"));
        }

        passo.accept("abilitazioni");
        for (var a : ABILITAZIONI.entrySet()) {
            richiedi(ad, "PUT", "/api/medici/" + id(a.getKey()) + "/prestazioni",
                    Map.of("operazione", "ASSEGNA", "idPrestazione", prestazioni.get(a.getValue())), 200);
        }

        passo.accept("slot");
        for (DefSlot s : SLOT) {
            JsonNode creati = richiedi(attori.get(s.titolare()), "POST", "/api/disponibilita", Map.of("idMedico", id(s.titolare()),
                    "dataOraInizio", s.inizio().toString(), "dataOraFine", s.inizio().plusHours(1).toString()), 201).json();
            if (creati.size() != 1) { throw new IllegalStateException("Attesa una disponibilità per " + s.codice()); }
            slot.put(s.codice(), creati.get(0).get("idDisponibilita").intValue());
        }

        passo.accept("configurazione");
        for (DefSlot s : SLOT) {
            if (s.stanza() == null) { continue; }
            richiedi(dk, "PUT", "/api/disponibilita/" + slot.get(s.codice()) + "/configurazione",
                    Map.of("idPrestazione", prestazioni.get("Q1"), "idStanza", stanze.get(s.stanza())), 200);
        }

        passo.accept("prenotazioni");
        for (DefPrenotazione p : PRENOTAZIONI) {
            prenotazioni.put(p.codice(), idDi(richiedi(attori.get(p.paziente()), "POST", "/api/prenotazioni",
                    Map.of("idPaziente", id(p.paziente()), "idDisponibilita", slot.get(p.slot())), 201), "idPrenotazione"));
        }

        passo.accept("conferme");
        for (DefPrenotazione p : PRENOTAZIONI) {
            if (p.statoT0().equals("CONFERMATA")) {
                richiedi(dk, "POST", "/api/prenotazioni/" + prenotazioni.get(p.codice()) + "/conferma", Map.of("idOperatore", dk.id()), 200);
            }
        }

        passo.accept("sostituzioni");
        List<Integer> sostituite = PRENOTAZIONI.stream().filter(DefPrenotazione::sostituita).map(p -> prenotazioni.get(p.codice())).toList();
        richiedi(attori.get("MT"), "POST", "/api/prenotazioni/segnala-indisponibilita",
                Map.of("medicoId", id("MT"), "idPrenotazioni", sostituite), 200);
        for (Integer b : sostituite) {
            richiedi(dk, "POST", "/api/prenotazioni/" + b + "/sostituisci-medico", Map.of("idOperatore", dk.id(),
                    "idMedicoSostituto", id("MS"), "statoAtteso", "CONFERMATA", "idMedicoIndisponibile", id("MT")), 200);
        }

        passo.accept("pagamenti");
        pagamentoEC = idDi(richiedi(attori.get("P2"), "POST", "/api/prenotazioni/" + prenotazioni.get("B-EC") + "/pagamento-online",
                Map.of("importoAtteso", PREZZO_Q1), 201), "idPagamento");

        anonimo = registra(new E2EClient(ambiente.server()));
        fase = Fase.T0;
    }

    // ------------------------------------------------------------------------------------------------ T1

    /* Avanza il dominio da T0 a T1, mai all'indietro, e completa visite, referti e rimborso. */
    public void passaAT1() throws Exception {
        if (fase != Fase.T0 || !T0.equals(ambiente.server().domainClock().instant())) {
            throw new IllegalStateException("Passaggio a T1 consentito una sola volta, da T0.");
        }
        passo.accept("T1");
        ambiente.server().domainClock().advance(Duration.between(T0, T1));

        passo.accept("visite");
        for (DefPrenotazione p : PRENOTAZIONI) {
            if (p.visitaDa() != null) {
                richiedi(attori.get(p.visitaDa()), "POST", "/api/prenotazioni/" + prenotazioni.get(p.codice()) + "/registra-visita", Map.of(), 200);
            }
        }

        passo.accept("referti");
        for (DefPrenotazione p : PRENOTAZIONI) {
            if (p.referto() == null) { continue; }
            E2EClient.Risposta r = attori.get(p.visitaDa()).client().inviaBytes("POST",
                    "/api/prenotazioni/" + prenotazioni.get(p.codice()) + "/referto", documenti.get(p.referto()), "application/pdf", Map.of());
            atteso(r, 201, "referto " + p.codice());
            referti.put(p.codice(), r.json().get("idReferto").intValue());
        }

        passo.accept("erogazione");
        Attore dk = attori.get("DK");
        richiedi(dk, "POST", "/api/prenotazioni/" + prenotazioni.get("B-EC") + "/verifica-mancata-erogazione",
                Map.of("idOperatore", dk.id(), "statoAtteso", "CONFERMATA", "esito", "NON_EROGATA"), 200);

        passo.accept("rimborsi");
        rimborsoEC = idDi(richiedi(attori.get("P2"), "POST", "/api/prenotazioni/" + prenotazioni.get("B-EC") + "/rimborso",
                Map.of("motivazione", "Prestazione non erogata (fixture sintetica)."), 201), "idRimborso");
        fase = Fase.T1;
    }

    // ------------------------------------------------------------------------------------------------ actors

    private void bootstrap() throws Exception {
        String email = manifest.nuovaEmail(RUOLO_EMAIL.get("AD"));
        String password = password("AD");
        Deque<String> righe = new ArrayDeque<>(List.of("Amministratore", "Fixture", email, ambiente.catalogo().nome()));
        Deque<char[]> segreti = new ArrayDeque<>(List.of(password.toCharArray(), password.toCharArray()));
        BootstrapService.Terminale terminale = new BootstrapService.Terminale() {
            @Override public String leggiRiga(String richiesta) { return righe.poll(); }
            @Override public char[] leggiPassword(String richiesta) { return segreti.poll(); }
            @Override public void scrivi(String messaggio) { bootstrapOutput.add(messaggio); }
        };
        bootstrapEsito = new BootstrapService(new UtenteRepository(ambiente.catalogo().factory(), ambiente.server().domainClock()),
                new PasswordHasher()).esegui(terminale);
        if (bootstrapEsito != BootstrapService.CREATO || !righe.isEmpty() || !segreti.isEmpty()) {
            throw new IllegalStateException("Bootstrap dell'amministratore non riuscito: esito " + bootstrapEsito);
        }
        accedi("AD", email, password);
    }

    private Attore creaAccount(String codice, String nome, String ruolo) throws Exception {
        String email = manifest.nuovaEmail(RUOLO_EMAIL.get(codice)), password = password(codice);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", nome); body.put("cognome", "Fixture"); body.put("email", email); body.put("password", password);
        body.put("ruoloGestionale", ruolo);
        richiedi(attori.get("AD"), "POST", "/api/utenti", body, 201);
        return accedi(codice, email, password);
    }

    private void creaMedico(String codice) throws Exception {
        String email = manifest.nuovaEmail(RUOLO_EMAIL.get(codice)), password = password(codice);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", "Medico " + codice); body.put("cognome", "Fixture"); body.put("email", email); body.put("password", password);
        body.put("specializzazione", "Medicina generale"); body.put("numeroIscrizioneOrdine", manifest.nuovoIdentificativo("ordine"));
        richiedi(attori.get("AD"), "POST", "/api/medici", body, 201);
        accedi(codice, email, password);
    }

    /* Registrazione con identificativi già nel manifest, poi login reale per acquisire l'ID. */
    private void registraPaziente(String codice) throws Exception {
        String email = manifest.nuovaEmail(RUOLO_EMAIL.get(codice)), password = password(codice);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", "Paziente " + codice); body.put("cognome", "Fixture"); body.put("email", email);
        body.put("password", password); body.put("codiceFiscale", manifest.nuovoCodiceFiscale());
        body.put("dataNascita", "1990-01-01"); body.put("telefono", null);
        atteso(anonimoTemporaneo().postJson("/api/auth/register", body), 201, "registrazione " + codice);
        accedi(codice, email, password);
    }

    private E2EClient anonimoTemporaneo() { return registra(new E2EClient(ambiente.server())); }

    private Attore accedi(String codice, String email, String password) throws Exception {
        E2EClient client = registra(accessi.accedi(email, password));
        JsonNode me = client.get("/api/auth/me").json();
        Attore a = new Attore(codice, me.get("idUtente").intValue(), email, password, client);
        attori.put(codice, a);
        return a;
    }

    private static Map<String, Object> prestazione(String codice, int durata, BigDecimal prezzo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", "Prestazione " + codice + " (fixture)"); body.put("descrizione", "Prestazione sintetica " + codice);
        body.put("durata", durata); body.put("prezzo", prezzo);
        return body;
    }

    private static String password(String codice) {
        byte[] b = new byte[8]; RANDOM.nextBytes(b);
        return "Fixture-" + codice + "-" + HexFormat.of().formatHex(b);
    }

    // ------------------------------------------------------------------------------------------------ HTTP

    private E2EClient.Risposta richiedi(Attore attore, String metodo, String path, Object body, int status) throws Exception {
        E2EClient.Risposta r = attore.client().invia(metodo, path, body == null ? null : MAPPER.writeValueAsString(body), Map.of());
        return atteso(r, status, attore.codice() + " " + metodo + " " + path);
    }

    private static E2EClient.Risposta atteso(E2EClient.Risposta r, int status, String operazione) {
        if (r.status() != status) {
            throw new IllegalStateException("Fixture: " + operazione + " -> HTTP " + r.status() + " (atteso " + status + "): " + r.testo());
        }
        return r;
    }

    private static int idDi(E2EClient.Risposta r, String campo) throws IOException {
        JsonNode n = r.json().get(campo);
        if (n == null || !n.canConvertToInt() || n.intValue() <= 0) { throw new IllegalStateException("Identificativo " + campo + " assente."); }
        return n.intValue();
    }

    private E2EClient registra(E2EClient client) { clienti.add(client); return client; }

    private int id(String codice) { return attori.get(codice).id(); }

    // ------------------------------------------------------------------------------------------------ accessors

    public Fase fase() { return fase; }
    public E2EAmbiente ambiente() { return ambiente; }
    public E2EManifest manifest() { return manifest; }
    public E2EAccessi accessi() { return accessi; }
    public Attore attore(String codice) { return Objects.requireNonNull(attori.get(codice), codice); }
    public Map<String, Attore> attori() { return Map.copyOf(attori); }
    public E2EClient anonimo() { return anonimo; }
    public int prestazione(String codice) { return prestazioni.get(codice); }
    public int stanza(String codice) { return stanze.get(codice); }
    public int slot(String codice) { return slot.get(codice); }
    public int prenotazione(String codice) { return prenotazioni.get(codice); }
    public Map<String, Integer> prenotazioni() { return Map.copyOf(prenotazioni); }
    public Integer referto(String codicePrenotazione) { return referti.get(codicePrenotazione); }
    public byte[] documento(String codice) { return documenti.get(codice).clone(); }
    public Integer pagamentoEC() { return pagamentoEC; }
    public Integer rimborsoEC() { return rimborsoEC; }
    public int bootstrapEsito() { return bootstrapEsito; }
    public List<String> bootstrapOutput() { return List.copyOf(bootstrapOutput); }

    /* Client separato per attore tramite nuovo login reale, sempre sotto limitatore. */
    public E2EClient nuovaSessione(String codice) throws Exception {
        Attore a = attore(codice);
        return registra(accessi.accedi(a.email(), a.password()));
    }

    /* Chiude tutti i client; catalogo e storage appartengono all'ambiente. */
    @Override public void close() { clienti.forEach(E2EClient::close); clienti.clear(); }

    public static DefPrenotazione definizione(String codice) {
        return PRENOTAZIONI.stream().filter(p -> p.codice().equals(codice)).findFirst().orElseThrow();
    }

    public static DefSlot definizioneSlot(String codice) {
        return SLOT.stream().filter(s -> s.codice().equals(codice)).findFirst().orElseThrow();
    }
}
