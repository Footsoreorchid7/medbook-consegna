package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.MedicoResponse;
import it.medbook.dto.MedicoDeskResponse;
import it.medbook.dto.PrestazioneResponse;
import java.util.List;
import java.util.ArrayList;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.RuoloGestionale;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/*
 * Scritture medico sotto AccountMutex, poi UTENTE per ID crescente e MEDICO. Ruolo amministratore
 * riletto sotto lock: attore declassato con 403 anche se destinatario assente. Qui non vengono
 * scritti ruoli.
 */
public class MedicoRepository {
    static final String EMAIL_DUPLICATA = "Email già presente.";
    static final String NUMERO_DUPLICATO = "Numero di iscrizione già presente.";
    static final String PROFILO_PRESENTE = "Profilo medico già presente.";
    static final String RUOLO_GESTIONALE = "Utente con ruolo gestionale: profilo medico non consentito.";
    static final String DATI_PERSONALI_GESTIONALE = "Dati personali di un account gestionale non modificabili.";
    static final String STATO_NON_VALIDO = "Stato medico non valido.";
    static final String ACCOUNT_NON_ATTIVO = "Account utente non attivo.";
    private static final String SELECT_MEDICO = """
            SELECT u.id_utente, u.nome, u.cognome, u.email, u.ruolo_gestionale,
                   m.specializzazione, m.numero_iscrizione_ordine, m.stato,
                   EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente = u.id_utente) AS paziente
            FROM utente u JOIN medico m ON m.id_utente = u.id_utente
            WHERE u.id_utente = ?""";

    private final ConnectionFactory connectionFactory;
    private final Clock clock;

