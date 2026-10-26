package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.CaricamentoRefertoResponse;
import it.medbook.exception.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

public class RefertoRepository {
    private final ConnectionFactory factory;
    private final Clock clock;
    public RefertoRepository(ConnectionFactory factory, Clock clock) { this.factory = factory; this.clock = clock; }

    /** Risultato interno, mai serializzato verso il client. */
    public record Documento(Integer idReferto, Integer idPrenotazione, String nomeFile,
                            LocalDateTime caricatoIl, String chiaveStorage) { }

    public Documento findForPatient(int booking, int patient) throws SQLException {
        try (Connection connection = factory.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.id_referto,r.id_prenotazione,r.nome_file,r.percorso_file,r.caricato_il
                     FROM referto r
                     JOIN prenotazione pr ON pr.id_prenotazione=r.id_prenotazione
                     JOIN paziente p ON p.id_utente=pr.id_paziente
                     WHERE pr.id_prenotazione=? AND p.id_utente=?
                     """)) {
            statement.setInt(1, booking); statement.setInt(2, patient);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new RisorsaNonTrovataException("Referto non trovato."); }
                return new Documento(rows.getInt("id_referto"), rows.getInt("id_prenotazione"),
                        rows.getString("nome_file"), rows.getObject("caricato_il", LocalDateTime.class),
                        rows.getString("percorso_file"));
            }
        }
    }

    /* Controllo preliminare senza lock; quello definitivo rimane in saveAtomically. */
    public void verifyCompetence(int booking, int doctor) throws SQLException {
        try (Connection connection = factory.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id_prenotazione FROM prenotazione WHERE id_prenotazione=? AND id_medico_effettivo=?")) {
            statement.setInt(1, booking); statement.setInt(2, doctor);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
            }
        }
    }

    /** Stato locale alla richiesta: un commit tentato non diventa mai 'cleanup sicuro'. */
    public static final class Outcome {
        private boolean safeToDelete = true;
        private boolean commitAttempted;
        public boolean safeToDelete() { return safeToDelete; }
    }
    public CaricamentoRefertoResponse saveAtomically(int booking, int doctor, String key, String name,
            Outcome outcome) throws SQLException {
        try (Connection connection = factory.getConnection()) {
            boolean auto = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            boolean completed = false;
            Throwable failure = null;
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                connection.setAutoCommit(false);
                String stato = StatoMedico.blocca(connection, doctor, false);
                if (stato == null) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                // Permesso MEDICO perso dopo il controllo HTTP: 403; il file pubblicato viene scartato
                // dal service perché il commit non è stato tentato.
                if (!StatoMedico.operativo(stato)) { throw new OperazioneNonAutorizzataException(StatoMedico.NON_OPERATIVO); }
                int slot;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=? AND id_medico_effettivo=?")) {
                    statement.setInt(1, booking); statement.setInt(2, doctor);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        slot = rows.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita,id_medico_effettivo,stato FROM prenotazione
                        WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    statement.setInt(1, booking);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getObject("id_medico_effettivo") == null
                                || rows.getInt("id_medico_effettivo") != doctor) {
                            throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                        }
                        if (rows.getInt("id_disponibilita") != slot || !"EFFETTUATA".equals(rows.getString("stato"))) {
                            throw new ConflittoPrenotazioneException("Prenotazione non compatibile con il caricamento.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_referto FROM referto WHERE id_prenotazione=?")) {
                    statement.setInt(1, booking);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) { throw new ConflittoPrenotazioneException("Referto già presente."); }
                    }
                }
                LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
                int id;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO referto (id_prenotazione,id_medico_caricamento,nome_file,percorso_file,caricato_il)
                        VALUES (?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setInt(1, booking); statement.setInt(2, doctor); statement.setString(3, name);
                    statement.setString(4, key); statement.setObject(5, now);
                    outcome.safeToDelete = false;
                    try {
                        if (statement.executeUpdate() != 1) { throw new SQLException("Inserimento referto incompleto."); }
                    } catch (SQLException e) {
                        if (e.getErrorCode() == 1062) { throw new ConflittoPrenotazioneException("Referto già presente."); }
                        throw e;
                    }
                    try (ResultSet rows = statement.getGeneratedKeys()) {
                        if (!rows.next()) { throw new SQLException("Identificativo referto non disponibile."); }
                        id = rows.getInt(1);
                    }
                }
                CaricamentoRefertoResponse response = new CaricamentoRefertoResponse();
                response.setIdReferto(id); response.setIdPrenotazione(booking);
                response.setNomeFile(name); response.setCaricatoIl(now);
                outcome.commitAttempted = true;
                connection.commit(); completed = true;
                return response;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try {
                    connection.rollback(); completed = true;
                    if (!outcome.commitAttempted) { outcome.safeToDelete = true; }
                } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally {
                if (completed) {
                    try { connection.setTransactionIsolation(isolation); connection.setAutoCommit(auto); }
                    catch (SQLException restore) {
                        if (failure != null) { failure.addSuppressed(restore); } else { throw restore; }
                    }
                }
            }
        }
    }
}
