package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.RichiestaPrenotazioneResponse;
import it.medbook.dto.SegnalazioneIndisponibilitaResponse;
import it.medbook.dto.SegnalazionePendenteResponse;
import it.medbook.dto.AppuntamentoAgendaResponse;
import it.medbook.dto.AppuntamentoPazienteResponse;
import it.medbook.dto.ConfermaPrenotazioneResponse;
import it.medbook.dto.SostituzioneMedicoResponse;
import it.medbook.dto.RifiutoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPrenotazioneResponse;
import it.medbook.dto.AnnullamentoPazienteResponse;
import it.medbook.model.StatoPagamento;
import it.medbook.exception.OperazioneNonAutorizzataException;
import java.util.ArrayList;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.model.Disponibilita;
import it.medbook.model.Paziente;
import it.medbook.model.Prenotazione;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.StatoPrenotazione;
import it.medbook.model.Utente;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import it.medbook.dto.VerificaMancataErogazioneResponse;
import it.medbook.dto.RegistrazioneVisitaResponse;
import it.medbook.dto.PrenotazioneDeskResponse;
import it.medbook.model.ModalitaPagamento;
import it.medbook.model.StatoRimborso;

public class PrenotazioneRepository {
    private final ConnectionFactory connectionFactory;
    private final Clock clock;
    private final DisponibilitaRepository disponibilitaRepository;

