package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.model.Prestazione;

import java.sql.Connection;
import java.sql.Statement;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoConfigurazioneException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PrestazioneRepository {
    private final ConnectionFactory connectionFactory;

    public PrestazioneRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public List<Prestazione> findAll() throws SQLException {
        String sql = "SELECT id_prestazione, nome, descrizione, durata, prezzo "
                + "FROM prestazione ORDER BY id_prestazione";
        List<Prestazione> prestazioni = new ArrayList<>();

        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                prestazioni.add(mapRow(resultSet));
            }
        }

        return prestazioni;
    }

    public Optional<Prestazione> findById(Integer idPrestazione) throws SQLException {
        String sql = "SELECT id_prestazione, nome, descrizione, durata, prezzo "
                + "FROM prestazione WHERE id_prestazione = ?";

        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idPrestazione);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
                return Optional.empty();
            }
        }
    }


    public Prestazione create(Prestazione value) throws SQLException {
        return write(null, value);
    }

    public Prestazione updateAtomically(Integer id, Prestazione value) throws SQLException {
        if (id == null || id <= 0) { throw new IllegalArgumentException("ID prestazione non valido."); }
        return write(id, value);
    }

    private Prestazione write(Integer id, Prestazione value) throws SQLException {
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            Throwable failure = null;
            boolean started = false;
            boolean completed = false;
            try {
                if (id != null) { connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); }
                connection.setAutoCommit(false);
                started = true;
                if (id != null) {
                    int durata;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT durata FROM prestazione WHERE id_prestazione = ? FOR UPDATE")) {
                        statement.setInt(1, id);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) { throw new RisorsaNonTrovataException("Prestazione non trovata."); }
                            durata = rows.getInt("durata");
                        }
                    }
                    if (durata != value.getDurata()) {
                        /* Nessun lock slot: la configurazione mantiene già FOR SHARE sulla prestazione prima dell'assegnazione. */
                        /* Mutex del padre e READ COMMITTED rendono visibili le assegnazioni confermate */
                        /* senza invertire l'ordine dei lock da slot a prestazione. */
                        try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT id_disponibilita FROM disponibilita WHERE id_prestazione = ? LIMIT 1")) {
                            statement.setInt(1, id);
                            try (ResultSet rows = statement.executeQuery()) {
                                if (rows.next()) { throw new ConflittoConfigurazioneException(
                                        "La durata non può cambiare quando esistono disponibilità associate."); }
                            }
                        }
                    }
                }
                String sql = id == null
                        ? "INSERT INTO prestazione (nome, descrizione, durata, prezzo) VALUES (?, ?, ?, ?)"
                        : "UPDATE prestazione SET nome = ?, descrizione = ?, durata = ?, prezzo = ? WHERE id_prestazione = ?";
                Integer resultId = id;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, value.getNome()); statement.setString(2, value.getDescrizione());
                    statement.setInt(3, value.getDurata()); statement.setBigDecimal(4, value.getPrezzo());
                    if (id != null) { statement.setInt(5, id); }
                    int changed = statement.executeUpdate();
                    /* MySQL può riportare zero righe cambiate per PUT identico; l'esistenza è bloccata. */
                    if (changed < 0 || changed > 1 || (id == null && changed != 1)) {
                        throw new SQLException("Scrittura prestazione non riuscita.");
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
                Prestazione result = new Prestazione();
                result.setIdPrestazione(resultId); result.setNome(value.getNome());
                result.setDescrizione(value.getDescrizione()); result.setDurata(value.getDurata());
                result.setPrezzo(value.getPrezzo());
                connection.commit(); completed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                if (started) {
                    try { connection.rollback(); completed = true; }
                    catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                }
                throw e;
            } finally {
                /* Mai cambiare autocommit dopo rollback incerto, per evitare commit impliciti. */
                if (completed || !started) {
                    try {
                        if (id != null) { connection.setTransactionIsolation(originalIsolation); }
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    private Prestazione mapRow(ResultSet resultSet) throws SQLException {
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(resultSet.getInt("id_prestazione"));
        prestazione.setNome(resultSet.getString("nome"));
        prestazione.setDescrizione(resultSet.getString("descrizione"));
        prestazione.setDurata(resultSet.getInt("durata"));
        prestazione.setPrezzo(resultSet.getBigDecimal("prezzo"));
        return prestazione;
    }
}