    public MedicoRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemDefaultZone());
    }
    public MedicoRepository(ConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.clock = Objects.requireNonNull(clock);
    }

    /* Reset amministrativo: stesso ordine mutex/UTENTE/MEDICO, hash e generazione aggiornati atomicamente. */
    public long reimpostaPassword(int attore, int medico, String hash) throws SQLException {
        verificaId(attore); verificaId(medico); Objects.requireNonNull(hash);
        return AccountMutex.execute(connectionFactory, c -> {
            /* Non richiede ATTIVO: il reset non deve cambiare o aggirare stati d'accesso e operativi. */
            Utenti utenti = bloccaUtenti(c, attore, medico);
            verificaAttore(utenti, attore);
            if (!utenti.containsKey(medico) || StatoMedico.blocca(c, medico, true) == null)
                throw new RisorsaNonTrovataException("Medico non trovato.");
            long precedente;
            try (PreparedStatement s = c.prepareStatement("SELECT generazione_autenticazione FROM utente WHERE id_utente=?")) {
                s.setInt(1, medico);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new SQLException("Account mancante.");
                    var g = UtenteRepository.generazione(r);
                    if (g.isEmpty()) throw new SQLException("Generazione non valida.");
                    precedente = g.getAsLong();
                }
            }
            if (precedente == Long.MAX_VALUE) throw new it.medbook.exception.GenerazioneEsauritaException();
            long nuova = precedente + 1;
            try (PreparedStatement s = c.prepareStatement("UPDATE utente SET password_hash=?, generazione_autenticazione=?, "
                    + UltimaModifica.SET + " WHERE id_utente=? AND generazione_autenticazione=?")) {
                s.setString(1, hash); s.setLong(2, nuova);
                UltimaModifica.imposta(s, 3, attore, UltimaModifica.adesso(clock));
                s.setInt(5, medico); s.setLong(6, precedente);
                if (s.executeUpdate() != 1) throw new SQLException("Reset non completato.");
            }
            return nuova;
        });
    }

    /* Nuovo account senza ruolo gestionale e profilo medico ATTIVO nella stessa transazione. */
    public MedicoResponse crea(int attore, String nome, String cognome, String email, String hash,
                               String specializzazione, String numero) throws SQLException {
        verificaId(attore); richiesti(nome, cognome, email, hash, specializzazione, numero);
        return AccountMutex.execute(connectionFactory, c -> {
            verificaAttore(bloccaUtenti(c, attore), attore);
            int id;
            /* Istante dopo mutex e FOR UPDATE dell'attore, prima dell'INSERT tracciato. */
            var modificatoIl = UltimaModifica.adesso(clock);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO utente (nome,cognome,email,password_hash,ruolo_gestionale,modificato_da,modificato_il) VALUES (?,?,?,?,NULL,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, nome); s.setString(2, cognome); s.setString(3, email); s.setString(4, hash);
                UltimaModifica.imposta(s, 5, attore, modificatoIl);
                int n;
                try { n = s.executeUpdate(); }
                catch (SQLException e) {
                    if (UtenteRepository.isDuplicateEmail(c, e, email)) { throw new ConflittoMedicoException(EMAIL_DUPLICATA, e); }
                    throw e;
                }
                if (n != 1) { throw new SQLException("Inserimento utente non completato."); }
                id = chiaveGenerata(s);
            }
            inserisciMedico(c, id, specializzazione, numero);
            return leggi(c, id);
        });
    }

    /* Profilo medico su account esistente senza ruolo gestionale; il profilo paziente rimane. */
    public MedicoResponse collega(int attore, int idUtente, String specializzazione, String numero) throws SQLException {
        verificaId(attore); verificaId(idUtente); richiesti(specializzazione, numero);
        return AccountMutex.execute(connectionFactory, c -> {
            Utenti ruoli = bloccaUtenti(c, attore, idUtente);
            verificaAttore(ruoli, attore);
            if (!ruoli.containsKey(idUtente)) { throw new RisorsaNonTrovataException("Utente non trovato."); }
            // Qualunque ruolo gestionale, canonico o meno, impedisce una nuova combinazione con MEDICO.
            if (ruoli.get(idUtente) != null) { throw new ConflittoMedicoException(RUOLO_GESTIONALE); }
            if (StatoMedico.blocca(c, idUtente, true) != null) { throw new ConflittoMedicoException(PROFILO_PRESENTE); }
            /* Nessun nuovo profilo medico per account non esattamente ATTIVO, già bloccato. */
            if (!"ATTIVO".equals(ruoli.stato(idUtente))) { throw new ConflittoMedicoException(ACCOUNT_NON_ATTIVO); }
            inserisciMedico(c, idUtente, specializzazione, numero);
            return leggi(c, idUtente);
        });
    }

    /* Dati professionali ammessi anche per combinazioni amministratore-medico preesistenti, anche su sé stessi. */
    public MedicoResponse aggiornaDatiProfessionali(int attore, int idMedico, String specializzazione, String numero)
            throws SQLException {
        verificaId(attore); verificaId(idMedico); richiesti(specializzazione, numero);
        return AccountMutex.execute(connectionFactory, c -> {
            bloccaMedico(c, attore, idMedico);
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE medico SET specializzazione = ?, numero_iscrizione_ordine = ? WHERE id_utente = ?")) {
                s.setString(1, specializzazione); s.setString(2, numero); s.setInt(3, idMedico);
                int n;
                try { n = s.executeUpdate(); }
                catch (SQLException e) {
                    if (isDuplicateNumero(c, e, numero, idMedico)) { throw new ConflittoMedicoException(NUMERO_DUPLICATO, e); }
                    throw e;
                }
                if (n < 0 || n > 1) { throw new SQLException("Aggiornamento medico non completato."); }
            }
            return leggi(c, idMedico);
        });
    }

    /* Dati personali dell'account: rifiutati quando il medico possiede un ruolo gestionale. */
    public MedicoResponse aggiornaDatiPersonali(int attore, int idMedico, String nome, String cognome, String email)
            throws SQLException {
        verificaId(attore); verificaId(idMedico); richiesti(nome, cognome, email);
        return AccountMutex.execute(connectionFactory, c -> {
            Utenti ruoli = bloccaMedico(c, attore, idMedico);
            if (ruoli.get(idMedico) != null) { throw new ConflittoMedicoException(DATI_PERSONALI_GESTIONALE); }
            /* Riga già bloccata: valori identici nel confronto Java non producono modifiche. */
            // nessuna scrittura o modifica dei metadati.
            if (datiPersonaliInvariati(c, idMedico, nome, cognome, email)) { return leggi(c, idMedico); }
            var modificatoIl = UltimaModifica.adesso(clock);
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE utente SET nome = ?, cognome = ?, email = ?, " + UltimaModifica.SET + " WHERE id_utente = ?")) {
                s.setString(1, nome); s.setString(2, cognome); s.setString(3, email);
                UltimaModifica.imposta(s, 4, attore, modificatoIl); s.setInt(6, idMedico);
                int n;
                try { n = s.executeUpdate(); }
                catch (SQLException e) {
                    /* Può collidere solo un'altra riga: quella del medico è la riga aggiornata. */
                    if (UtenteRepository.isDuplicateEmail(c, e, email)) { throw new ConflittoMedicoException(EMAIL_DUPLICATA, e); }
                    throw e;
                }
                if (n != 1) { throw new SQLException("Aggiornamento utente non completato."); }
            }
            return leggi(c, idMedico);
        });
    }

    /* Mutex medico serializza tutte le scritture di nuovi impegni bloccanti; nessun lock figlio qui. */
    public MedicoResponse cambiaStatoMedico(int attore, int idMedico, String nuovoStato) throws SQLException {
        verificaId(attore); verificaId(idMedico);
        if (!"ATTIVO".equals(nuovoStato) && !"INATTIVO".equals(nuovoStato)) { throw new IllegalArgumentException(); }
        return AccountMutex.execute(connectionFactory, c -> {
            Utenti utenti = bloccaMedico(c, attore, idMedico);
            /* Rilegge sulla stessa connessione sotto il lock medico esclusivo mantenuto. */
            String precedente = leggi(c, idMedico).stato();
            if (precedente.equals(nuovoStato)) { return leggi(c, idMedico); }
            /* Riattivazione professionale solo con accesso ATTIVO; ATTIVO verso ATTIVO resta idempotente */
            /* e INATTIVO è sempre consentito. */
            if ("ATTIVO".equals(nuovoStato) && !"ATTIVO".equals(utenti.stato(idMedico))) {
                throw new ConflittoMedicoException(ACCOUNT_NON_ATTIVO);
            }
            if ("INATTIVO".equals(nuovoStato)) {
                // Controllo canonico indipendente dalla collation: anche una riga storica associata anomala
                // determina un conflitto prudenziale, senza interpretazione implicita come stato terminale.
                try (PreparedStatement s = c.prepareStatement("""
                        SELECT pr.stato FROM prenotazione pr
                        JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                        WHERE (d.id_medico = ? OR pr.id_medico_effettivo = ?)
                          AND CAST(pr.stato AS BINARY) NOT IN
                              ('RICHIESTA','CONFERMATA','RIFIUTATA','ANNULLATA','EFFETTUATA','PAZIENTE_ASSENTE','NON_EROGATA')
                        LIMIT 1""")) {
                    s.setInt(1, idMedico); s.setInt(2, idMedico);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) { throw new ConflittoMedicoException("Stato prenotazione non canonico."); }
                    }
                }
                try (PreparedStatement s = c.prepareStatement("""
                        SELECT 1 FROM prenotazione pr
                        JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                        WHERE (CAST(pr.stato AS BINARY) = CAST('RICHIESTA' AS BINARY) AND d.id_medico = ?)
                           OR (CAST(pr.stato AS BINARY) = CAST('CONFERMATA' AS BINARY) AND pr.id_medico_effettivo = ?)
                        LIMIT 1""")) {
                    s.setInt(1, idMedico); s.setInt(2, idMedico);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) { throw new ConflittoMedicoException("Prenotazioni bloccanti presenti."); }
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE medico SET stato = ? WHERE id_utente = ? AND CAST(stato AS BINARY) = CAST(? AS BINARY)")) {
                s.setString(1, nuovoStato); s.setInt(2, idMedico); s.setString(3, precedente);
                if (s.executeUpdate() != 1) { throw new ConflittoMedicoException("Stato medico modificato."); }
            }
            return leggi(c, idMedico);
        });
    }

    /* Le abilitazioni mantengono il mutex medico fino al commit, incluse le verifiche FK MySQL. */
    public void assegnaPrestazione(int attore, int idMedico, int idPrestazione) throws SQLException {
        mutaAbilitazione(attore, idMedico, idPrestazione, true);
    }

    public void rimuoviPrestazione(int attore, int idMedico, int idPrestazione) throws SQLException {
        mutaAbilitazione(attore, idMedico, idPrestazione, false);
    }

    private void mutaAbilitazione(int attore, int idMedico, int idPrestazione, boolean aggiungi) throws SQLException {
        verificaId(attore); verificaId(idMedico); verificaId(idPrestazione);
        AccountMutex.execute(connectionFactory, c -> {
            bloccaMedico(c, attore, idMedico); // Prima UTENTE, poi MEDICO; INATTIVO è ammesso, stati anomali no.
            /* Nessun lock esplicito ai livelli intermedi; mai acquisire lock figlio dopo quelli delle prenotazioni. */
            try (PreparedStatement s = c.prepareStatement("SELECT id_prestazione FROM prestazione WHERE id_prestazione=? FOR SHARE")) {
                s.setInt(1, idPrestazione);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) { throw new RisorsaNonTrovataException("Prestazione non trovata."); }
                }
            }
            boolean presente;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id_medico FROM medico_prestazione WHERE id_medico=? AND id_prestazione=? FOR UPDATE")) {
                s.setInt(1, idMedico); s.setInt(2, idPrestazione);
                try (ResultSet r = s.executeQuery()) { presente = r.next(); }
            }
            if (aggiungi && presente) { throw new ConflittoMedicoException("Abilitazione già presente."); }
            if (!aggiungi && !presente) { throw new ConflittoMedicoException("Abilitazione assente."); }
            if (!aggiungi) { verificaDipendenzeAbilitazione(c, idMedico, idPrestazione); }
            String sql = aggiungi ? "INSERT INTO medico_prestazione (id_medico,id_prestazione) VALUES (?,?)"
                    : "DELETE FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setInt(1, idMedico); s.setInt(2, idPrestazione);
                int n;
                try { n = s.executeUpdate(); }
                catch (SQLException e) {
                    /* Classifica solo vincoli noti dell'istruzione, confermati da rilettura corrente. */
                    try {
                        if (aggiungi && codiceSql(e, 1062) && esisteAbilitazione(c, idMedico, idPrestazione)) {
                            throw new ConflittoMedicoException("Abilitazione già presente.", e);
                        }
                        if (!aggiungi && codiceSql(e, 1451) && esisteDisponibilitaAbilitata(c, idMedico, idPrestazione)) {
                            throw new ConflittoMedicoException("Disponibilità associate alla prestazione.", e);
                        }
                    } catch (SQLException secondaria) { e.addSuppressed(secondaria); }
                    throw e;
                }
                if (n != 1) { throw new SQLException("Modifica abilitazione non completata."); }
            }
            return null;
        });
    }

    private static boolean codiceSql(SQLException e, int codice) {
        var visitate = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SQLException, Boolean>());
        for (SQLException x=e; x!=null && visitate.add(x); x=x.getNextException()) {
            if (x.getErrorCode()==codice && "23000".equals(x.getSQLState())) { return true; }
        }
        return false;
    }

    private static boolean esisteAbilitazione(Connection c, int medico, int prestazione) throws SQLException {
        return esiste(c, "SELECT 1 FROM medico_prestazione WHERE id_medico=? AND id_prestazione=?", medico, prestazione);
    }
    private static boolean esisteDisponibilitaAbilitata(Connection c, int medico, int prestazione) throws SQLException {
        return esiste(c, "SELECT 1 FROM disponibilita WHERE id_medico=? AND id_prestazione=? LIMIT 1", medico, prestazione);
    }
    private static boolean esiste(Connection c, String sql, int medico, int prestazione) throws SQLException {
        try (PreparedStatement s=c.prepareStatement(sql)) {
            s.setInt(1,medico); s.setInt(2,prestazione);
            try (ResultSet r=s.executeQuery()) { return r.next(); }
        }
    }

    private static void verificaDipendenzeAbilitazione(Connection c, int medico, int prestazione) throws SQLException {
        if (esisteDisponibilitaAbilitata(c, medico, prestazione)) {
            throw new ConflittoMedicoException("Disponibilità associate alla prestazione.");
        }
        String pertinente = """
                FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita=pr.id_disponibilita
                WHERE pr.id_medico_effettivo=? AND d.id_prestazione=? AND d.id_medico<>pr.id_medico_effettivo
                """;
        /* Stati sconosciuti o storici non diventano mai non bloccanti per normalizzazione implicita. */
        if (esiste(c, "SELECT 1 " + pertinente + """
                AND CAST(pr.stato AS BINARY) NOT IN
                    ('RICHIESTA','CONFERMATA','RIFIUTATA','ANNULLATA','EFFETTUATA','PAZIENTE_ASSENTE','NON_EROGATA') LIMIT 1
                """, medico, prestazione)) {
            throw new ConflittoMedicoException("Stato prenotazione non canonico.");
        }
        /* RICHIESTA con effettivo non nullo è incoerente e resta prudenzialmente bloccante. */
        if (esiste(c, "SELECT 1 " + pertinente +
                "AND CAST(pr.stato AS BINARY) IN ('RICHIESTA','CONFERMATA') LIMIT 1", medico, prestazione)) {
            throw new ConflittoMedicoException("Prenotazioni assegnate alla prestazione.");
        }
    }

    private static final String SELECT_ADMIN_ELENCO = """
            SELECT u.id_utente,u.nome,u.cognome,u.email,u.ruolo_gestionale,
                   m.specializzazione,m.numero_iscrizione_ordine,m.stato,
                   EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente=u.id_utente) AS paziente
            FROM medico m JOIN utente u ON u.id_utente=m.id_utente
            """;
    private static final String SELECT_DESK = """
            SELECT u.id_utente,u.nome,u.cognome,m.specializzazione,m.numero_iscrizione_ordine,m.stato
            FROM medico m JOIN utente u ON u.id_utente=m.id_utente
            """;

    public List<MedicoResponse> elencaPerAmministratore(int attore, String stato, Integer idPrestazione) throws SQLException {
        return elenca(attore,stato,idPrestazione,"AMMINISTRATORE",SELECT_ADMIN_ELENCO,MedicoRepository::mappaAdmin);
    }
    public List<MedicoDeskResponse> elencaPerDesk(int attore, String stato, Integer idPrestazione) throws SQLException {
        return elenca(attore,stato,idPrestazione,"OPERATORE_DESK",SELECT_DESK,MedicoRepository::mappaDesk);
    }
    public MedicoResponse dettaglioPerAmministratore(int attore, int medico) throws SQLException {
        verificaId(attore); verificaId(medico);
        try (Connection c=connectionFactory.getConnection()) {
            verificaLettore(c,attore,"AMMINISTRATORE");
            try (PreparedStatement s=c.prepareStatement(SELECT_MEDICO)) {
                s.setInt(1,medico);
                try (ResultSet r=s.executeQuery()) {
                    if (!r.next()) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                    return mappaAdmin(r);
                }
            }
        }
    }
    /** Flag minimo nel solo dettaglio desk; nessun dato anagrafico del profilo paziente. */
    public boolean profiloPazientePerDesk(int attore,int medico) throws SQLException {
        try(var c=connectionFactory.getConnection()) {
            verificaLettore(c,attore,"OPERATORE_DESK");
            try(var q=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM paziente p WHERE p.id_utente=m.id_utente) FROM medico m WHERE m.id_utente=?")) {
                q.setInt(1,medico);try(var r=q.executeQuery()) {
                    if(!r.next())throw new RisorsaNonTrovataException("Medico non trovato.");
                    return r.getBoolean(1);
                }
            }
        }
    }

    public MedicoDeskResponse dettaglioPerDesk(int attore, int medico) throws SQLException {
        verificaId(attore); verificaId(medico);
        try (Connection c=connectionFactory.getConnection()) {
            verificaLettore(c,attore,"OPERATORE_DESK");
            try (PreparedStatement s=c.prepareStatement(SELECT_DESK+"WHERE m.id_utente=?")) {
                s.setInt(1,medico);
                try (ResultSet r=s.executeQuery()) {
                    if (!r.next()) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                    return mappaDesk(r);
                }
            }
        }
    }
    public List<PrestazioneResponse> consultaAbilitazioni(int attore, int medico) throws SQLException {
        verificaId(attore); verificaId(medico);
        try (Connection c=connectionFactory.getConnection()) {
            String ruolo=verificaLettore(c,attore,null);
            try (PreparedStatement s=c.prepareStatement("SELECT stato FROM medico WHERE id_utente=?")) {
                s.setInt(1,medico);
                try (ResultSet r=s.executeQuery()) {
                    if (!r.next()) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                    if ("OPERATORE_DESK".equals(ruolo)) { verificaStatoCanonico(r.getString("stato")); }
                }
            }
            try (PreparedStatement s=c.prepareStatement("""
                    SELECT p.id_prestazione,p.nome,p.descrizione,p.durata,p.prezzo
                    FROM medico_prestazione mp JOIN prestazione p ON p.id_prestazione=mp.id_prestazione
                    WHERE mp.id_medico=? ORDER BY p.id_prestazione ASC
                    """)) {
                s.setInt(1,medico);
                try (ResultSet r=s.executeQuery()) {
                    List<PrestazioneResponse> result=new ArrayList<>();
                    while (r.next()) {
                        var p=new PrestazioneResponse(); p.setIdPrestazione(r.getInt("id_prestazione"));
                        p.setNome(r.getString("nome")); p.setDescrizione(r.getString("descrizione"));
                        p.setDurata(r.getInt("durata")); p.setPrezzo(r.getBigDecimal("prezzo")); result.add(p);
                    }
                    return result;
                }
            }
        }
    }
    @FunctionalInterface private interface Mappa<T> { T leggi(ResultSet r) throws SQLException; }
    private <T> List<T> elenca(int attore, String stato, Integer prestazione, String ruolo, String select, Mappa<T> mappa)
            throws SQLException {
        verificaId(attore); verificaFiltri(stato,prestazione);
        String sql=select+"WHERE 1=1"
                +(stato==null?"":" AND CAST(m.stato AS BINARY)=CAST(? AS BINARY)")
                +(prestazione==null?"":" AND EXISTS(SELECT 1 FROM medico_prestazione mp WHERE mp.id_medico=m.id_utente AND mp.id_prestazione=?)")
                +" ORDER BY m.id_utente ASC";
        try (Connection c=connectionFactory.getConnection()) {
            verificaLettore(c,attore,ruolo);
            try (PreparedStatement s=c.prepareStatement(sql)) {
                int i=1; if (stato!=null) { s.setString(i++,stato); } if (prestazione!=null) { s.setInt(i,prestazione); }
                try (ResultSet r=s.executeQuery()) {
                    List<T> result=new ArrayList<>(); while (r.next()) { result.add(mappa.leggi(r)); } return result;
                }
            }
        }
    }
    private static String verificaLettore(Connection c, int attore, String richiesto) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=?")) {
            s.setInt(1,attore);
            try (ResultSet r=s.executeQuery()) {
                /* Anche le letture richiedono attore esattamente ATTIVO. */
                if (!r.next() || !"ATTIVO".equals(r.getString("stato_accesso"))) { throw new OperazioneNonAutorizzataException("Operazione non autorizzata."); }
                String ruolo=r.getString("ruolo_gestionale");
                if ((richiesto!=null && !richiesto.equals(ruolo)) ||
                        (richiesto==null && !"AMMINISTRATORE".equals(ruolo) && !"OPERATORE_DESK".equals(ruolo))) {
                    throw new OperazioneNonAutorizzataException("Operazione non autorizzata.");
                }
                return ruolo;
            }
        }
    }
    private static void verificaFiltri(String stato, Integer prestazione) {
        if (stato!=null) { verificaNuovoStato(stato); } if (prestazione!=null) { verificaId(prestazione); }
    }
    private static void verificaNuovoStato(String stato) {
        if (!"ATTIVO".equals(stato) && !"INATTIVO".equals(stato)) { throw new IllegalArgumentException(); }
    }
    private static void verificaStatoCanonico(String stato) {
        if (!"ATTIVO".equals(stato) && !"INATTIVO".equals(stato)) { throw new ConflittoMedicoException(STATO_NON_VALIDO); }
    }
    private static MedicoDeskResponse mappaDesk(ResultSet r) throws SQLException {
        String stato=r.getString("stato"); verificaStatoCanonico(stato);
        return new MedicoDeskResponse(r.getInt("id_utente"),r.getString("nome"),r.getString("cognome"),
                r.getString("specializzazione"),r.getString("numero_iscrizione_ordine"),stato);
    }
    private static MedicoResponse mappaAdmin(ResultSet r) throws SQLException {
        String ruolo=r.getString("ruolo_gestionale"); RuoloGestionale role;
        try { role=ruolo==null?null:RuoloGestionale.valueOf(ruolo); }
        catch (IllegalArgumentException e) { throw new SQLException("Ruolo gestionale non riconosciuto.",e); }
        return new MedicoResponse(r.getInt("id_utente"),r.getString("nome"),r.getString("cognome"),r.getString("email"),
                r.getString("specializzazione"),r.getString("numero_iscrizione_ordine"),r.getString("stato"),r.getBoolean("paziente"),role);
    }

    /*
     * Dati personali correnti di una riga già bloccata FOR UPDATE nella transazione; confronto
     * esatto String.equals.
     */
    private static boolean datiPersonaliInvariati(Connection c, int idUtente, String nome, String cognome, String email)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT nome, cognome, email FROM utente WHERE id_utente = ?")) {
            s.setInt(1, idUtente);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new SQLException("Utente bloccato non trovato."); }
                return nome.equals(r.getString("nome")) && cognome.equals(r.getString("cognome"))
                        && email.equals(r.getString("email"));
            }
        }
    }

    /* Prima UTENTE dell'attore e medico, decisione sull'attore, poi riga MEDICO e stato. */
    private static Utenti bloccaMedico(Connection c, int attore, int idMedico) throws SQLException {
        Utenti ruoli = bloccaUtenti(c, attore, idMedico);
        verificaAttore(ruoli, attore);
        if (!ruoli.containsKey(idMedico)) { throw new RisorsaNonTrovataException("Medico non trovato."); }
        String stato = StatoMedico.blocca(c, idMedico, true);
        if (stato == null) { throw new RisorsaNonTrovataException("Medico non trovato."); }
        /* Stato anomalo diverso da ATTIVO/INATTIVO: rifiuto, mai normalizzazione. */
        if (!"ATTIVO".equals(stato) && !"INATTIVO".equals(stato)) { throw new ConflittoMedicoException(STATO_NON_VALIDO); }
        return ruoli;
    }

    /* Ruoli e stati d'accesso grezzi delle righe UTENTE bloccate. */
    private record Utenti(Map<Integer, String> ruoli, Map<Integer, String> stati) {
        boolean containsKey(int id) { return ruoli.containsKey(id); }
        String get(int id) { return ruoli.get(id); }
        String stato(int id) { return stati.get(id); }
    }

    /* FOR UPDATE UTENTE per ID crescente: assenze registrate, decisione attore per prima. */
    private static Utenti bloccaUtenti(Connection c, int... ids) throws SQLException {
        var ordinati = new TreeSet<Integer>(); for (int id : ids) { ordinati.add(id); }
        Map<Integer, String> ruoli = new HashMap<>(), stati = new HashMap<>();
        for (int id : ordinati) {
            try (PreparedStatement s = c.prepareStatement("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR UPDATE")) {
                s.setInt(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) { ruoli.put(id, r.getString("ruolo_gestionale")); stati.put(id, r.getString("stato_accesso")); }
                }
            }
        }
        return new Utenti(ruoli, stati);
    }

    /* Esattamente AMMINISTRATORE e ATTIVO: anomalia significa attore non attivo. */
    private static void verificaAttore(Utenti ruoli, int attore) {
        if (!ruoli.containsKey(attore) || !"AMMINISTRATORE".equals(ruoli.get(attore)) || !"ATTIVO".equals(ruoli.stato(attore))) {
            throw new OperazioneNonAutorizzataException("Operazione non autorizzata.");
        }
    }

    private static void inserisciMedico(Connection c, int id, String specializzazione, String numero) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO medico (id_utente,specializzazione,numero_iscrizione_ordine,stato) VALUES (?,?,?,'ATTIVO')")) {
            s.setInt(1, id); s.setString(2, specializzazione); s.setString(3, numero);
            int n;
            try { n = s.executeUpdate(); }
            catch (SQLException e) {
                if (isDuplicateNumero(c, e, numero, null)) { throw new ConflittoMedicoException(NUMERO_DUPLICATO, e); }
                throw e;
            }
            if (n != 1) { throw new SQLException("Inserimento medico non completato."); }
        }
    }

    /*
     * 1062/23000 confermato dalla rilettura sulla stessa colonna e collation, mai dal messaggio
     * localizzato. L'escluso evita il confronto col medico stesso in modifica.
     */
    static boolean isDuplicateNumero(Connection c, SQLException error, String numero, Integer escluso) {
        if (error.getErrorCode() != 1062 || !"23000".equals(error.getSQLState())) { return false; }
        String sql = "SELECT 1 FROM medico WHERE numero_iscrizione_ordine = ?" + (escluso == null ? "" : " AND id_utente <> ?");
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, numero); if (escluso != null) { s.setInt(2, escluso); }
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } catch (SQLException x) { error.addSuppressed(x); return false; }
    }

    private static int chiaveGenerata(PreparedStatement s) throws SQLException {
        try (ResultSet r = s.getGeneratedKeys()) {
            if (!r.next()) { throw new SQLException("ID generato mancante."); }
            long key = r.getLong(1);
            if (r.wasNull() || key <= 0 || key > Integer.MAX_VALUE || r.next()) { throw new SQLException("ID generato non valido."); }
            return (int) key;
        }
    }

    private static MedicoResponse leggi(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(SELECT_MEDICO)) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new SQLException("Medico non trovato dopo la scrittura."); }
                String ruolo = r.getString("ruolo_gestionale");
                RuoloGestionale ruoloGestionale;
                try { ruoloGestionale = ruolo == null ? null : RuoloGestionale.valueOf(ruolo); }
                catch (IllegalArgumentException e) { throw new SQLException("Ruolo gestionale non riconosciuto.", e); }
                /* Stato grezzo, mai normalizzato. */
                return new MedicoResponse(r.getInt("id_utente"), r.getString("nome"), r.getString("cognome"),
                        r.getString("email"), r.getString("specializzazione"), r.getString("numero_iscrizione_ordine"),
                        r.getString("stato"), r.getBoolean("paziente"), ruoloGestionale);
            }
        }
    }

    private static void verificaId(int id) { if (id <= 0) { throw new IllegalArgumentException(); } }
    private static void richiesti(String... values) { for (String v : values) { if (v == null) { throw new IllegalArgumentException(); } } }
}
