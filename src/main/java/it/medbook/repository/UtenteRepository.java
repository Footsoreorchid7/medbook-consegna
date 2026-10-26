package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.model.RuoloGestionale;
import java.math.BigInteger;
import java.sql.*;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public class UtenteRepository {
    private final ConnectionFactory connectionFactory;
    private final java.time.Clock clock;
    /* medico indica esistenza del profilo in qualunque stato; medico_stato è letto grezzo e interpretato in Java, */
    /* poiché il confronto SQL ai_ci accetterebbe anche varianti non canoniche di ATTIVO. */
    /* Stato accesso e generazione vengono letti grezzi e classificati in Java; */
    /* un valore non canonico è anomalia, mai normalizzato silenziosamente. */
    private static final String FIELDS = "u.id_utente, u.nome, u.cognome, u.ruolo_gestionale, "
            + "EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente=u.id_utente) AS paziente, "
            + "EXISTS(SELECT 1 FROM medico m WHERE m.id_utente=u.id_utente) AS medico, "
            + "(SELECT m.stato FROM medico m WHERE m.id_utente=u.id_utente) AS medico_stato, "
            + "u.stato_accesso, u.generazione_autenticazione";
    public static final String ATTIVO = "ATTIVO", DISABILITATO = "DISABILITATO";

    /** Non serializzare questo oggetto interno: contiene il verificatore della password. */
    public static final class Credenziali {
        private final UtenteAutenticatoResponse identita;
        private final String passwordHash;
        public Credenziali(UtenteAutenticatoResponse identita, String passwordHash) {
            this.identita = Objects.requireNonNull(identita);
            this.passwordHash = passwordHash;
        }
        public UtenteAutenticatoResponse identita() { return identita; }
        public String passwordHash() { return passwordHash; }
    }

    public UtenteRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, java.time.Clock.systemDefaultZone());
    }
    public UtenteRepository(ConnectionFactory connectionFactory, java.time.Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.clock = Objects.requireNonNull(clock);
    }
    public Optional<Credenziali> findByEmail(String email) throws SQLException {
        String sql = "SELECT " + FIELDS + ", u.password_hash FROM utente u WHERE u.email=?";
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new Credenziali(mapIdentity(result), result.getString("password_hash")))
                        : Optional.empty();
            }
        }
    }
    /* Identità solo per account ATTIVO; DISABILITATO restituisce vuoto, anomalie generano eccezione dedicata. */
    public Optional<UtenteAutenticatoResponse> findById(Integer idUtente) throws SQLException {
        Optional<StatoSessione> stato = findSessione(idUtente);
        return stato.map(StatoSessione::identita);
    }

    /*
     * Verifica sessione: identità, stato grezzo e generazione validata dell'account. Vuoto se
     * assente o DISABILITATO canonico; stato/ruolo non canonico o generazione fuori intervallo sono
     * anomalie anche per account disabilitato. Mai serializzare.
     */
    public record StatoSessione(UtenteAutenticatoResponse identita, long generazione) { }
    public Optional<StatoSessione> findSessione(Integer idUtente) throws SQLException {
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + FIELDS + " FROM utente u WHERE u.id_utente=?")) {
            statement.setInt(1, idUtente);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { return Optional.empty(); }
                String stato = statoAccesso(result.getString("stato_accesso"));
                OptionalLong generazione = generazione(result);
                if (stato == null || generazione.isEmpty()) { throw new AnomaliaDatiAccountException(); }
                /* Tutte le anomalie, ruolo incluso, vengono classificate prima del rifiuto DISABILITATO canonico. */
                UtenteAutenticatoResponse identita = mapIdentity(result);
                if (!ATTIVO.equals(stato)) { return Optional.empty(); }
                return Optional.of(new StatoSessione(identita, generazione.getAsLong()));
            }
        }
    }

    /* Anomalie nella lettura credenziali: il login le decide solo dopo KDF. */
    public enum Anomalia { STATO, GENERAZIONE, RUOLO }

    /*
     * Lettura login: anomalie come flag, non eccezioni. Contiene il verificatore della password,
     * quindi mai serializzare o registrare; toString lo omette.
     */
    public static final class CredenzialiLogin {
        private final int idUtente;
        private final String passwordHash, statoAccesso;
        private final OptionalLong generazione;
        private final Set<Anomalia> anomalie;
        private final UtenteAutenticatoResponse identita;
        public CredenzialiLogin(int idUtente, String passwordHash, String statoAccesso, OptionalLong generazione,
                                Set<Anomalia> anomalie, UtenteAutenticatoResponse identita) {
            this.idUtente = idUtente; this.passwordHash = passwordHash; this.statoAccesso = statoAccesso;
            this.generazione = Objects.requireNonNull(generazione);
            this.anomalie = Set.copyOf(anomalie); this.identita = identita;
        }
        public int idUtente() { return idUtente; }
        public String passwordHash() { return passwordHash; }
        /* ATTIVO, DISABILITATO oppure null se non canonico, con anomalia STATO. */
        public String statoAccesso() { return statoAccesso; }
        public OptionalLong generazione() { return generazione; }
        public Set<Anomalia> anomalie() { return anomalie; }
        /* Costruito solo senza anomalia RUOLO, altrimenti null. */
        public UtenteAutenticatoResponse identita() { return identita; }
        @Override public String toString() { return "CredenzialiLogin[idUtente=" + idUtente + ", anomalie=" + anomalie + "]"; }
    }
    public Optional<CredenzialiLogin> leggiCredenzialiLogin(String email) throws SQLException {
        String sql = "SELECT " + FIELDS + ", u.password_hash FROM utente u WHERE u.email=?";
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { return Optional.empty(); }
                Set<Anomalia> anomalie = EnumSet.noneOf(Anomalia.class);
                String stato = statoAccesso(result.getString("stato_accesso"));
                if (stato == null) { anomalie.add(Anomalia.STATO); }
                OptionalLong generazione = generazione(result);
                if (generazione.isEmpty()) { anomalie.add(Anomalia.GENERAZIONE); }
                UtenteAutenticatoResponse identita = null;
                try { identita = mapIdentity(result); }
                catch (AnomaliaDatiAccountException e) { anomalie.add(Anomalia.RUOLO); }
                return Optional.of(new CredenzialiLogin(result.getInt("id_utente"), result.getString("password_hash"),
                        stato, generazione, anomalie, identita));
            }
        }
    }

    /* Stato canonico esatto o null; mai affidarsi alla collation ai_ci. */
    static String statoAccesso(String raw) {
        return ATTIVO.equals(raw) || DISABILITATO.equals(raw) ? raw : null;
    }
    /*
     * Generazione in 0..Long.MAX_VALUE, vuota se anomala. Connector/J restituisce BIGINT UNSIGNED
     * come BigInteger: getLong oltre limite genererebbe un errore localizzato contenente il valore.
     */
    static OptionalLong generazione(ResultSet result) throws SQLException {
        Object raw = result.getObject("generazione_autenticazione");
        BigInteger value;
        if (raw instanceof BigInteger b) { value = b; }
        else if (raw instanceof Long l) { value = BigInteger.valueOf(l); }
        else { return OptionalLong.empty(); }
        return value.signum() < 0 || value.bitLength() > 63 ? OptionalLong.empty() : OptionalLong.of(value.longValueExact());
    }
    private UtenteAutenticatoResponse mapIdentity(ResultSet result) throws SQLException {
        String ruolo = result.getString("ruolo_gestionale");
        RuoloGestionale ruoloGestionale;
        try { ruoloGestionale = ruolo == null ? null : RuoloGestionale.valueOf(ruolo); }
        catch (IllegalArgumentException e) {
            // Dati non compatibili con l'enumerazione: errore interno, non input HTTP invalido.
            throw new AnomaliaDatiAccountException();
        }
        return new UtenteAutenticatoResponse(result.getInt("id_utente"), result.getString("nome"),
                result.getString("cognome"), result.getBoolean("paziente"), medicoOperativo(result),
                ruoloGestionale);
    }
    /*
     * Permesso MEDICO solo con profilo e ATTIVO esatto. Ogni altro valore nega soltanto questo
     * permesso, senza conversione o errore.
     */
    private static boolean medicoOperativo(ResultSet result) throws SQLException {
        return result.getBoolean("medico") && "ATTIVO".equals(result.getString("medico_stato"));
    }

    public static final class ConflittoAccountException extends RuntimeException {
        public ConflittoAccountException(String message) { super(message); }
        public ConflittoAccountException(String message, Throwable cause) { super(message,cause); }
    }
    public static final class LockAccountTimeoutException extends SQLException {
        public LockAccountTimeoutException() { super("Gestione account temporaneamente non disponibile."); }
    }
    private it.medbook.dto.AccountInternoResponse account(ResultSet r) throws SQLException {
        /* Mappatura prudente: il CHECK SQL senza distinzione maiuscole ammette testi non canonici. */
        var identity=mapIdentity(r);
        if(identity.ruoloGestionale()==null)throw new SQLException("Account gestionale non coerente.");
        /* La proiezione account indica il profilo medico in ogni stato, non il permesso operativo. */
        String stato=statoAccesso(r.getString("stato_accesso"));
        if(stato==null)throw new AnomaliaDatiAccountException();
        return new it.medbook.dto.AccountInternoResponse(identity.idUtente(),identity.nome(),identity.cognome(),
                r.getString("email"),identity.ruoloGestionale(),identity.paziente(),r.getBoolean("medico"),stato);
    }
    public java.util.List<it.medbook.dto.AccountInternoResponse> findGestionali() throws SQLException {
        try(Connection c=connectionFactory.getConnection(); PreparedStatement s=c.prepareStatement(
                "SELECT "+FIELDS+", u.email FROM utente u WHERE u.ruolo_gestionale IN ('AMMINISTRATORE','OPERATORE_DESK') ORDER BY u.id_utente ASC");
                ResultSet r=s.executeQuery()) {
            var result=new java.util.ArrayList<it.medbook.dto.AccountInternoResponse>();
            while(r.next()) {
                /* Stato canonico anche DISABILITATO e generazione nel dominio ammesso; */
                /* il ruolo viene validato da account. Un'anomalia fa fallire l'intero elenco, non viene saltata. */
                if(statoAccesso(r.getString("stato_accesso"))==null || generazione(r).isEmpty())throw new AnomaliaDatiAccountException();
                result.add(account(r));
            }
            return result;
        }
    }
    /*
     * Filtri validati dal servizio: ruolo gestionale o NESSUNO, email e cognome per prefisso,
     * almeno un filtro, cursore non negativo, limite 1–50.
     */
    public record FiltriRicerca(Integer idUtente, String email, String cognome, String codiceFiscale, String ruolo,
                                String statoAccesso, int dopoId, int limite) { }

    /*
     * Ricerca amministrativa per chiave id_utente, non OFFSET. Attore riletto sulla stessa
     * connessione: esattamente AMMINISTRATORE e ATTIVO, altrimenti 403. Nessun mutex, lock o
     * transazione. Solo UTENTE e codice fiscale PAZIENTE; anomalie in ruolo/stato fanno fallire
     * l'intera pagina.
     */
    public it.medbook.dto.RicercaUtentiResponse cerca(int attore, FiltriRicerca filtri) throws SQLException {
        Objects.requireNonNull(filtri);
        if (filtri.limite() < 1 || filtri.limite() > 50 || filtri.dopoId() < 0) { throw new IllegalArgumentException(); }
        StringBuilder sql = new StringBuilder("""
                SELECT u.id_utente, u.nome, u.cognome, p.codice_fiscale, u.email, u.ruolo_gestionale, u.stato_accesso
                FROM utente u LEFT JOIN paziente p ON p.id_utente = u.id_utente
                WHERE u.id_utente > ?""");
        java.util.List<Object> valori = new java.util.ArrayList<>(); valori.add(filtri.dopoId());
        /* Si concatenano solo frammenti costanti; ogni valore utente è un parametro associato. */
        if (filtri.idUtente() != null) { sql.append(" AND u.id_utente = ?"); valori.add(filtri.idUtente()); }
        if (filtri.email() != null) { sql.append(" AND u.email LIKE CONCAT(?, '%') ESCAPE '!'"); valori.add(likeLetterale(filtri.email())); }
        if (filtri.cognome() != null) { sql.append(" AND u.cognome LIKE CONCAT(?, '%') ESCAPE '!'"); valori.add(likeLetterale(filtri.cognome())); }
        if (filtri.codiceFiscale() != null) { sql.append(" AND p.codice_fiscale = ?"); valori.add(filtri.codiceFiscale()); }
        if ("NESSUNO".equals(filtri.ruolo())) { sql.append(" AND u.ruolo_gestionale IS NULL"); }
        else if (filtri.ruolo() != null) { sql.append(" AND u.ruolo_gestionale COLLATE utf8mb4_0900_bin = ?"); valori.add(filtri.ruolo()); }
        if (filtri.statoAccesso() != null) { sql.append(" AND u.stato_accesso COLLATE utf8mb4_0900_bin = ?"); valori.add(filtri.statoAccesso()); }
        sql.append(" ORDER BY u.id_utente ASC LIMIT ?"); valori.add(filtri.limite() + 1);
        try (Connection c = connectionFactory.getConnection()) {
            verificaAmministratoreAttivo(c, attore);
            var risultati = new java.util.ArrayList<it.medbook.dto.UtenteAmministrativoResponse>();
            boolean altri = false;
            try (PreparedStatement s = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < valori.size(); i++) { s.setObject(i + 1, valori.get(i)); }
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (risultati.size() == filtri.limite()) { altri = true; break; }
                        risultati.add(amministrativo(r));
                    }
                }
            }
            return new it.medbook.dto.RicercaUtentiResponse(risultati, altri ? risultati.getLast().idUtente() : null);
        }
    }
    /*
     * Prefisso LIKE con ESCAPE '!': punto esclamativo, percentuale e underscore letterali
     * indipendentemente da NO_BACKSLASH_ESCAPES.
     */
    static String likeLetterale(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
    private static void verificaAmministratoreAttivo(Connection c, int attore) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=?")) {
            s.setInt(1, attore);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !"AMMINISTRATORE".equals(r.getString("ruolo_gestionale"))
                        || !ATTIVO.equals(r.getString("stato_accesso"))) {
                    throw new it.medbook.exception.OperazioneNonAutorizzataException("Operazione non autorizzata.");
                }
            }
        }
    }
    private static it.medbook.dto.UtenteAmministrativoResponse amministrativo(ResultSet r) throws SQLException {
        String ruolo = r.getString("ruolo_gestionale"), stato = statoAccesso(r.getString("stato_accesso"));
        RuoloGestionale ruoloGestionale = null;
        if (ruolo != null) {
            if (!"AMMINISTRATORE".equals(ruolo) && !"OPERATORE_DESK".equals(ruolo)) { throw new AnomaliaDatiAccountException(); }
            ruoloGestionale = RuoloGestionale.valueOf(ruolo);
        }
        if (stato == null) { throw new AnomaliaDatiAccountException(); }
        return new it.medbook.dto.UtenteAmministrativoResponse(r.getInt("id_utente"), r.getString("nome"),
                r.getString("cognome"), r.getString("codice_fiscale"), r.getString("email"), ruoloGestionale, stato);
    }

    static final String NON_DISPONIBILE = "Operazione non disponibile per questo account.";

    /* Riga UTENTE sotto FOR UPDATE: ruolo grezzo, stato canonico o null e generazione validata. */
    record RigaAccount(String ruolo, String stato, OptionalLong generazione) {
        boolean amministratoreAttivo() { return "AMMINISTRATORE".equals(ruolo) && ATTIVO.equals(stato); }
        boolean ruoloCanonico() { return ruolo == null || "AMMINISTRATORE".equals(ruolo) || "OPERATORE_DESK".equals(ruolo); }
    }
    /* Lock UTENTE per ID crescente, con AccountMutex già acquisito; righe mancanti restano assenti. */
    private static java.util.Map<Integer, RigaAccount> bloccaAccount(Connection c, java.util.Collection<Integer> ids) throws SQLException {
        var righe = new java.util.HashMap<Integer, RigaAccount>();
        for (int id : new java.util.TreeSet<>(ids)) {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT ruolo_gestionale, stato_accesso, generazione_autenticazione FROM utente WHERE id_utente=? FOR UPDATE")) {
                s.setInt(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) { righe.put(id, new RigaAccount(r.getString("ruolo_gestionale"), statoAccesso(r.getString("stato_accesso")), generazione(r))); }
                }
            }
        }
        return righe;
    }
    /* Amministratori utilizzabili diversi dall'escluso, confrontando ruolo e stato in binario. */
    private static long altriAmministratoriAttivi(Connection c, int escluso) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM utente WHERE CAST(ruolo_gestionale AS BINARY) = CAST('AMMINISTRATORE' AS BINARY) "
                + "AND CAST(stato_accesso AS BINARY) = CAST('ATTIVO' AS BINARY) AND id_utente <> ?")) {
            s.setInt(1, escluso);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) { throw new SQLException("Conteggio non disponibile."); } return r.getLong(1); }
        }
    }

    /* Esito: proiezione amministrativa e nuova generazione confermata solo per transizione effettiva. */
    public record CambioStatoAccesso(it.medbook.dto.UtenteAmministrativoResponse utente, Long nuovaGenerazione) { }

    /*
     * Disabilita o riabilita sotto AccountMutex, UTENTE FOR UPDATE per ID crescente e MEDICO FOR
     * SHARE. Idempotenza senza scritture; transizione effettiva incrementa la generazione di uno
     * nella stessa istruzione di stato e metadati.
     */
    public CambioStatoAccesso cambiaStatoAccesso(int attore, int destinatario, String nuovoStato) throws SQLException {
        if (attore <= 0 || destinatario <= 0 || (!ATTIVO.equals(nuovoStato) && !DISABILITATO.equals(nuovoStato))) {
            throw new IllegalArgumentException();
        }
        return AccountMutex.execute(connectionFactory, c -> {
            var righe = bloccaAccount(c, java.util.List.of(attore, destinatario));
            /* Prima decide l'attore: se non attivo ottiene 403 anche per destinatario assente. */
            if (!righe.containsKey(attore) || !righe.get(attore).amministratoreAttivo()) {
                throw new it.medbook.exception.OperazioneNonAutorizzataException("Operazione non autorizzata.");
            }
            RigaAccount riga = righe.get(destinatario);
            if (riga == null) { throw new it.medbook.exception.RisorsaNonTrovataException("Utente non trovato."); }
            /* Destinatario anomalo: conflitto senza scritture, ruolo incluso; non si può costruire una proiezione valida. */
            if (riga.stato() == null || !riga.ruoloCanonico()) { throw new ConflittoAccountException(NON_DISPONIBILE); }
            /* Generazione fuori intervallo: destinatario anomalo anche nelle richieste idempotenti. */
            /* Long.MAX_VALUE è valido; solo la transizione effettiva è rifiutata al limite. */
            if (riga.generazione().isEmpty()) { throw new ConflittoAccountException(NON_DISPONIBILE); }
            // Idempotenza senza UPDATE, incremento generazione o metadati, anche per sé stesso da ATTIVO ad ATTIVO.
            if (nuovoStato.equals(riga.stato())) { return new CambioStatoAccesso(leggiAmministrativo(c, destinatario), null); }
            if (DISABILITATO.equals(nuovoStato)) {
                if (attore == destinatario) { throw new ConflittoAccountException("Auto-disabilitazione non consentita."); }
                if ("AMMINISTRATORE".equals(riga.ruolo()) && altriAmministratoriAttivi(c, destinatario) < 1) {
                    throw new ConflittoAccountException("Ultimo amministratore attivo protetto.");
                }
                try (PreparedStatement s = c.prepareStatement("SELECT stato FROM medico WHERE id_utente = ? FOR SHARE")) {
                    s.setInt(1, destinatario);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next() && ATTIVO.equals(r.getString("stato"))) {
                            throw new ConflittoAccountException("Inattivare prima il profilo medico.");
                        }
                    }
                }
            }
            /* La generazione validata sotto lock non supera il massimo né ricomincia da zero. */
            long precedente = riga.generazione().getAsLong();
            if (precedente == Long.MAX_VALUE) { throw new it.medbook.exception.GenerazioneEsauritaException(); }
            long nuova = Math.addExact(precedente, 1);
            /* Orologio letto dopo i lock decisivi, appena prima della scrittura tracciata. */
            var modificatoIl = UltimaModifica.adesso(clock);
            try (PreparedStatement s = c.prepareStatement("UPDATE utente SET stato_accesso = ?, generazione_autenticazione = ?, "
                    + UltimaModifica.SET + " WHERE id_utente = ? AND CAST(stato_accesso AS BINARY) = CAST(? AS BINARY) "
                    + "AND generazione_autenticazione = ?")) {
                s.setString(1, nuovoStato); s.setLong(2, nuova); UltimaModifica.imposta(s, 3, attore, modificatoIl);
                s.setInt(5, destinatario); s.setString(6, riga.stato()); s.setLong(7, precedente);
                /* Un conteggio diverso è impossibile sotto FOR UPDATE: errore interno e rollback. */
                if (s.executeUpdate() != 1) { throw new SQLException("Aggiornamento stato non completato."); }
            }
            return new CambioStatoAccesso(leggiAmministrativo(c, destinatario), nuova);
        });
    }
    /*
     * Lettura preliminare password senza lock: verificatore, stato, generazione e categoria. Mai
     * serializzare o registrare. Politica rigorosa per ruolo gestionale o profilo medico: 12–1024
     * caratteri.
     */
    public record DatiPassword(String passwordHash, String statoAccesso, long generazione, boolean rigorosa) {
        @Override public String toString() { return "DatiPassword[statoAccesso=" + statoAccesso + "]"; }
    }
    private static final String CATEGORIA = "u.ruolo_gestionale, EXISTS(SELECT 1 FROM medico m WHERE m.id_utente = u.id_utente) AS medico";

    /* Vuoto per account assente; anomalie di stato, generazione o ruolo generano eccezione dedicata. */
    public Optional<DatiPassword> leggiPerCambioPassword(int idUtente) throws SQLException {
        try (Connection c = connectionFactory.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT u.password_hash, u.stato_accesso, u.generazione_autenticazione, " + CATEGORIA + " FROM utente u WHERE u.id_utente = ?")) {
            s.setInt(1, idUtente);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { return Optional.empty(); }
                String stato = statoAccesso(r.getString("stato_accesso")); OptionalLong generazione = generazione(r);
                if (stato == null || generazione.isEmpty()) { throw new AnomaliaDatiAccountException(); }
                return Optional.of(new DatiPassword(r.getString("password_hash"), stato, generazione.getAsLong(), rigorosa(r)));
            }
        }
    }
    private static boolean rigorosa(ResultSet r) throws SQLException {
        String ruolo = r.getString("ruolo_gestionale");
        if (ruolo != null && !"AMMINISTRATORE".equals(ruolo) && !"OPERATORE_DESK".equals(ruolo)) { throw new AnomaliaDatiAccountException(); }
        return ruolo != null || r.getBoolean("medico");
    }

    /*
     * Cambio password su connessione fisica dedicata READ COMMITTED, senza AccountMutex e con un
     * lock riga. Nuova generazione solo dopo commit confermato. Errore precedente: rollback tentato
     * e originale propagato; chiusura fisica scarta la transazione mai confermata. Commit che
     * lancia: esito incerto. Dopo conferma gli errori close non cambiano l'esito.
     */
    public long aggiornaPassword(int idUtente, long generazioneSessione, boolean rigorosa, String nuovoHash) throws SQLException {
        Objects.requireNonNull(nuovoHash);
        Connection c = connectionFactory.getConnection();
        long nuova;
        try {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try { nuova = scriviPassword(c, idUtente, generazioneSessione, rigorosa, nuovoHash); }
            catch (SQLException | RuntimeException | Error e) {
                try { c.rollback(); } catch (SQLException x) { e.addSuppressed(x); }
                throw e;
            }
            try { c.commit(); }
            catch (SQLException | RuntimeException e) { throw new it.medbook.exception.EsitoCommitIncertoException(e); }
        } catch (SQLException | RuntimeException | Error e) {
            try { c.close(); } catch (SQLException x) { e.addSuppressed(x); }
            throw e;
        }
        /* Commit confermato: un guasto successivo non cambia l'esito né produce errore HTTP. */
        try { c.close(); } catch (SQLException ignored) { }
        return nuova;
    }
    private long scriviPassword(Connection c, int idUtente, long generazioneSessione, boolean rigorosa, String nuovoHash) throws SQLException {
        long precedente;
        try (PreparedStatement s = c.prepareStatement("SELECT u.stato_accesso, u.generazione_autenticazione, " + CATEGORIA
                + " FROM utente u WHERE u.id_utente = ? FOR UPDATE")) {
            s.setInt(1, idUtente);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new ConflittoAccountException(NON_DISPONIBILE); }
                String stato = statoAccesso(r.getString("stato_accesso")); OptionalLong generazione = generazione(r);
                /* Anomalie dati: errore interno 500 classificato prima delle altre decisioni. */
                if (stato == null || generazione.isEmpty()) { throw new AnomaliaDatiAccountException(); }
                boolean categoria = rigorosa(r);
                // Stato non ATTIVO, generazione superata o categoria cambiata: 409 senza scritture o invalidazioni.
                if (!ATTIVO.equals(stato) || generazione.getAsLong() != generazioneSessione || categoria != rigorosa) {
                    throw new ConflittoAccountException(NON_DISPONIBILE);
                }
                precedente = generazione.getAsLong();
            }
        }
        /* Il massimo non viene superato né causa riavvolgimento numerico. */
        if (precedente == Long.MAX_VALUE) { throw new it.medbook.exception.GenerazioneEsauritaException(); }
        long nuova = Math.addExact(precedente, 1);
        var modificatoIl = UltimaModifica.adesso(clock);
        try (PreparedStatement s = c.prepareStatement("UPDATE utente SET password_hash = ?, generazione_autenticazione = ?, "
                + UltimaModifica.SET + " WHERE id_utente = ? AND generazione_autenticazione = ?")) {
            s.setString(1, nuovoHash); s.setLong(2, nuova); UltimaModifica.imposta(s, 3, idUtente, modificatoIl);
            s.setInt(5, idUtente); s.setLong(6, precedente);
            // Un conteggio diverso è impossibile sotto FOR UPDATE: errore interno.
            if (s.executeUpdate() != 1) { throw new SQLException("Aggiornamento password non completato."); }
        }
        return nuova;
    }

    private static it.medbook.dto.UtenteAmministrativoResponse leggiAmministrativo(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT u.id_utente, u.nome, u.cognome, p.codice_fiscale, u.email, u.ruolo_gestionale, u.stato_accesso
                FROM utente u LEFT JOIN paziente p ON p.id_utente = u.id_utente WHERE u.id_utente = ?""")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new SQLException("Risultato mancante."); }
                return amministrativo(r);
            }
        }
    }

    /* Nome del mutex condiviso degli account; protocollo descritto in AccountMutex. */
    public static String accountLockName(String catalog) throws SQLException {
        return AccountMutex.lockName(catalog);
    }
    /*
     * Non interpreta il testo localizzato dell'errore. Su INSERT il 1062 può derivare da email
     * UNIQUE o PRIMARY esaurita: distingue rileggendo la stessa email con la stessa collation.
     * InnoDB segnala il duplicato solo dopo commit della riga concorrente.
     */
    static boolean isDuplicateEmail(Connection c,SQLException error,String email) {
        if(error.getErrorCode()!=1062 || !"23000".equals(error.getSQLState()))return false;
        try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM utente WHERE email=?")) {
            s.setString(1,email);try(ResultSet r=s.executeQuery()){return r.next();}
        }catch(SQLException x){error.addSuppressed(x);return false;}
    }
    public it.medbook.dto.AccountInternoResponse createInterno(Integer actor,String nome,String cognome,String email,
                                                              String hash,RuoloGestionale role) throws SQLException {
        return writeAccount(actor,null,nome,cognome,email,hash,role);
    }
    public it.medbook.dto.AccountInternoResponse updateRuolo(Integer actor,Integer target,RuoloGestionale role) throws SQLException {
        if(target==null)throw new IllegalArgumentException("Destinatario obbligatorio.");
        return writeAccount(actor,target,null,null,null,null,role);
    }
    private it.medbook.dto.AccountInternoResponse writeAccount(Integer actor,Integer target,String nome,String cognome,
                                                               String email,String hash,RuoloGestionale role) throws SQLException {
        if(actor==null || actor<=0 || role==null || (target!=null && target<=0))throw new IllegalArgumentException();
        return AccountMutex.execute(connectionFactory,c->{
                var ids=new java.util.TreeSet<Integer>();ids.add(actor);if(target!=null)ids.add(target);
                /* Lock per ID crescente; la riga mancante viene soltanto registrata, così decide prima l'attore */
                /* e un attore declassato ottiene 403 anche se il destinatario non esiste. */
                var righe=bloccaAccount(c,ids);var roles=new java.util.HashMap<Integer,String>();
                righe.forEach((id,riga)->roles.put(id,riga.ruolo()));
                /* Attore esattamente ATTIVO; uno stato anomalo non è attivo. */
                if(!righe.containsKey(actor) || !righe.get(actor).amministratoreAttivo())throw new it.medbook.exception.OperazioneNonAutorizzataException("Operazione non autorizzata.");
                if(target!=null && target.equals(actor))throw new ConflittoAccountException("Auto-modifica del ruolo non consentita.");
                if(target!=null && !righe.containsKey(target))throw new it.medbook.exception.RisorsaNonTrovataException("Utente non trovato.");
                /* Stato o generazione anomala del destinatario: conflitto, nessuna scrittura. */
                if(target!=null && (righe.get(target).stato()==null || righe.get(target).generazione().isEmpty()))
                    throw new ConflittoAccountException(NON_DISPONIBILE);
                /*
                 * Combinazioni amministratore-medico preesistenti possono agire; solo il
                 * destinatario non può ricevere entrambi.
                 */
                if(target!=null)try(PreparedStatement s=c.prepareStatement("SELECT id_utente FROM medico WHERE id_utente=?")) {
                    s.setInt(1,target);try(ResultSet r=s.executeQuery()) {
                        if(r.next())throw new ConflittoAccountException("Profilo medico incompatibile con ruolo gestionale.");
                    }
                }
                if(target!=null && !"AMMINISTRATORE".equals(roles.get(target)) && !"OPERATORE_DESK".equals(roles.get(target)))
                    throw new ConflittoAccountException("Utente non gestionale o ruolo incompatibile.");
                // Declassare un amministratore utilizzabile richiede un altro amministratore utilizzabile;
                /* Un amministratore disabilitato può essere declassato e non conta tra quelli utilizzabili. */
                if(target!=null && "AMMINISTRATORE".equals(roles.get(target)) && ATTIVO.equals(righe.get(target).stato())
                        && role!=RuoloGestionale.AMMINISTRATORE && altriAmministratoriAttivi(c,target)<1)
                    throw new ConflittoAccountException("Ultimo amministratore protetto.");
                int id;
                /* Istante letto dopo mutex e FOR UPDATE, appena prima della scrittura tracciata. */
                if(target==null) {
                    var modificatoIl=UltimaModifica.adesso(clock);
                    try(PreparedStatement s=c.prepareStatement("INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale,modificato_da,modificato_il) VALUES (?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                        s.setString(1,nome);s.setString(2,cognome);s.setString(3,email);s.setString(4,hash);s.setString(5,role.name());
                        UltimaModifica.imposta(s,6,actor,modificatoIl);
                        int n;
                        try{n=s.executeUpdate();}
                        catch(SQLException e){if(isDuplicateEmail(c,e,email))throw new ConflittoAccountException("Email già presente.",e);throw e;}
                        if(n!=1)throw new SQLException("Inserimento non completato.");
                        try(ResultSet r=s.getGeneratedKeys()) {
                            if(!r.next())throw new SQLException("ID generato mancante.");long key=r.getLong(1);
                            if(r.wasNull() || key<=0 || key>Integer.MAX_VALUE || r.next())throw new SQLException("ID generato non valido.");id=(int)key;
                        }
                    }
                }else if(role.name().equals(roles.get(target))) {
                    /* Stesso ruolo grezzo sotto lock: idempotenza, senza scritture o metadati. */
                    id=target;
                }else {
                    id=target;var modificatoIl=UltimaModifica.adesso(clock);
                    try(PreparedStatement s=c.prepareStatement("UPDATE utente SET ruolo_gestionale=?, "+UltimaModifica.SET+" WHERE id_utente=?")) {
                        s.setString(1,role.name());UltimaModifica.imposta(s,2,actor,modificatoIl);s.setInt(4,id);
                        if(s.executeUpdate()!=1)throw new SQLException("Aggiornamento non completato.");
                    }
                }
                it.medbook.dto.AccountInternoResponse result;
                try(PreparedStatement s=c.prepareStatement("SELECT "+FIELDS+", u.email FROM utente u WHERE u.id_utente=?")) {
                    s.setInt(1,id);try(ResultSet r=s.executeQuery()) {if(!r.next())throw new SQLException("Risultato mancante.");result=account(r);}
                }
                return result;
        });
    }

    /* Identità del database raggiunto, mostrata prima di ogni scrittura di bootstrap. */
    public record DestinazioneDatabase(String catalogo, String hostname, int porta, String serverUuid, String versione) { }
    /* Rifiuti del bootstrap che lasciano il database invariato. */
    public static final class BootstrapRifiutatoException extends RuntimeException {
        public enum Motivo { GIA_INIZIALIZZATO, EMAIL_PRESENTE, SCHEMA_NON_AGGIORNATO, DESTINAZIONE_DIVERSA }
        private final Motivo motivo;
        public BootstrapRifiutatoException(Motivo motivo) { super("Bootstrap rifiutato: " + motivo + "."); this.motivo = Objects.requireNonNull(motivo); }
        public BootstrapRifiutatoException(Motivo motivo, Throwable cause) { this(motivo); initCause(cause); }
        public Motivo motivo() { return motivo; }
    }
    public DestinazioneDatabase destinazione() throws SQLException {
        try (Connection c = connectionFactory.getConnection()) { return destinazione(c); }
    }
    static DestinazioneDatabase destinazione(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT DATABASE(), @@hostname, @@port, @@server_uuid, VERSION()");
             ResultSet r = s.executeQuery()) {
            if (!r.next() || r.getString(1) == null || r.getString(4) == null) { throw new SQLException("Destinazione del database non determinabile."); }
            return new DestinazioneDatabase(r.getString(1), r.getString(2), r.getInt(3), r.getString(4), r.getString(5));
        }
    }
    /*
     * Verifica in sola lettura della migrazione 003 su DATABASE(): colonne e tipi, CHECK imposto
     * sullo stato e chiave esterna autore. Nessun DDL; le verifiche puntuali delle definizioni
     * restano nella migrazione.
     */
    public boolean schemaAggiornato() throws SQLException {
        try (Connection c = connectionFactory.getConnection()) { return schemaAggiornato(c); }
    }
    static boolean schemaAggiornato(Connection c) throws SQLException {
        String sql = "SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'utente' AND ("
                + "(COLUMN_NAME = 'stato_accesso' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 20 AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = 'ATTIVO')"
                + " OR (COLUMN_NAME = 'generazione_autenticazione' AND COLUMN_TYPE = 'bigint unsigned' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0')"
                + " OR (COLUMN_NAME = 'modificato_da' AND COLUMN_TYPE = 'int unsigned' AND IS_NULLABLE = 'YES')"
                + " OR (COLUMN_NAME = 'modificato_il' AND DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 0 AND IS_NULLABLE = 'YES'))), "
                + "(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'utente'"
                + " AND CONSTRAINT_NAME = 'chk_utente_stato_accesso' AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'), "
                + "(SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS rc JOIN information_schema.KEY_COLUMN_USAGE k"
                + " ON k.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND k.TABLE_NAME = rc.TABLE_NAME"
                + " WHERE rc.CONSTRAINT_SCHEMA = DATABASE() AND rc.TABLE_NAME = 'utente' AND rc.CONSTRAINT_NAME = 'fk_utente_modificato_da'"
                + " AND rc.REFERENCED_TABLE_NAME = 'utente' AND rc.UPDATE_RULE = 'RESTRICT' AND rc.DELETE_RULE = 'SET NULL'"
                + " AND k.COLUMN_NAME = 'modificato_da' AND k.REFERENCED_COLUMN_NAME = 'id_utente')";
        try (PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            if (!r.next()) { throw new SQLException("Verifica dello schema non riuscita."); }
            return r.getLong(1) == 4 && r.getLong(2) == 1 && r.getLong(3) == 1;
        }
    }
    /* Account con qualsiasi ruolo gestionale, canonico o meno, attivo o disabilitato; i soli pazienti non contano. */
    public long contaGestionali() throws SQLException {
        try (Connection c = connectionFactory.getConnection()) { return contaGestionali(c); }
    }
    private static long contaGestionali(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM utente WHERE ruolo_gestionale IS NOT NULL");
             ResultSet r = s.executeQuery()) {
            if (!r.next()) { throw new SQLException("Conteggio non disponibile."); }
            return r.getLong(1);
        }
    }
    /*
     * Crea il primo amministratore solo senza account gestionali, sotto AccountMutex per rendere
     * atomici conteggio e INSERT. Riverifica destinazione confermata e migrazione nella
     * transazione. Non promuove account esistenti: email duplicata rifiutata anche se paziente.
     * Errore dopo scrittura verificata può essere commit o pulizia: esito incerto.
     */
    public int creaPrimoAmministratore(String nome, String cognome, String email, String hash, DestinazioneDatabase attesa) throws SQLException {
        Objects.requireNonNull(nome); Objects.requireNonNull(cognome); Objects.requireNonNull(email);
        Objects.requireNonNull(hash); Objects.requireNonNull(attesa);
        boolean[] scritto = {false};
        try {
            return AccountMutex.execute(connectionFactory, c -> {
                DestinazioneDatabase reale = destinazione(c);
                if (!reale.catalogo().equals(attesa.catalogo()) || !reale.serverUuid().equals(attesa.serverUuid())) {
                    throw new BootstrapRifiutatoException(BootstrapRifiutatoException.Motivo.DESTINAZIONE_DIVERSA);
                }
                if (!schemaAggiornato(c)) { throw new BootstrapRifiutatoException(BootstrapRifiutatoException.Motivo.SCHEMA_NON_AGGIORNATO); }
                if (contaGestionali(c) > 0) { throw new BootstrapRifiutatoException(BootstrapRifiutatoException.Motivo.GIA_INIZIALIZZATO); }
                /* Operazione di sistema senza autore; istante dopo mutex, prima di INSERT. */
                var modificatoIl = UltimaModifica.adesso(clock);
                int id;
                try (PreparedStatement s = c.prepareStatement("INSERT INTO utente (nome, cognome, email, password_hash, ruolo_gestionale, "
                        + "stato_accesso, generazione_autenticazione, modificato_da, modificato_il) VALUES (?, ?, ?, ?, 'AMMINISTRATORE', 'ATTIVO', 0, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1, nome); s.setString(2, cognome); s.setString(3, email); s.setString(4, hash);
                    UltimaModifica.imposta(s, 5, null, modificatoIl);
                    int n;
                    try { n = s.executeUpdate(); }
                    catch (SQLException e) {
                        if (isDuplicateEmail(c, e, email)) { throw new BootstrapRifiutatoException(BootstrapRifiutatoException.Motivo.EMAIL_PRESENTE, e); }
                        throw e;
                    }
                    if (n != 1) { throw new SQLException("Inserimento non completato."); }
                    try (ResultSet r = s.getGeneratedKeys()) {
                        if (!r.next()) { throw new SQLException("ID generato mancante."); }
                        long key = r.getLong(1);
                        if (r.wasNull() || key <= 0 || key > Integer.MAX_VALUE || r.next()) { throw new SQLException("ID generato non valido."); }
                        id = (int) key;
                    }
                }
                /* Rilettura come l'applicazione: un amministratore attivo esatto, generazione zero. */
                try (PreparedStatement s = c.prepareStatement("SELECT ruolo_gestionale, stato_accesso, generazione_autenticazione FROM utente WHERE id_utente = ?")) {
                    s.setInt(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || !"AMMINISTRATORE".equals(r.getString(1)) || !ATTIVO.equals(statoAccesso(r.getString(2)))
                                || generazione(r).orElse(-1) != 0) { throw new SQLException("Amministratore non verificato."); }
                    }
                }
                scritto[0] = true;
                return id;
            });
        } catch (SQLException | RuntimeException e) {
            if (scritto[0]) { throw new it.medbook.exception.EsitoCommitIncertoException(e); }
            throw e;
        }
    }
}
