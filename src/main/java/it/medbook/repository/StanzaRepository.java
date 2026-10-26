package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.model.Stanza;

import java.sql.Connection;
import java.sql.Statement;
import it.medbook.exception.RisorsaNonTrovataException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class StanzaRepository {
    private final ConnectionFactory connectionFactory;

    public StanzaRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public List<Stanza> findAll() throws SQLException {
        String sql = "SELECT id_stanza, nome, descrizione "
                + "FROM stanza ORDER BY id_stanza ASC";
        List<Stanza> stanze = new ArrayList<>();

        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                stanze.add(mapRow(resultSet));
            }
        }

        return stanze;
    }

    public Stanza create(Stanza value) throws SQLException {
        return write(null, value);
    }

    public Stanza updateAtomically(Integer id, Stanza value) throws SQLException {
        if (id == null || id <= 0) { throw new IllegalArgumentException("ID stanza non valido."); }
        return write(id, value);
    }

    private Stanza write(Integer id, Stanza value) throws SQLException {
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            Throwable failure = null;
            boolean started = false;
            boolean completed = false;
            try {
                connection.setAutoCommit(false);
                started = true;
                if (id != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT id_stanza FROM stanza WHERE id_stanza = ? FOR UPDATE")) {
                        statement.setInt(1, id);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) { throw new RisorsaNonTrovataException("Stanza non trovata."); }
                        }
                    }
                    /* Solo mutex stanza: mai acquisire qui lock medico, slot o prenotazione. */
                }
                String sql = id == null
                        ? "INSERT INTO stanza (nome, descrizione) VALUES (?, ?)"
                        : "UPDATE stanza SET nome = ?, descrizione = ? WHERE id_stanza = ?";
                Integer resultId = id;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, value.getNome()); statement.setString(2, value.getDescrizione());
                    if (id != null) { statement.setInt(3, id); }
                    int changed = statement.executeUpdate();
                    /* MySQL può riportare zero righe cambiate per PUT identico; l'esistenza è già verificata sotto lock. */
                    if (changed < 0 || changed > 1 || (id == null && changed != 1)) {
                        throw new SQLException("Scrittura stanza non riuscita.");
                    }
                    if (id == null) {
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) { throw new SQLException("ID generato non disponibile."); }
                            long key = keys.getLong(1);
                            if (keys.wasNull() || key <= 0 || key > Integer.MAX_VALUE || keys.next()) {
                                throw new SQLException("ID generato non valido.");
                            }
                            resultId = (int) key;
                        }
                    }
                }
                Stanza result = new Stanza();
                result.setIdStanza(resultId); result.setNome(value.getNome());
                result.setDescrizione(value.getDescrizione());
                connection.commit(); completed = true;
                return result;
            } catch (SQLException | RuntimeException caught) {
                Throwable e = caught;
                /* Solo duplicati MySQL sul vincolo UNIQUE previsto sono conflitti. */
                if (e instanceof SQLException sqlError && isDuplicateName(sqlError)) {
                    e = new NomeDuplicatoException(sqlError);
                }
                failure = e;
                if (started) {
                    try { connection.rollback(); completed = true; }
                    catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                }
                if (e instanceof SQLException sqlError) { throw sqlError; }
                throw (RuntimeException) e;
            } finally {
                /* Non ripristinare autocommit dopo rollback incerto: potrebbe confermare la transazione. */
                if (completed || !started) {
                    try {
                        if (connection.getTransactionIsolation() != originalIsolation) { connection.setTransactionIsolation(originalIsolation); }
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public static final class NomeDuplicatoException extends RuntimeException {
        public NomeDuplicatoException(SQLException cause) { super("Nome stanza già presente.", cause); }
    }
    private static boolean isDuplicateName(SQLException error) {
        String message = error.getMessage();
        return error.getErrorCode() == 1062 && "23000".equals(error.getSQLState())
                && message != null && java.util.regex.Pattern.compile(
                    "(?i)for key ['`\"](?:[^'`\"]*\\.)?uq_stanza_nome['`\"]\\s*$").matcher(message).find();
    }

    private Stanza mapRow(ResultSet resultSet) throws SQLException {
        Stanza stanza = new Stanza();
        stanza.setIdStanza(resultSet.getInt("id_stanza"));
        stanza.setNome(resultSet.getString("nome"));
        stanza.setDescrizione(resultSet.getString("descrizione"));
        return stanza;
    }
}