    public PrenotazioneRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemDefaultZone());
    }

    public PrenotazioneRepository(ConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = connectionFactory;
        this.clock = Objects.requireNonNull(clock, "Clock obbligatorio.");
        this.disponibilitaRepository = new DisponibilitaRepository(connectionFactory);
    }

    /**
     * Selezione atomica: prima tutti gli slot per ID, poi tutte le prenotazioni per ID.
     * Non acquisisce lock del medico o della stanza dopo lo slot. Le letture preliminari
     * sono riverificate sotto lock; il Clock viene letto una sola volta dopo i lock.
     */
    public SegnalazioneIndisponibilitaResponse segnalaIndisponibilitaAtomically(
            Integer medicoId, List<Integer> ids) throws SQLException {
        if (medicoId == null || medicoId <= 0 || ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Medico e prenotazioni sono obbligatori.");
        }
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (Integer id : ids) {
            if (id == null || id <= 0 || !unique.add(id)) {
                throw new IllegalArgumentException("ID prenotazione non valido o duplicato.");
            }
        }
        List<Integer> ordinati = unique.stream().sorted().toList();
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_utente FROM medico WHERE id_utente = ?")) {
                    statement.setInt(1, medicoId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                    }
                }
                java.util.Map<Integer, Integer> slotPerPrenotazione = new java.util.HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione = ?")) {
                    for (Integer id : ordinati) {
                        statement.setInt(1, id);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                            slotPerPrenotazione.put(id, rows.getInt(1));
                        }
                    }
                }
                java.util.Map<Integer, Integer> titolari = new java.util.HashMap<>();
                java.util.Map<Integer, LocalDateTime> inizi = new java.util.HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico, data_ora_inizio FROM disponibilita
                        WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    for (Integer slot : slotPerPrenotazione.values().stream().distinct().sorted().toList()) {
                        statement.setInt(1, slot);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) { throw new ConflittoPrenotazioneException("Disponibilità associata non trovata."); }
                            titolari.put(slot, rows.getInt("id_medico"));
                            inizi.put(slot, rows.getObject("data_ora_inizio", LocalDateTime.class));
                        }
                    }
                }
                java.util.Map<Integer, StatoPrenotazione> stati = new java.util.HashMap<>();
                java.util.Set<Integer> daSegnalare = new java.util.HashSet<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, stato, gestita_da, data_ora_gestione,
                               id_medico_effettivo, indisponibilita_segnalata
                        FROM prenotazione WHERE id_prenotazione = ? FOR UPDATE
                        """)) {
                    for (Integer id : ordinati) {
                        statement.setInt(1, id);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                            Integer slot = slotPerPrenotazione.get(id);
                            if (!slot.equals(rows.getObject("id_disponibilita", Integer.class))) {
                                throw new ConflittoPrenotazioneException("Disponibilità della prenotazione modificata.");
                            }
                            StatoPrenotazione stato = StatoPrenotazione.valueOf(rows.getString("stato"));
                            if (stato != StatoPrenotazione.RICHIESTA && stato != StatoPrenotazione.CONFERMATA) {
                                throw new ConflittoPrenotazioneException("Prenotazione non segnalabile.");
                            }
                            Integer effettivo = rows.getObject("id_medico_effettivo", Integer.class);
                            boolean gestita = rows.getObject("gestita_da") != null;
                            boolean dataGestione = rows.getObject("data_ora_gestione") != null;
                            if ((stato == StatoPrenotazione.RICHIESTA && (effettivo != null || gestita || dataGestione))
                                    || (stato == StatoPrenotazione.CONFERMATA && (effettivo == null || !gestita || !dataGestione))) {
                                throw new ConflittoPrenotazioneException("Dati della prenotazione incoerenti.");
                            }
                            Integer competente = stato == StatoPrenotazione.RICHIESTA ? titolari.get(slot) : effettivo;
                            if (!medicoId.equals(competente)) {
                                throw new RisorsaNonTrovataException("Prenotazione non trovata per il medico dichiarato.");
                            }
                            stati.put(id, stato);
                            if (!rows.getBoolean("indisponibilita_segnalata")) { daSegnalare.add(id); }
                        }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                for (Integer id : ordinati) {
                    if (!inizi.get(slotPerPrenotazione.get(id)).isAfter(adesso)) {
                        throw new ConflittoPrenotazioneException("L'appuntamento è già iniziato.");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione SET indisponibilita_segnalata = TRUE
                        WHERE id_prenotazione = ? AND stato = ? AND indisponibilita_segnalata = FALSE
                        """)) {
                    for (Integer id : ordinati) {
                        if (!daSegnalare.contains(id)) { continue; }
                        statement.setInt(1, id);
                        statement.setString(2, stati.get(id).name());
                        if (statement.executeUpdate() != 1) {
                            throw new ConflittoPrenotazioneException("Prenotazione modificata durante la segnalazione.");
                        }
                    }
                }
                SegnalazioneIndisponibilitaResponse result = new SegnalazioneIndisponibilitaResponse();
                result.setIdPrenotazioni(List.copyOf(ids));
                result.setIndisponibilitaSegnalata(true);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                if (completed) {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public List<SegnalazionePendenteResponse> findSegnalazioniPendenti() throws SQLException {
        List<SegnalazionePendenteResponse> result = new ArrayList<>();
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT pr.id_prenotazione, pr.stato, pr.indisponibilita_segnalata,
                            pr.id_paziente, up.nome AS nome_paziente, up.cognome AS cognome_paziente,
                            d.id_disponibilita, d.data_ora_inizio, d.data_ora_fine,
                            d.id_medico AS id_medico_titolare,
                            ut.nome AS nome_titolare, ut.cognome AS cognome_titolare,
                            pr.id_medico_effettivo, ue.nome AS nome_effettivo, ue.cognome AS cognome_effettivo,
                            p.id_prestazione, p.nome AS nome_prestazione, s.id_stanza, s.nome AS nome_stanza
                     FROM prenotazione pr
                     JOIN utente up ON up.id_utente = pr.id_paziente
                     JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                     JOIN medico mt ON mt.id_utente = d.id_medico
                     JOIN utente ut ON ut.id_utente = mt.id_utente
                     LEFT JOIN medico me ON me.id_utente = pr.id_medico_effettivo
                     LEFT JOIN utente ue ON ue.id_utente = me.id_utente
                     LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                     LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
                     WHERE pr.indisponibilita_segnalata = TRUE AND pr.stato IN ('RICHIESTA', 'CONFERMATA')
                     ORDER BY d.data_ora_inizio, pr.id_prenotazione
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                SegnalazionePendenteResponse row = new SegnalazionePendenteResponse();
                row.setIdPrenotazione(rows.getInt("id_prenotazione"));
                row.setIdPaziente(rows.getInt("id_paziente"));
                row.setNomePaziente(rows.getString("nome_paziente"));
                row.setCognomePaziente(rows.getString("cognome_paziente"));
                row.setStato(StatoPrenotazione.valueOf(rows.getString("stato")));
                row.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
                row.setIdDisponibilita(rows.getInt("id_disponibilita"));
                row.setDataOraInizio(rows.getObject("data_ora_inizio", LocalDateTime.class));
                row.setDataOraFine(rows.getObject("data_ora_fine", LocalDateTime.class));
                row.setIdMedicoTitolare(rows.getInt("id_medico_titolare"));
                row.setNomeMedicoTitolare(rows.getString("nome_titolare"));
                row.setCognomeMedicoTitolare(rows.getString("cognome_titolare"));
                row.setIdMedicoEffettivo(rows.getObject("id_medico_effettivo", Integer.class));
                row.setNomeMedicoEffettivo(rows.getString("nome_effettivo"));
                row.setCognomeMedicoEffettivo(rows.getString("cognome_effettivo"));
                row.setIdPrestazione(rows.getObject("id_prestazione", Integer.class));
                row.setNomePrestazione(rows.getString("nome_prestazione"));
                row.setIdStanza(rows.getObject("id_stanza", Integer.class));
                row.setNomeStanza(rows.getString("nome_stanza"));
                result.add(row);
            }
        }
        return result;
    }

    /** Solo sviluppo/test fino a UC-02: gli ID dichiarati non autenticano il chiamante. */
    public SostituzioneMedicoResponse sostituisciMedicoAtomically(Integer idPrenotazione,
            Integer idOperatore, Integer idSostituto, StatoPrenotazione statoAtteso, Integer idIndisponibile)
            throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0
                || idSostituto == null || idSostituto <= 0 || idIndisponibile == null || idIndisponibile <= 0
                || (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA)) {
            throw new IllegalArgumentException("ID positivi e statoAtteso RICHIESTA o CONFERMATA obbligatori.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!RuoloGestionale.OPERATORE_DESK.name().equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                Integer slot, titolare, effettivo;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT pr.id_disponibilita, pr.id_medico_effettivo, d.id_medico
                        FROM prenotazione pr JOIN disponibilita d ON d.id_disponibilita=pr.id_disponibilita
                        WHERE pr.id_prenotazione=?
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        slot = rows.getInt("id_disponibilita"); titolare = rows.getInt("id_medico");
                        effettivo = rows.getObject("id_medico_effettivo", Integer.class);
                    }
                }
                java.util.Set<Integer> medici = new java.util.TreeSet<>();
                medici.add(titolare); medici.add(idSostituto); if (effettivo != null) { medici.add(effettivo); }
                String statoSostituto = null;
                for (Integer medico : medici) {
                    String stato = StatoMedico.blocca(connection, medico, true);
                    if (stato == null) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                    if (medico.equals(idSostituto)) { statoSostituto = stato; }
                }
                Integer prestazione;
                LocalDateTime inizio, fine;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico,id_prestazione,id_stanza,data_ora_inizio,data_ora_fine
                        FROM disponibilita WHERE id_disponibilita=? FOR UPDATE
                        """)) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || !titolare.equals(rows.getObject("id_medico", Integer.class))) {
                            throw new ConflittoPrenotazioneException("Disponibilità o titolare modificati.");
                        }
                        prestazione = rows.getObject("id_prestazione", Integer.class);
                        if (prestazione == null || rows.getObject("id_stanza") == null) {
                            throw new ConflittoPrenotazioneException("Disponibilità non configurata.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                        fine = rows.getObject("data_ora_fine", LocalDateTime.class);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita,id_medico_effettivo,stato,gestita_da,data_ora_gestione,indisponibilita_segnalata
                        FROM prenotazione WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || !slot.equals(rows.getObject("id_disponibilita", Integer.class))
                                || !Objects.equals(effettivo, rows.getObject("id_medico_effettivo", Integer.class))) {
                            throw new ConflittoPrenotazioneException("Discovery della prenotazione obsoleta.");
                        }
                        if (!statoAtteso.name().equals(rows.getString("stato")) || !rows.getBoolean("indisponibilita_segnalata")) {
                            throw new ConflittoPrenotazioneException("Prenotazione non più sostituibile.");
                        }
                        boolean gestione = rows.getObject("gestita_da") != null, data = rows.getObject("data_ora_gestione") != null;
                        if ((statoAtteso == StatoPrenotazione.RICHIESTA && (effettivo != null || gestione || data))
                                || (statoAtteso == StatoPrenotazione.CONFERMATA && (effettivo == null || !gestione || !data))) {
                            throw new ConflittoPrenotazioneException("Dati della prenotazione incoerenti.");
                        }
                    }
                }
                Integer indisponibile = statoAtteso == StatoPrenotazione.RICHIESTA ? titolare : effettivo;
                if (!idIndisponibile.equals(indisponibile) || idSostituto.equals(indisponibile)) {
                    throw new ConflittoPrenotazioneException("Medico indisponibile atteso errato o sostituto coincidente.");
                }
                // UC-23: solo il nuovo medico effettivo deve essere operativo; il titolare originario può non
                // esserlo. Copre anche il ritorno al titolare, che in quel caso è il sostituto.
                if (!StatoMedico.operativo(statoSostituto)) {
                    throw new ConflittoPrenotazioneException(StatoMedico.NON_OPERATIVO);
                }
                if (!java.time.Duration.between(inizio, fine).equals(java.time.Duration.ofHours(1))) {
                    throw new ConflittoPrenotazioneException("Lo slot deve durare esattamente 60 minuti.");
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT id_prestazione FROM prestazione WHERE id_prestazione=? FOR SHARE")) {
                    statement.setInt(1, prestazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new ConflittoPrenotazioneException("Prestazione associata non trovata."); }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_medico FROM medico_prestazione WHERE id_medico=? AND id_prestazione=? FOR SHARE")) {
                    statement.setInt(1, idSostituto); statement.setInt(2, prestazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new ConflittoPrenotazioneException("Sostituto non abilitato alla prestazione."); }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (!inizio.isAfter(adesso)) { throw new ConflittoPrenotazioneException("L'appuntamento è già iniziato."); }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita FROM disponibilita
                        WHERE id_medico=? AND id_disponibilita<>? AND data_ora_inizio<? AND data_ora_fine>?
                        """)) {
                    statement.setInt(1, idSostituto); statement.setInt(2, slot);
                    statement.setObject(3, fine); statement.setObject(4, inizio);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) { throw new ConflittoPrenotazioneException("Sostituto con disponibilità incompatibile."); }
                    }
                }
                if (ImpegniMedico.haAppuntamenti(connection, idSostituto, inizio, fine, idPrenotazione)) {
                    throw new ConflittoPrenotazioneException("Sostituto con appuntamento incompatibile.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione SET stato='CONFERMATA',id_medico_effettivo=?,indisponibilita_segnalata=FALSE,
                                                gestita_da=?,data_ora_gestione=?
                        WHERE id_prenotazione=? AND stato=? AND indisponibilita_segnalata=TRUE
                          AND id_disponibilita=? AND id_medico_effettivo <=> ?
                        """)) {
                    statement.setInt(1, idSostituto); statement.setInt(2, idOperatore); statement.setObject(3, adesso);
                    statement.setInt(4, idPrenotazione); statement.setString(5, statoAtteso.name());
                    statement.setInt(6, slot); statement.setObject(7, effettivo);
                    if (statement.executeUpdate() != 1) { throw new ConflittoPrenotazioneException("Prenotazione modificata durante la sostituzione."); }
                }
                var result = new SostituzioneMedicoResponse();
                result.setIdPrenotazione(idPrenotazione); result.setStato(StatoPrenotazione.CONFERMATA);
                result.setIdMedicoEffettivo(idSostituto); result.setIndisponibilitaSegnalata(false);
                result.setIdOperatore(idOperatore); result.setDataOraGestione(adesso);
                connection.commit(); completed = true; return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                if (completed) {
                    try { connection.setTransactionIsolation(originalIsolation); connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) { if (failure != null) { failure.addSuppressed(restoreError); } else { throw restoreError; } }
                }
            }
        }
    }

    public ConfermaPrenotazioneResponse confermaAtomically(Integer idPrenotazione, Integer idOperatore)
            throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!RuoloGestionale.OPERATORE_DESK.name().equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                Integer idDisponibilita;
                // Lettura preliminare senza lock: il risultato viene verificato di nuovo sotto lock.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione = ?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        idDisponibilita = rows.getInt("id_disponibilita");
                    }
                }
                Integer medicoPreliminare;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new ConflittoPrenotazioneException("Disponibilità associata non trovata."); }
                        medicoPreliminare = rows.getInt("id_medico");
                    }
                }
                // Anticipa la FK del medico effettivo prima del lock sullo slot. Il titolare diventa
                // medico effettivo: deve essere operativo (UC-23), verificato sotto lo stesso mutex.
                String statoTitolare = StatoMedico.blocca(connection, medicoPreliminare, true);
                if (statoTitolare == null) { throw new ConflittoPrenotazioneException("Medico titolare non trovato."); }
                if (!StatoMedico.operativo(statoTitolare)) {
                    throw new ConflittoPrenotazioneException(StatoMedico.NON_OPERATIVO);
                }
                Integer idMedico;
                LocalDateTime inizio, fine;
                boolean configurata;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new ConflittoPrenotazioneException("Disponibilità associata non trovata.");
                        }
                        idMedico = rows.getInt("id_medico");
                        if (!idMedico.equals(medicoPreliminare)) {
                            throw new ConflittoPrenotazioneException("Medico titolare modificato durante la conferma.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                        fine = rows.getObject("data_ora_fine", LocalDateTime.class);
                        configurata = rows.getObject("id_prestazione") != null && rows.getObject("id_stanza") != null;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, stato, gestita_da, data_ora_gestione, id_medico_effettivo, indisponibilita_segnalata
                        FROM prenotazione WHERE id_prenotazione = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (!idDisponibilita.equals(rows.getObject("id_disponibilita", Integer.class))
                                || !StatoPrenotazione.RICHIESTA.name().equals(rows.getString("stato"))) {
                            throw new ConflittoPrenotazioneException("Prenotazione non più confermabile.");
                        }
                        if (rows.getBoolean("indisponibilita_segnalata")) {
                            throw new ConflittoPrenotazioneException("Indisponibilità segnalata: occorre gestire un sostituto.");
                        }
                        if (rows.getObject("gestita_da") != null || rows.getObject("data_ora_gestione") != null
                                || rows.getObject("id_medico_effettivo") != null) {
                            throw new ConflittoPrenotazioneException("Dati della richiesta incoerenti.");
                        }
                    }
                }
                if (!configurata) { throw new ConflittoPrenotazioneException("Disponibilità non configurata."); }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (!inizio.isAfter(adesso)) {
                    throw new ConflittoPrenotazioneException("L'appuntamento è già iniziato.");
                }
                if (ImpegniMedico.haAppuntamenti(connection, idMedico, inizio, fine, idPrenotazione)) {
                    throw new ConflittoPrenotazioneException("Medico con appuntamento incompatibile.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione
                        SET stato = ?, gestita_da = ?, data_ora_gestione = ?, id_medico_effettivo = ?
                        WHERE id_prenotazione = ? AND stato = ?
                        """)) {
                    statement.setString(1, StatoPrenotazione.CONFERMATA.name());
                    statement.setInt(2, idOperatore);
                    statement.setObject(3, adesso);
                    statement.setInt(4, idMedico);
                    statement.setInt(5, idPrenotazione);
                    statement.setString(6, StatoPrenotazione.RICHIESTA.name());
                    if (statement.executeUpdate() != 1) {
                        throw new ConflittoPrenotazioneException("Prenotazione non più confermabile.");
                    }
                }
                ConfermaPrenotazioneResponse result = new ConfermaPrenotazioneResponse();
                result.setIdPrenotazione(idPrenotazione);
                result.setStato(StatoPrenotazione.CONFERMATA);
                result.setIdOperatore(idOperatore);
                result.setDataOraGestione(adesso);
                result.setIdMedicoEffettivo(idMedico);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                // Non riattivare autocommit se il rollback fallisce.
                if (completed) {
                    try { connection.setTransactionIsolation(originalIsolation); connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public RifiutoPrenotazioneResponse rifiutaAtomically(Integer idPrenotazione, Integer idOperatore)
            throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!RuoloGestionale.OPERATORE_DESK.name().equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                Integer idDisponibilita;
                // Lettura preliminare senza lock: il risultato viene verificato di nuovo sotto lock.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione = ?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        idDisponibilita = rows.getInt("id_disponibilita");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new ConflittoPrenotazioneException("Disponibilità associata non trovata.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, stato, gestita_da, data_ora_gestione, id_medico_effettivo
                        FROM prenotazione WHERE id_prenotazione = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (!idDisponibilita.equals(rows.getObject("id_disponibilita", Integer.class))
                                || !StatoPrenotazione.RICHIESTA.name().equals(rows.getString("stato"))) {
                            throw new ConflittoPrenotazioneException("Prenotazione non più rifiutabile.");
                        }
                        if (rows.getObject("gestita_da") != null || rows.getObject("data_ora_gestione") != null
                                || rows.getObject("id_medico_effettivo") != null) {
                            throw new ConflittoPrenotazioneException("Dati della richiesta incoerenti.");
                        }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione
                        SET stato = ?, gestita_da = ?, data_ora_gestione = ?, indisponibilita_segnalata = FALSE
                        WHERE id_prenotazione = ? AND stato = ?
                        """)) {
                    statement.setString(1, StatoPrenotazione.RIFIUTATA.name());
                    statement.setInt(2, idOperatore);
                    statement.setObject(3, adesso);
                    statement.setInt(4, idPrenotazione);
                    statement.setString(5, StatoPrenotazione.RICHIESTA.name());
                    if (statement.executeUpdate() != 1) {
                        throw new ConflittoPrenotazioneException("Prenotazione non più rifiutabile.");
                    }
                }
                RifiutoPrenotazioneResponse result = new RifiutoPrenotazioneResponse();
                result.setIdPrenotazione(idPrenotazione);
                result.setStato(StatoPrenotazione.RIFIUTATA);
                result.setIdOperatore(idOperatore);
                result.setDataOraGestione(adesso);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                // Non riattivare autocommit se il rollback fallisce.
                if (completed) {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }


    /** UC-21: slot → prenotazione → pagamenti; nessun mutex tardivo sul medico.
     * I futuri scrittori di pagamenti devono bloccare la prenotazione e verificarne lo stato.
     */
    public RegistrazioneVisitaResponse registraVisitaAtomically(Integer idPrenotazione, Integer idMedico) throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idMedico == null || idMedico <= 0) {
            throw new IllegalArgumentException("Identificativi non validi.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            // Letture correnti e assenza di gap lock superflui sulla ricerca dei pagamenti assenti.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Throwable failure = null;
            boolean completed = false;
            try {
                connection.setAutoCommit(false);
                int slot;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=? AND id_medico_effettivo=?")) {
                    statement.setInt(1, idPrenotazione);
                    statement.setInt(2, idMedico);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        slot = rows.getInt(1);
                    }
                }
                LocalDateTime fine;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT data_ora_fine FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                        fine = rows.getObject("data_ora_fine", LocalDateTime.class);
                    }
                }
                Prenotazione prenotazione = new Prenotazione();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita,stato,gestita_da,data_ora_gestione,id_medico_effettivo,
                               indisponibilita_segnalata
                        FROM prenotazione WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (rows.getObject("id_medico_effettivo") == null
                                || rows.getInt("id_medico_effettivo") != idMedico) {
                            throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                        }
                        if (rows.getInt("id_disponibilita") != slot
                                || !StatoPrenotazione.CONFERMATA.name().equals(rows.getString("stato"))
                                || rows.getObject("gestita_da") == null || rows.getObject("data_ora_gestione") == null) {
                            throw new ConflittoPrenotazioneException("Prenotazione non registrabile o incoerente.");
                        }
                        prenotazione.setStato(StatoPrenotazione.CONFERMATA);
                        prenotazione.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
                    }
                }
                if (prenotazione.isIndisponibilitaSegnalata()) {
                    throw new ConflittoPrenotazioneException("Indisponibilità ancora da gestire.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_pagamento,stato FROM pagamento WHERE id_prenotazione=?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (StatoPagamento.IN_ATTESA.name().equals(rows.getString("stato"))) {
                                throw new ConflittoPrenotazioneException("Pagamento in attesa: verifica non consentita.");
                            }
                        }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (fine == null || adesso.isBefore(fine)) {
                    throw new ConflittoPrenotazioneException("Lo slot non è ancora terminato.");
                }
                prenotazione.registraVisita();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione SET stato='EFFETTUATA'
                        WHERE id_prenotazione=? AND id_medico_effettivo=? AND id_disponibilita=?
                          AND stato='CONFERMATA' AND indisponibilita_segnalata=FALSE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    statement.setInt(2, idMedico);
                    statement.setInt(3, slot);
                    if (statement.executeUpdate() != 1) {
                        throw new ConflittoPrenotazioneException("Prenotazione modificata.");
                    }
                }
                RegistrazioneVisitaResponse result = new RegistrazioneVisitaResponse();
                result.setIdPrenotazione(idPrenotazione);
                result.setStato(prenotazione.getStato());
                connection.commit(); completed = true; return result;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                if (completed) {
                    try { connection.setTransactionIsolation(originalIsolation); connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); } else { throw restoreError; }
                    }
                }
            }
        }
    }

    // UC-15: NON_EROGATA è un accertamento dell'operatore, non una prova automatica della causa.
    public VerificaMancataErogazioneResponse verificaMancataErogazioneAtomically(Integer idPrenotazione,
            Integer idOperatore, StatoPrenotazione statoAtteso, StatoPrenotazione esito) throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0
                || statoAtteso != StatoPrenotazione.CONFERMATA
                || (esito != StatoPrenotazione.PAZIENTE_ASSENTE && esito != StatoPrenotazione.NON_EROGATA)) {
            throw new IllegalArgumentException("Dati di verifica non validi.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            // Letture correnti e assenza di gap lock superflui sulla ricerca dei pagamenti assenti.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Throwable failure = null;
            boolean completed = false;
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!RuoloGestionale.OPERATORE_DESK.name().equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                int slot;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        slot = rows.getInt(1);
                    }
                }
                LocalDateTime fine;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT data_ora_fine FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                        fine = rows.getObject("data_ora_fine", LocalDateTime.class);
                    }
                }
                Prenotazione prenotazione = new Prenotazione();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita,stato,gestita_da,data_ora_gestione,id_medico_effettivo,
                               indisponibilita_segnalata
                        FROM prenotazione WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (rows.getInt("id_disponibilita") != slot || !statoAtteso.name().equals(rows.getString("stato"))
                                || rows.getObject("gestita_da") == null || rows.getObject("data_ora_gestione") == null
                                || rows.getObject("id_medico_effettivo") == null) {
                            throw new ConflittoPrenotazioneException("Prenotazione non verificabile o incoerente.");
                        }
                        prenotazione.setStato(statoAtteso);
                        prenotazione.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_pagamento,stato FROM pagamento WHERE id_prenotazione=?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (StatoPagamento.IN_ATTESA.name().equals(rows.getString("stato"))) {
                                throw new ConflittoPrenotazioneException("Pagamento in attesa: verifica non consentita.");
                            }
                        }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (fine == null || adesso.isBefore(fine)) {
                    throw new ConflittoPrenotazioneException("Lo slot non è ancora terminato.");
                }
                if (esito == StatoPrenotazione.PAZIENTE_ASSENTE && prenotazione.isIndisponibilitaSegnalata()) {
                    throw new ConflittoPrenotazioneException("Indisponibilità ancora da gestire.");
                }
                if (esito == StatoPrenotazione.NON_EROGATA) {
                    prenotazione.registraMancataErogazioneAmministrativa();
                } else { prenotazione.registraAssenzaPaziente(); }
                LocalDateTime timestampGestione = adesso.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                String sql = "UPDATE prenotazione SET stato=?,gestita_da=?,data_ora_gestione=?"
                        + (esito == StatoPrenotazione.NON_EROGATA ? ",indisponibilita_segnalata=FALSE" : "")
                        + " WHERE id_prenotazione=? AND stato=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, prenotazione.getStato().name()); statement.setInt(2, idOperatore);
                    statement.setObject(3, timestampGestione); statement.setInt(4, idPrenotazione);
                    statement.setString(5, statoAtteso.name());
                    if (statement.executeUpdate() != 1) { throw new ConflittoPrenotazioneException("Prenotazione modificata."); }
                }
                VerificaMancataErogazioneResponse result = new VerificaMancataErogazioneResponse();
                result.setIdPrenotazione(idPrenotazione); result.setStato(prenotazione.getStato());
                result.setIdOperatore(idOperatore); result.setDataOraGestione(timestampGestione);
                connection.commit(); completed = true; return result;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                if (completed) {
                    try { connection.setTransactionIsolation(originalIsolation); connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); } else { throw restoreError; }
                    }
                }
            }
        }
    }

    public AnnullamentoPrenotazioneResponse annullaAtomically(Integer idPrenotazione, Integer idOperatore,
                                                               StatoPrenotazione statoAtteso)
            throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        if (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA) {
            throw new IllegalArgumentException("statoAtteso deve essere RICHIESTA o CONFERMATA.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!RuoloGestionale.OPERATORE_DESK.name().equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                Integer idDisponibilita;
                // Lettura preliminare senza lock: il risultato viene verificato di nuovo sotto lock.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione = ?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        idDisponibilita = rows.getInt("id_disponibilita");
                    }
                }
                LocalDateTime inizio;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, data_ora_inizio
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new RisorsaNonTrovataException("Disponibilità associata non trovata.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, stato, gestita_da, data_ora_gestione, id_medico_effettivo
                        FROM prenotazione WHERE id_prenotazione = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (!idDisponibilita.equals(rows.getObject("id_disponibilita", Integer.class))
                                || !statoAtteso.name().equals(rows.getString("stato"))) {
                            throw new ConflittoPrenotazioneException("Prenotazione non più annullabile.");
                        }
                        boolean gestita = rows.getObject("gestita_da") != null;
                        boolean dataGestione = rows.getObject("data_ora_gestione") != null;
                        boolean medico = rows.getObject("id_medico_effettivo") != null;
                        if ((statoAtteso == StatoPrenotazione.RICHIESTA && (gestita || dataGestione || medico))
                                || (statoAtteso == StatoPrenotazione.CONFERMATA && (!gestita || !dataGestione || !medico))) {
                            throw new ConflittoPrenotazioneException("Dati della prenotazione incoerenti.");
                        }
                    }
                }
                // I futuri flussi di pagamento devono bloccare prima la prenotazione,
                // poi inserire/completare pagamenti: questa lettura non sostituisce quel protocollo.
                boolean pagamentoBloccante = false;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_pagamento, stato FROM pagamento WHERE id_prenotazione = ?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (statoAtteso == StatoPrenotazione.RICHIESTA
                                    || !StatoPagamento.FALLITO.name().equals(rows.getString("stato"))) {
                                pagamentoBloccante = true;
                            }
                        }
                    }
                }
                if (pagamentoBloccante) {
                    throw new ConflittoPrenotazioneException("Pagamenti associati impediscono l'annullamento.");
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (statoAtteso == StatoPrenotazione.CONFERMATA && !inizio.isAfter(adesso)) {
                    throw new ConflittoPrenotazioneException("L'appuntamento è già iniziato.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione
                        SET stato = ?, gestita_da = ?, data_ora_gestione = ?, indisponibilita_segnalata = FALSE
                        WHERE id_prenotazione = ? AND stato = ?
                        """)) {
                    statement.setString(1, StatoPrenotazione.ANNULLATA.name());
                    statement.setInt(2, idOperatore);
                    statement.setObject(3, adesso);
                    statement.setInt(4, idPrenotazione);
                    statement.setString(5, statoAtteso.name());
                    if (statement.executeUpdate() != 1) {
                        throw new ConflittoPrenotazioneException("Prenotazione non più annullabile.");
                    }
                }
                AnnullamentoPrenotazioneResponse result = new AnnullamentoPrenotazioneResponse();
                result.setIdPrenotazione(idPrenotazione);
                result.setStato(StatoPrenotazione.ANNULLATA);
                result.setIdOperatore(idOperatore);
                result.setDataOraGestione(adesso);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                // Non riattivare autocommit se il rollback fallisce.
                if (completed) {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public AnnullamentoPazienteResponse annullaPazienteAtomically(Integer idPrenotazione, Integer idPaziente,
                                                               StatoPrenotazione statoAtteso)
            throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idPaziente == null || idPaziente <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        if (statoAtteso != StatoPrenotazione.RICHIESTA && statoAtteso != StatoPrenotazione.CONFERMATA) {
            throw new IllegalArgumentException("statoAtteso deve essere RICHIESTA o CONFERMATA.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_utente FROM paziente WHERE id_utente = ? FOR SHARE")) {
                    statement.setInt(1, idPaziente);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Paziente non trovato."); }
                    }
                }
                Integer idDisponibilita;
                // Lettura preliminare senza lock: il risultato viene verificato di nuovo sotto lock.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione = ?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        idDisponibilita = rows.getInt("id_disponibilita");
                    }
                }
                LocalDateTime inizio;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita, data_ora_inizio
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new RisorsaNonTrovataException("Disponibilità associata non trovata.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_paziente, id_disponibilita, stato, gestita_da, data_ora_gestione, id_medico_effettivo
                        FROM prenotazione WHERE id_prenotazione = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (!idPaziente.equals(rows.getObject("id_paziente", Integer.class))) {
                            throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                        }
                        if (!idDisponibilita.equals(rows.getObject("id_disponibilita", Integer.class))
                                || !statoAtteso.name().equals(rows.getString("stato"))) {
                            throw new ConflittoPrenotazioneException("Prenotazione non più annullabile.");
                        }
                        boolean gestita = rows.getObject("gestita_da") != null;
                        boolean dataGestione = rows.getObject("data_ora_gestione") != null;
                        boolean medico = rows.getObject("id_medico_effettivo") != null;
                        if ((statoAtteso == StatoPrenotazione.RICHIESTA && (gestita || dataGestione || medico))
                                || (statoAtteso == StatoPrenotazione.CONFERMATA && (!gestita || !dataGestione || !medico))) {
                            throw new ConflittoPrenotazioneException("Dati della prenotazione incoerenti.");
                        }
                    }
                }
                // I futuri flussi di pagamento devono bloccare prima la prenotazione,
                // verificarne lo stato, poi inserire/completare pagamenti.
                // Questa lettura non sostituisce quel protocollo.
                boolean pagamentoBloccante = false;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_pagamento, stato FROM pagamento WHERE id_prenotazione = ?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (statoAtteso == StatoPrenotazione.RICHIESTA
                                    || !StatoPagamento.FALLITO.name().equals(rows.getString("stato"))) {
                                pagamentoBloccante = true;
                            }
                        }
                    }
                }
                if (pagamentoBloccante) {
                    throw new ConflittoPrenotazioneException("Pagamenti associati impediscono l'annullamento.");
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (!inizio.isAfter(adesso)) {
                    throw new ConflittoPrenotazioneException("L'appuntamento è già iniziato.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE prenotazione
                        SET stato = 'ANNULLATA', indisponibilita_segnalata = FALSE
                        WHERE id_prenotazione = ? AND id_paziente = ? AND stato = ?
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    statement.setInt(2, idPaziente);
                    statement.setString(3, statoAtteso.name());
                    if (statement.executeUpdate() != 1) {
                        throw new ConflittoPrenotazioneException("Prenotazione non più annullabile.");
                    }
                }
                AnnullamentoPazienteResponse result = new AnnullamentoPazienteResponse();
                result.setIdPrenotazione(idPrenotazione);
                result.setStato(StatoPrenotazione.ANNULLATA);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                // Non riattivare autocommit se il rollback fallisce.
                if (completed) {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public List<AppuntamentoPazienteResponse> findByPaziente(Integer idPaziente, StatoPrenotazione stato,
            LocalDate dataDa, LocalDate dataA, LocalDateTime adesso) throws SQLException {
        try (Connection connection = connectionFactory.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id_utente FROM paziente WHERE id_utente = ?")) {
                statement.setInt(1, idPaziente);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { throw new RisorsaNonTrovataException("Paziente non trovato."); }
                }
            }
            StringBuilder sql = new StringBuilder("""
                    SELECT pr.id_prenotazione, pr.stato, pr.data_ora_richiesta, pr.prezzo,
                           d.id_disponibilita, d.data_ora_inizio, d.data_ora_fine,
                           p.id_prestazione, p.nome AS nome_prestazione, p.durata AS durata_prestazione,
                           d.id_medico AS id_medico_titolare, ut.nome AS nome_medico_titolare,
                           ut.cognome AS cognome_medico_titolare,
                           pr.id_medico_effettivo, ue.nome AS nome_medico_effettivo,
                           ue.cognome AS cognome_medico_effettivo, s.id_stanza, s.nome AS nome_stanza
                    FROM prenotazione pr
                    JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                    JOIN medico mt ON mt.id_utente = d.id_medico
                    JOIN utente ut ON ut.id_utente = mt.id_utente
                    LEFT JOIN medico me ON me.id_utente = pr.id_medico_effettivo
                    LEFT JOIN utente ue ON ue.id_utente = me.id_utente
                    LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                    LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
                    WHERE pr.id_paziente = ?
                    """);
            if (stato != null) { sql.append(" AND pr.stato = ?"); }
            if (dataDa != null) { sql.append(" AND d.data_ora_inizio >= ?"); }
            if (dataA != null) { sql.append(" AND d.data_ora_inizio < ?"); }
            sql.append("""
                     ORDER BY CASE WHEN d.data_ora_fine > ? THEN 0 ELSE 1 END ASC,
                              CASE WHEN d.data_ora_fine > ? THEN d.data_ora_inizio END ASC,
                              CASE WHEN d.data_ora_fine <= ? THEN d.data_ora_inizio END DESC,
                              pr.data_ora_richiesta DESC, pr.id_prenotazione DESC
                    """);
            List<AppuntamentoPazienteResponse> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setInt(index++, idPaziente);
                if (stato != null) { statement.setString(index++, stato.name()); }
                if (dataDa != null) { statement.setObject(index++, dataDa.atStartOfDay()); }
                if (dataA != null) { statement.setObject(index++, dataA.plusDays(1).atStartOfDay()); }
                // La medesima lettura del Clock governa tutti i CASE, indipendentemente dallo stato.
                statement.setObject(index++, adesso);
                statement.setObject(index++, adesso);
                statement.setObject(index, adesso);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) { result.add(mapAppuntamentoPaziente(rows)); }
                }
            }
            return result;
        }
    }

    private AppuntamentoPazienteResponse mapAppuntamentoPaziente(ResultSet rows) throws SQLException {
        AppuntamentoPazienteResponse result = new AppuntamentoPazienteResponse();
        result.setIdPrenotazione(rows.getObject("id_prenotazione", Integer.class));
        result.setStato(StatoPrenotazione.valueOf(rows.getString("stato")));
        result.setDataOraRichiesta(rows.getObject("data_ora_richiesta", LocalDateTime.class));
        result.setPrezzo(rows.getBigDecimal("prezzo"));
        result.setIdDisponibilita(rows.getObject("id_disponibilita", Integer.class));
        result.setDataOraInizio(rows.getObject("data_ora_inizio", LocalDateTime.class));
        result.setDataOraFine(rows.getObject("data_ora_fine", LocalDateTime.class));
        result.setIdPrestazione(rows.getObject("id_prestazione", Integer.class));
        result.setNomePrestazione(rows.getString("nome_prestazione"));
        result.setDurataPrestazione(rows.getObject("durata_prestazione", Integer.class));
        result.setIdMedicoTitolare(rows.getObject("id_medico_titolare", Integer.class));
        result.setNomeMedicoTitolare(rows.getString("nome_medico_titolare"));
        result.setCognomeMedicoTitolare(rows.getString("cognome_medico_titolare"));
        result.setIdMedicoEffettivo(rows.getObject("id_medico_effettivo", Integer.class));
        result.setNomeMedicoEffettivo(rows.getString("nome_medico_effettivo"));
        result.setCognomeMedicoEffettivo(rows.getString("cognome_medico_effettivo"));
        result.setIdStanza(rows.getObject("id_stanza", Integer.class));
        result.setNomeStanza(rows.getString("nome_stanza"));
        return result;
    }

    public List<AppuntamentoAgendaResponse> findAgenda(Integer idMedico, LocalDate dataDa, LocalDate dataA)
            throws SQLException {
        try (Connection connection = connectionFactory.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id_utente FROM medico WHERE id_utente = ?")) {
                statement.setInt(1, idMedico);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                }
            }
            List<AppuntamentoAgendaResponse> agenda = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT pr.id_prenotazione, pr.id_paziente, pr.stato, pr.id_medico_effettivo, pr.indisponibilita_segnalata,
                           pr.gestita_da, pr.data_ora_gestione,
                           EXISTS (SELECT 1 FROM referto r WHERE r.id_prenotazione = pr.id_prenotazione) AS referto_presente,
                           d.id_disponibilita, d.data_ora_inizio, d.data_ora_fine,
                           d.id_medico AS id_medico_titolare,
                           up.nome AS nome_paziente, up.cognome AS cognome_paziente,
                           p.id_prestazione, p.nome AS nome_prestazione, p.durata AS durata_prestazione,
                           s.id_stanza, s.nome AS nome_stanza
                    FROM prenotazione pr
                    JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                    JOIN paziente pa ON pa.id_utente = pr.id_paziente
                    JOIN utente up ON up.id_utente = pa.id_utente
                    LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                    LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
                    WHERE d.data_ora_inizio >= ? AND d.data_ora_inizio < ?
                      AND ((pr.stato = 'RICHIESTA' AND d.id_medico = ?)
                        OR (pr.stato IN ('CONFERMATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA')
                            AND (pr.id_medico_effettivo = ?
                                 OR (pr.id_medico_effettivo IS NULL AND d.id_medico = ?))))
                    ORDER BY d.data_ora_inizio ASC, d.id_disponibilita ASC, pr.id_prenotazione ASC
                    """)) {
                statement.setObject(1, dataDa.atStartOfDay());
                statement.setObject(2, dataA.plusDays(1).atStartOfDay());
                statement.setInt(3, idMedico);
                statement.setInt(4, idMedico);
                statement.setInt(5, idMedico);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) { agenda.add(mapAgendaRow(rows)); }
                }
            }
            return agenda;
        }
    }

    private AppuntamentoAgendaResponse mapAgendaRow(ResultSet rows) throws SQLException {
        StatoPrenotazione stato = StatoPrenotazione.valueOf(rows.getString("stato"));
        Integer effettivo = rows.getObject("id_medico_effettivo", Integer.class);
        boolean gestione = rows.getObject("gestita_da") != null;
        boolean dataGestione = rows.getObject("data_ora_gestione") != null;
        // Le righe senza medico effettivo entrano nella query solo per diagnosticare
        // anomalie del titolare e del periodo richiesti, mai come fallback di attribuzione.
        if ((stato == StatoPrenotazione.RICHIESTA && (effettivo != null || gestione || dataGestione))
                || (stato != StatoPrenotazione.RICHIESTA && (effettivo == null || !gestione || !dataGestione))) {
            throw new SQLException("Dati incoerenti nell'agenda richiesta.");
        }
        AppuntamentoAgendaResponse result = new AppuntamentoAgendaResponse();
        result.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
        result.setRefertoPresente(rows.getBoolean("referto_presente"));
        result.setIdPrenotazione(rows.getObject("id_prenotazione", Integer.class));
        result.setIdDisponibilita(rows.getObject("id_disponibilita", Integer.class));
        result.setStato(stato);
        result.setDataOraInizio(rows.getObject("data_ora_inizio", LocalDateTime.class));
        result.setDataOraFine(rows.getObject("data_ora_fine", LocalDateTime.class));
        result.setIdMedicoTitolare(rows.getObject("id_medico_titolare", Integer.class));
        result.setIdMedicoEffettivo(rows.getObject("id_medico_effettivo", Integer.class));
        result.setIdPrestazione(rows.getObject("id_prestazione", Integer.class));
        result.setNomePrestazione(rows.getString("nome_prestazione"));
        result.setDurataPrestazione(rows.getObject("durata_prestazione", Integer.class));
        result.setIdStanza(rows.getObject("id_stanza", Integer.class));
        result.setNomeStanza(rows.getString("nome_stanza"));
        result.setIdPaziente(rows.getObject("id_paziente", Integer.class));
        result.setNomePaziente(rows.getString("nome_paziente"));
        result.setCognomePaziente(rows.getString("cognome_paziente"));
        return result;
    }

    /**
     * prenotazioni con inizio nel periodo, negli stati successivi alla gestione desk, con
     * pagamenti e rimborsi. Una sola SELECT: InnoDB la esegue su un'unica lettura consistente non
     * bloccante, senza query per prenotazione. Il rimborso è al più uno per pagamento
     * (uq_rimborso_pagamento), quindi i LEFT JOIN producono una riga per pagamento.
     */
    public List<PrenotazioneDeskResponse> findPrenotazioniDesk(Integer idMedico, LocalDate dataDa, LocalDate dataA)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT pr.id_prenotazione, pr.stato, pr.indisponibilita_segnalata, pr.prezzo,
                       pr.gestita_da, pr.data_ora_gestione,
                       d.id_disponibilita, d.data_ora_inizio, d.data_ora_fine,
                       pr.id_paziente, up.nome AS nome_paziente, up.cognome AS cognome_paziente,
                       d.id_medico AS id_medico_titolare, ut.nome AS nome_titolare, ut.cognome AS cognome_titolare,
                       pr.id_medico_effettivo, ue.nome AS nome_effettivo, ue.cognome AS cognome_effettivo,
                       p.id_prestazione, p.nome AS nome_prestazione, s.id_stanza, s.nome AS nome_stanza,
                       pg.id_pagamento, pg.modalita, pg.importo AS importo_pagamento, pg.stato AS stato_pagamento,
                       pg.creato_il, pg.pagato_il, pg.registrato_da,
                       r.id_rimborso, r.stato AS stato_rimborso, r.importo AS importo_rimborso,
                       r.richiesto_il, r.verificato_il, r.verificato_da
                FROM prenotazione pr
                JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                JOIN paziente pa ON pa.id_utente = pr.id_paziente
                JOIN utente up ON up.id_utente = pa.id_utente
                JOIN medico mt ON mt.id_utente = d.id_medico
                JOIN utente ut ON ut.id_utente = mt.id_utente
                LEFT JOIN medico me ON me.id_utente = pr.id_medico_effettivo
                LEFT JOIN utente ue ON ue.id_utente = me.id_utente
                LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
                LEFT JOIN pagamento pg ON pg.id_prenotazione = pr.id_prenotazione
                LEFT JOIN rimborso r ON r.id_pagamento = pg.id_pagamento
                WHERE d.data_ora_inizio >= ? AND d.data_ora_inizio < ?
                  AND pr.stato IN ('CONFERMATA', 'ANNULLATA', 'EFFETTUATA', 'PAZIENTE_ASSENTE', 'NON_EROGATA')
                """);
        if (idMedico != null) { sql.append(" AND pr.id_medico_effettivo = ?"); }
        sql.append(" ORDER BY d.data_ora_inizio ASC, pr.id_prenotazione ASC, pg.id_pagamento ASC");
        List<PrenotazioneDeskResponse> result = new ArrayList<>();
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setObject(1, dataDa.atStartOfDay());
            statement.setObject(2, dataA.plusDays(1).atStartOfDay());
            if (idMedico != null) { statement.setInt(3, idMedico); }
            try (ResultSet rows = statement.executeQuery()) {
                PrenotazioneDeskResponse current = null;
                while (rows.next()) {
                    int id = rows.getInt("id_prenotazione");
                    if (current == null || current.getIdPrenotazione() != id) {
                        current = mapPrenotazioneDesk(rows);
                        result.add(current);
                    }
                    Integer pagamento = rows.getObject("id_pagamento", Integer.class);
                    if (pagamento == null) { continue; }
                    List<PrenotazioneDeskResponse.Pagamento> pagamenti = current.getPagamenti();
                    // Ordine per ID: un pagamento ripetuto indicherebbe più rimborsi, esclusi dallo schema.
                    if (!pagamenti.isEmpty() && pagamenti.getLast().idPagamento() >= pagamento) {
                        throw new SQLException("Dati incoerenti nell'elenco desk.");
                    }
                    pagamenti.add(new PrenotazioneDeskResponse.Pagamento(pagamento,
                            ModalitaPagamento.valueOf(rows.getString("modalita")),
                            rows.getBigDecimal("importo_pagamento"),
                            StatoPagamento.valueOf(rows.getString("stato_pagamento")),
                            rows.getObject("creato_il", LocalDateTime.class),
                            rows.getObject("pagato_il", LocalDateTime.class),
                            rows.getObject("registrato_da", Integer.class)));
                    Integer rimborso = rows.getObject("id_rimborso", Integer.class);
                    if (rimborso != null) {
                        current.getRimborsi().add(new PrenotazioneDeskResponse.Rimborso(rimborso, pagamento,
                                StatoRimborso.valueOf(rows.getString("stato_rimborso")),
                                rows.getBigDecimal("importo_rimborso"),
                                rows.getObject("richiesto_il", LocalDateTime.class),
                                rows.getObject("verificato_il", LocalDateTime.class),
                                rows.getObject("verificato_da", Integer.class)));
                    }
                }
            }
        }
        for (PrenotazioneDeskResponse row : result) {
            row.getRimborsi().sort(java.util.Comparator.comparing(PrenotazioneDeskResponse.Rimborso::idRimborso));
        }
        return result;
    }

    private PrenotazioneDeskResponse mapPrenotazioneDesk(ResultSet rows) throws SQLException {
        StatoPrenotazione stato = StatoPrenotazione.valueOf(rows.getString("stato"));
        Integer effettivo = rows.getObject("id_medico_effettivo", Integer.class);
        Integer gestitaDa = rows.getObject("gestita_da", Integer.class);
        LocalDateTime gestione = rows.getObject("data_ora_gestione", LocalDateTime.class);
        // Come per l'agenda: gli stati derivati da una conferma richiedono gestione e medico effettivo.
        // ANNULLATA può derivare anche da una RICHIESTA e conservare valori nulli.
        if ((stato != StatoPrenotazione.ANNULLATA && (effettivo == null || gestitaDa == null || gestione == null))
                || (effettivo != null && rows.getString("nome_effettivo") == null)) {
            throw new SQLException("Dati incoerenti nell'elenco desk.");
        }
        PrenotazioneDeskResponse result = new PrenotazioneDeskResponse();
        result.setIdPrenotazione(rows.getInt("id_prenotazione"));
        result.setStato(stato);
        result.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
        result.setPrezzo(rows.getBigDecimal("prezzo"));
        result.setIdDisponibilita(rows.getInt("id_disponibilita"));
        result.setDataOraInizio(rows.getObject("data_ora_inizio", LocalDateTime.class));
        result.setDataOraFine(rows.getObject("data_ora_fine", LocalDateTime.class));
        result.setIdPaziente(rows.getInt("id_paziente"));
        result.setNomePaziente(rows.getString("nome_paziente"));
        result.setCognomePaziente(rows.getString("cognome_paziente"));
        result.setIdMedicoTitolare(rows.getInt("id_medico_titolare"));
        result.setNomeMedicoTitolare(rows.getString("nome_titolare"));
        result.setCognomeMedicoTitolare(rows.getString("cognome_titolare"));
        result.setIdMedicoEffettivo(effettivo);
        result.setNomeMedicoEffettivo(rows.getString("nome_effettivo"));
        result.setCognomeMedicoEffettivo(rows.getString("cognome_effettivo"));
        result.setIdPrestazione(rows.getObject("id_prestazione", Integer.class));
        result.setNomePrestazione(rows.getString("nome_prestazione"));
        result.setIdStanza(rows.getObject("id_stanza", Integer.class));
        result.setNomeStanza(rows.getString("nome_stanza"));
        result.setGestitaDa(gestitaDa);
        result.setDataOraGestione(gestione);
        return result;
    }

    public List<RichiestaPrenotazioneResponse> findRichieste(Integer idMedico, Integer idPrestazione,
                                                          LocalDate dataDa, LocalDate dataA)
            throws SQLException {
        return findRichieste(idMedico, idPrestazione, dataDa, dataA, StatoPrenotazione.RICHIESTA);
    }

    /** Stato null: elenco completo per i soli filtri richiesti, stessa proiezione del titolare. */
    public List<RichiestaPrenotazioneResponse> findRichieste(Integer idMedico, Integer idPrestazione,
            LocalDate dataDa, LocalDate dataA, StatoPrenotazione stato) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT pr.id_prenotazione, pr.stato, pr.data_ora_richiesta, pr.prezzo, pr.indisponibilita_segnalata,
                       d.data_ora_inizio, d.data_ora_fine,
                       pa.id_utente AS id_paziente, up.nome AS nome_paziente,
                       up.cognome AS cognome_paziente,
                       m.id_utente AS id_medico, um.nome AS nome_medico,
                       um.cognome AS cognome_medico,
                       p.id_prestazione, p.nome AS nome_prestazione, p.durata AS durata_prestazione,
                       s.id_stanza, s.nome AS nome_stanza
                FROM prenotazione pr
                JOIN paziente pa ON pa.id_utente = pr.id_paziente
                JOIN utente up ON up.id_utente = pa.id_utente
                JOIN disponibilita d ON d.id_disponibilita = pr.id_disponibilita
                JOIN medico m ON m.id_utente = d.id_medico
                JOIN utente um ON um.id_utente = m.id_utente
                LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
                LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
                WHERE 1 = 1
                """);
        if (stato != null) { sql.append(" AND pr.stato = ?"); }
        if (idMedico != null) { sql.append(" AND d.id_medico = ?"); }
        if (idPrestazione != null) { sql.append(" AND d.id_prestazione = ?"); }
        if (dataDa != null) { sql.append(" AND d.data_ora_inizio >= ?"); }
        if (dataA != null) { sql.append(" AND d.data_ora_inizio < ?"); }
        sql.append(" ORDER BY d.data_ora_inizio ASC, pr.data_ora_richiesta ASC, pr.id_prenotazione ASC");
        List<RichiestaPrenotazioneResponse> result = new ArrayList<>();
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (stato != null) { statement.setString(index++, stato.name()); }
            if (idMedico != null) { statement.setInt(index++, idMedico); }
            if (idPrestazione != null) { statement.setInt(index++, idPrestazione); }
            if (dataDa != null) { statement.setObject(index++, dataDa.atStartOfDay()); }
            if (dataA != null) { statement.setObject(index, dataA.plusDays(1).atStartOfDay()); }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { result.add(mapRichiesta(rows)); }
            }
        }
        return result;
    }

    private RichiestaPrenotazioneResponse mapRichiesta(ResultSet rows) throws SQLException {
        RichiestaPrenotazioneResponse response = new RichiestaPrenotazioneResponse();
        response.setIndisponibilitaSegnalata(rows.getBoolean("indisponibilita_segnalata"));
        response.setIdPrenotazione(rows.getInt("id_prenotazione"));
        response.setStato(StatoPrenotazione.valueOf(rows.getString("stato")));
        response.setDataOraRichiesta(rows.getObject("data_ora_richiesta", LocalDateTime.class));
        response.setDataOraInizio(rows.getObject("data_ora_inizio", LocalDateTime.class));
        response.setDataOraFine(rows.getObject("data_ora_fine", LocalDateTime.class));
        response.setPrezzo(rows.getBigDecimal("prezzo"));
        response.setIdPaziente(rows.getInt("id_paziente"));
        response.setNomePaziente(rows.getString("nome_paziente"));
        response.setCognomePaziente(rows.getString("cognome_paziente"));
        response.setIdMedico(rows.getInt("id_medico"));
        response.setNomeMedico(rows.getString("nome_medico"));
        response.setCognomeMedico(rows.getString("cognome_medico"));
        response.setIdPrestazione(rows.getObject("id_prestazione", Integer.class));
        response.setNomePrestazione(rows.getString("nome_prestazione"));
        response.setDurataPrestazione(rows.getObject("durata_prestazione", Integer.class));
        response.setIdStanza(rows.getObject("id_stanza", Integer.class));
        response.setNomeStanza(rows.getString("nome_stanza"));
        return response;
    }

    public Prenotazione creaRichiestaAtomically(Integer idPaziente, Integer idDisponibilita)
            throws SQLException {
        if (idPaziente == null || idPaziente <= 0 || idDisponibilita == null || idDisponibilita <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            // UC-23: titolare letto in autocommit, prima della transazione. Una lettura non bloccante
            // dentro la transazione fisserebbe lo snapshot REPEATABLE READ prima delle attese sui lock.
            Integer titolarePreliminare;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) {
                statement.setInt(1, idDisponibilita);
                try (ResultSet rows = statement.executeQuery()) {
                    titolarePreliminare = rows.next() ? rows.getInt("id_medico") : null;
                }
            }
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                Paziente paziente = lockPaziente(connection, idPaziente);
                if (titolarePreliminare == null) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                // Medico prima dello slot, come UC-11/17/19/27: FOR SHARE serializza con l'inattivazione UC-23.
                String statoTitolare = StatoMedico.blocca(connection, titolarePreliminare, false);
                if (statoTitolare == null) { throw new SQLException("Medico titolare non trovato."); }
                if (!StatoMedico.operativo(statoTitolare)) {
                    throw new ConflittoPrenotazioneException(StatoMedico.NON_OPERATIVO);
                }
                LocalDateTime inizio;
                Integer idPrestazione;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico, data_ora_inizio, id_prestazione, id_stanza
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new RisorsaNonTrovataException("Disponibilità non trovata.");
                        }
                        if (rows.getInt("id_medico") != titolarePreliminare) {
                            throw new ConflittoPrenotazioneException("Titolare della disponibilità modificato.");
                        }
                        idPrestazione = rows.getObject("id_prestazione", Integer.class);
                        if (idPrestazione == null || rows.getObject("id_stanza") == null) {
                            throw new ConflittoPrenotazioneException("Disponibilità non configurata.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                    }
                }
                BigDecimal prezzo;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT prezzo FROM prestazione WHERE id_prestazione = ? FOR SHARE")) {
                    statement.setInt(1, idPrestazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new SQLException("Prestazione associata non trovata."); }
                        prezzo = rows.getBigDecimal("prezzo");
                    }
                }
                // Il clock è letto dopo le attese sui lock, mai prima della transazione.
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (!inizio.isAfter(adesso)) {
                    throw new ConflittoPrenotazioneException("La disponibilità non è più futura.");
                }
                if (hasPrenotazioneBloccante(connection, idDisponibilita)) {
                    throw new ConflittoPrenotazioneException("Disponibilità già occupata.");
                }
                Integer idPrenotazione;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO prenotazione
                        (id_paziente, id_disponibilita, stato, data_ora_richiesta, prezzo,
                         gestita_da, data_ora_gestione, id_medico_effettivo)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setInt(1, idPaziente);
                    statement.setInt(2, idDisponibilita);
                    statement.setString(3, StatoPrenotazione.RICHIESTA.name());
                    statement.setObject(4, adesso);
                    statement.setBigDecimal(5, prezzo);
                    if (statement.executeUpdate() != 1) { throw new SQLException("Richiesta non inserita."); }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) { throw new SQLException("ID prenotazione non disponibile."); }
                        idPrenotazione = keys.getInt(1);
                    }
                }
                Disponibilita slot = disponibilitaRepository.findById(connection, idDisponibilita)
                        .orElseThrow(() -> new SQLException("Disponibilità richiesta non trovata."));
                Prenotazione result = new Prenotazione();
                result.setIdPrenotazione(idPrenotazione);
                result.setPaziente(paziente);
                result.setDisponibilita(slot);
                result.setStato(StatoPrenotazione.RICHIESTA);
                result.setDataOraRichiesta(adesso);
                result.setPrezzo(prezzo);
                connection.commit();
                completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try {
                    connection.rollback();
                    completed = true;
                } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                // Ripristinare autocommit dopo un rollback fallito potrebbe confermare dati parziali.
                if (completed) {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    private Paziente lockPaziente(Connection connection, Integer idPaziente) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id_utente, p.codice_fiscale, p.data_nascita, p.telefono,
                       u.nome, u.cognome, u.email, u.password_hash, u.ruolo_gestionale
                FROM paziente p JOIN utente u ON u.id_utente = p.id_utente
                WHERE p.id_utente = ? FOR SHARE
                """)) {
            statement.setInt(1, idPaziente);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new RisorsaNonTrovataException("Paziente non trovato."); }
                Utente utente = new Utente();
                utente.setIdUtente(rows.getInt("id_utente"));
                utente.setNome(rows.getString("nome"));
                utente.setCognome(rows.getString("cognome"));
                utente.setEmail(rows.getString("email"));
                utente.setPasswordHash(rows.getString("password_hash"));
                String ruolo = rows.getString("ruolo_gestionale");
                utente.setRuoloGestionale(ruolo == null ? null : RuoloGestionale.valueOf(ruolo));
                Paziente paziente = new Paziente();
                paziente.setUtente(utente);
                paziente.setCodiceFiscale(rows.getString("codice_fiscale"));
                paziente.setDataNascita(rows.getObject("data_nascita", LocalDate.class));
                paziente.setTelefono(rows.getString("telefono"));
                return paziente;
            }
        }
    }

    private boolean hasPrenotazioneBloccante(Connection connection, Integer idDisponibilita)
            throws SQLException {
        List<StatoPrenotazione> stati = DisponibilitaRepository.statiBloccanti();
        String sql = "SELECT id_prenotazione FROM prenotazione WHERE id_disponibilita = ? AND stato IN ("
                + String.join(", ", Collections.nCopies(stati.size(), "?")) + ") LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idDisponibilita);
            for (int i = 0; i < stati.size(); i++) { statement.setString(i + 2, stati.get(i).name()); }
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }
}
