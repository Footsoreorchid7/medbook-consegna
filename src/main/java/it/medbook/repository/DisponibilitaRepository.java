package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.exception.ConflittoDisponibilitaException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import java.time.Clock;
import it.medbook.exception.ConflittoConfigurazioneException;
import it.medbook.model.Disponibilita;
import it.medbook.model.Medico;
import it.medbook.model.Prestazione;
import it.medbook.model.RuoloGestionale;
import it.medbook.model.Stanza;
import it.medbook.model.StatoPrenotazione;
import it.medbook.model.Utente;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DisponibilitaRepository {
    private static final String SELECT = """
            SELECT d.id_disponibilita, d.data_ora_inizio, d.data_ora_fine,
                   u.id_utente, u.nome AS utente_nome, u.cognome, u.email,
                   u.password_hash, u.ruolo_gestionale,
                   m.specializzazione, m.numero_iscrizione_ordine,
                   p.id_prestazione, p.nome AS prestazione_nome,
                   p.descrizione AS prestazione_descrizione, p.durata, p.prezzo,
                   s.id_stanza, s.nome AS stanza_nome, s.descrizione AS stanza_descrizione
            FROM disponibilita d
            JOIN medico m ON m.id_utente = d.id_medico
            JOIN utente u ON u.id_utente = m.id_utente
            LEFT JOIN prestazione p ON p.id_prestazione = d.id_prestazione
            LEFT JOIN stanza s ON s.id_stanza = d.id_stanza
            """;

    // Unica definizione degli stati che occupano lo slot, riutilizzabile per UC-05.
    private static final List<StatoPrenotazione> STATI_BLOCCANTI = List.of(
            StatoPrenotazione.RICHIESTA, StatoPrenotazione.CONFERMATA,
            StatoPrenotazione.EFFETTUATA, StatoPrenotazione.PAZIENTE_ASSENTE,
            StatoPrenotazione.NON_EROGATA);

    static List<StatoPrenotazione> statiBloccanti() {
        return STATI_BLOCCANTI;
    }

    private final ConnectionFactory connectionFactory;
    private final Clock clock;

    public DisponibilitaRepository(ConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemDefaultZone());
    }

    public DisponibilitaRepository(ConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = connectionFactory;
        this.clock = java.util.Objects.requireNonNull(clock, "Clock obbligatorio.");
    }

    /*
     * Regola condivisa da servizio e repository dopo mutex medico: inizio strettamente successivo
     * all'orologio nella sua zona, mai troncato. Uguaglianza con adesso rifiutata.
     */
    public static void richiediInizioFuturo(LocalDateTime inizio, Clock clock) {
        if (!inizio.isAfter(LocalDateTime.now(clock))) {
            throw new IllegalArgumentException("L'inizio della disponibilità deve essere futuro.");
        }
    }

    public List<Disponibilita> findByMedico(Integer idMedico) throws SQLException {
        List<Disponibilita> disponibilita = new ArrayList<>();
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + "WHERE d.id_medico = ? ORDER BY d.data_ora_inizio")) {
            statement.setInt(1, idMedico);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    disponibilita.add(mapRow(resultSet));
                }
            }
        }
        return disponibilita;
    }

    public List<Disponibilita> findPrenotabili(Integer idMedico, Integer idPrestazione,
                                             LocalDate data, LocalDateTime adesso) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT).append("""
                WHERE d.id_prestazione IS NOT NULL
                  AND d.id_stanza IS NOT NULL
                  AND %s
                  AND d.data_ora_inizio > ?
                  AND NOT EXISTS (
                      SELECT 1 FROM prenotazione pr
                      WHERE pr.id_disponibilita = d.id_disponibilita
                        AND pr.stato IN (
                """.formatted(StatoMedico.FILTRO_OPERATIVO_SQL));
        sql.append(String.join(", ", java.util.Collections.nCopies(STATI_BLOCCANTI.size(), "?")));
        sql.append("))");
        if (idMedico != null) { sql.append(" AND d.id_medico = ?"); }
        if (idPrestazione != null) { sql.append(" AND d.id_prestazione = ?"); }
        if (data != null) {
            sql.append(" AND d.data_ora_inizio >= ? AND d.data_ora_inizio < ?");
        }
        sql.append(" ORDER BY d.data_ora_inizio, d.id_disponibilita");
        List<Disponibilita> result = new ArrayList<>();
        try (Connection connection = connectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setObject(index++, adesso);
            for (StatoPrenotazione stato : STATI_BLOCCANTI) {
                statement.setString(index++, stato.name());
            }
            if (idMedico != null) { statement.setInt(index++, idMedico); }
            if (idPrestazione != null) { statement.setInt(index++, idPrestazione); }
            if (data != null) {
                statement.setObject(index++, data.atStartOfDay());
                statement.setObject(index, data.plusDays(1).atStartOfDay());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { result.add(mapRow(rows)); }
            }
        }
        return result;
    }

    public Optional<Disponibilita> findById(Integer idDisponibilita) throws SQLException {
        try (Connection connection = connectionFactory.getConnection()) {
            return findById(connection, idDisponibilita);
        }
    }

    public Disponibilita save(Integer idMedico, LocalDateTime dataOraInizio,
                             LocalDateTime dataOraFine) throws SQLException {
        Disponibilita slot = new Disponibilita();
        slot.setDataOraInizio(dataOraInizio);
        slot.setDataOraFine(dataOraFine);
        return saveAllAtomically(idMedico, List.of(slot)).getFirst();
    }

    public List<Disponibilita> saveAllAtomically(Integer idMedico, List<Disponibilita> slots)
            throws SQLException {
        validateSlots(idMedico, slots);
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean transactionCompleted = false;
            try {
                lockMedico(connection, idMedico);
                if (hasOverlap(connection, idMedico, slots.getFirst().getDataOraInizio(),
                        slots.getLast().getDataOraFine())) {
                    throw new IllegalArgumentException(
                            "L'intervallo si sovrappone a una disponibilità esistente del medico.");
                }
                if (ImpegniMedico.haAppuntamenti(connection, idMedico, slots.getFirst().getDataOraInizio(),
                        slots.getLast().getDataOraFine(), 0)) {
                    throw new IllegalArgumentException("Intervallo incompatibile con un appuntamento assegnato al medico.");
                }
                /* Ripete dopo le attese e prima del primo INSERT: il controllo del servizio può essere superato */
                /* durante l'attesa del mutex medico. Il rifiuto passa dal rollback. */
                richiediInizioFuturo(slots.getFirst().getDataOraInizio(), clock);
                List<Disponibilita> saved = new ArrayList<>();
                for (Disponibilita slot : slots) {
                    saved.add(insertSlot(connection, idMedico,
                            slot.getDataOraInizio(), slot.getDataOraFine()));
                }
                connection.commit();
                transactionCompleted = true;
                return saved;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try {
                    connection.rollback();
                    transactionCompleted = true;
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                // Non riattivare autocommit se il rollback fallisce: potrebbe confermare dati parziali.
                if (transactionCompleted) {
                    try {
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (SQLException restoreError) {
                        if (failure != null) {
                            failure.addSuppressed(restoreError);
                        } else {
                            throw restoreError;
                        }
                    }
                }
            }
        }
    }

    public Disponibilita configuraAtomically(Integer idDisponibilita, Integer idPrestazione,
                                             Integer idStanza) throws SQLException {
        if (idDisponibilita == null || idDisponibilita <= 0 || idPrestazione == null
                || idPrestazione <= 0 || idStanza == null || idStanza <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            // Prima della transazione: la discovery non rende obsoleta la lettura dei conflitti.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                Integer medicoPreliminare;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_medico FROM disponibilita WHERE id_disponibilita = ?")) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                        medicoPreliminare = rows.getInt("id_medico");
                    }
                }
                // Anticipa il lock FK: medico -> stanza -> slot. Lo stesso lock serializza l'inattivazione UC-23.
                String statoTitolare = StatoMedico.blocca(connection, medicoPreliminare, false);
                if (statoTitolare == null) { throw new ConflittoConfigurazioneException("Medico titolare non trovato."); }
                if (!StatoMedico.operativo(statoTitolare)) {
                    throw new ConflittoConfigurazioneException(StatoMedico.NON_OPERATIVO);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_stanza FROM stanza WHERE id_stanza = ? FOR UPDATE")) {
                    statement.setInt(1, idStanza);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Stanza non trovata."); }
                    }
                }
                Integer idMedico;
                LocalDateTime inizio;
                LocalDateTime fine;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                        if (rows.getObject("id_prestazione") != null || rows.getObject("id_stanza") != null) {
                            throw new ConflittoConfigurazioneException("Disponibilità già configurata, anche parzialmente.");
                        }
                        idMedico = rows.getInt("id_medico");
                        if (!idMedico.equals(medicoPreliminare)) {
                            throw new ConflittoConfigurazioneException("Medico titolare modificato durante la configurazione.");
                        }
                        inizio = rows.getObject("data_ora_inizio", LocalDateTime.class);
                        fine = rows.getObject("data_ora_fine", LocalDateTime.class);
                    }
                }
                if (!Duration.between(inizio, fine).equals(Duration.ofHours(1))) {
                    throw new ConflittoConfigurazioneException("La disponibilità deve durare esattamente 60 minuti.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT durata FROM prestazione WHERE id_prestazione = ? FOR SHARE")) {
                    statement.setInt(1, idPrestazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prestazione non trovata."); }
                        int durata = rows.getInt("durata");
                        if (durata < 1 || durata > 60) {
                            throw new ConflittoConfigurazioneException("La prestazione deve durare da 1 a 60 minuti.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico FROM medico_prestazione
                        WHERE id_medico = ? AND id_prestazione = ? FOR SHARE
                        """)) {
                    statement.setInt(1, idMedico);
                    statement.setInt(2, idPrestazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new ConflittoConfigurazioneException("Medico non abilitato alla prestazione.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita FROM disponibilita
                        WHERE id_stanza = ? AND id_disponibilita <> ?
                          AND data_ora_inizio < ? AND data_ora_fine > ?
                        """)) {
                    statement.setInt(1, idStanza);
                    statement.setInt(2, idDisponibilita);
                    statement.setObject(3, fine);
                    statement.setObject(4, inizio);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) { throw new ConflittoConfigurazioneException("Stanza occupata nell'intervallo richiesto."); }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE disponibilita SET id_prestazione = ?, id_stanza = ?
                        WHERE id_disponibilita = ?
                        """)) {
                    statement.setInt(1, idPrestazione);
                    statement.setInt(2, idStanza);
                    statement.setInt(3, idDisponibilita);
                    if (statement.executeUpdate() != 1) { throw new SQLException("Configurazione non riuscita."); }
                }
                Disponibilita result = findById(connection, idDisponibilita)
                        .orElseThrow(() -> new SQLException("Disponibilità configurata non trovata."));
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
                if (completed) {
                    try {
                        connection.setTransactionIsolation(originalIsolation);
                        connection.setAutoCommit(originalAutoCommit);
                    }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public Disponibilita modificaOrarioAtomically(Integer idDisponibilita, Integer idMedico,
            LocalDateTime inizio, LocalDateTime fine) throws SQLException {
        if (inizio == null || fine == null || !Duration.between(inizio, fine).equals(Duration.ofHours(1))
                || inizio.getNano() != 0 || fine.getNano() != 0
                || inizio.getYear() < 1000 || fine.getYear() > 9999) {
            throw new IllegalArgumentException("Il nuovo intervallo deve essere rappresentabile e durare 60 minuti.");
        }
        return mutaSlotAtomically(idDisponibilita, idMedico, inizio, fine);
    }

    public void eliminaAtomically(Integer idDisponibilita, Integer idMedico) throws SQLException {
        mutaSlotAtomically(idDisponibilita, idMedico, null, null);
    }

    private Disponibilita mutaSlotAtomically(Integer idDisponibilita, Integer idMedico,
            LocalDateTime inizio, LocalDateTime fine) throws SQLException {
        if (idDisponibilita == null || idDisponibilita <= 0 || idMedico == null || idMedico <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere maggiori di zero.");
        }
        try (Connection connection = connectionFactory.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean completed = false;
            try {
                // Stesso mutex della creazione: tutte le nuove scritture sugli orari
                // del medico sono serializzate prima di bloccare lo slot.
                String stato = StatoMedico.blocca(connection, idMedico, true);
                if (stato == null) { throw new RisorsaNonTrovataException("Medico non trovato."); }
                // Permesso MEDICO perso dopo il controllo HTTP: 403, non un conflitto di dominio.
                if (!StatoMedico.operativo(stato)) { throw new OperazioneNonAutorizzataException(StatoMedico.NON_OPERATIVO); }
                LocalDateTime originale;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_medico, data_ora_inizio, id_prestazione, id_stanza
                        FROM disponibilita WHERE id_disponibilita = ? FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getInt("id_medico") != idMedico) {
                            throw new RisorsaNonTrovataException("Disponibilità non trovata.");
                        }
                        if (rows.getObject("id_prestazione") != null || rows.getObject("id_stanza") != null) {
                            throw new ConflittoDisponibilitaException("Disponibilità già configurata, anche parzialmente.");
                        }
                        originale = rows.getObject("data_ora_inizio", LocalDateTime.class);
                    }
                }
                // Lettura corrente: conta ogni stato, non soltanto gli stati bloccanti di UC-04.
                // UC-05 e i lock FK degli INSERT devono attendere il nostro lock sullo slot.
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_prenotazione FROM prenotazione WHERE id_disponibilita = ?
                        ORDER BY id_prenotazione FOR UPDATE
                        """)) {
                    statement.setInt(1, idDisponibilita);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) { throw new ConflittoDisponibilitaException("Disponibilità con prenotazioni associate."); }
                    }
                }
                LocalDateTime adesso = LocalDateTime.now(clock);
                if (!originale.isAfter(adesso) || (inizio != null && !inizio.isAfter(adesso))) {
                    throw new ConflittoDisponibilitaException("La disponibilità e il nuovo orario devono essere futuri.");
                }
                if (inizio != null) {
                    /* Prima lettura consistente della transazione: vede gli orari confermati. */
                    // prima del mutex sul medico. Le altre scritture sono serializzate.
                    // Non bloccare slot estranei: UC-27 acquisisce stanza prima dello slot.
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT id_disponibilita FROM disponibilita
                            WHERE id_medico = ? AND id_disponibilita <> ?
                              AND data_ora_inizio < ? AND data_ora_fine > ?
                            """)) {
                        statement.setInt(1, idMedico); statement.setInt(2, idDisponibilita);
                        statement.setObject(3, fine); statement.setObject(4, inizio);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (rows.next()) { throw new ConflittoDisponibilitaException("Orario sovrapposto a un'altra disponibilità."); }
                        }
                    }
                }
                if (inizio != null && ImpegniMedico.haAppuntamenti(connection, idMedico, inizio, fine, 0)) {
                    throw new ConflittoDisponibilitaException("Orario incompatibile con un appuntamento assegnato al medico.");
                }
                String sql = inizio == null
                        ? "DELETE FROM disponibilita WHERE id_disponibilita = ? AND id_medico = ?"
                        : "UPDATE disponibilita SET data_ora_inizio = ?, data_ora_fine = ? WHERE id_disponibilita = ? AND id_medico = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    if (inizio != null) { statement.setObject(index++, inizio); statement.setObject(index++, fine); }
                    statement.setInt(index++, idDisponibilita); statement.setInt(index, idMedico);
                    if (statement.executeUpdate() != 1) { throw new SQLException("Mutazione disponibilità non riuscita."); }
                }
                Disponibilita result = inizio == null ? null : findById(connection, idDisponibilita)
                        .orElseThrow(() -> new SQLException("Disponibilità aggiornata non trovata."));
                connection.commit(); completed = true;
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

    private void validateSlots(Integer idMedico, List<Disponibilita> slots) {
        if (idMedico == null || idMedico <= 0) {
            throw new IllegalArgumentException("L'ID del medico deve essere maggiore di zero.");
        }
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("La lista degli slot non può essere vuota.");
        }
        LocalDateTime previousEnd = null;
        for (Disponibilita slot : slots) {
            if (slot == null || slot.getDataOraInizio() == null || slot.getDataOraFine() == null) {
                throw new IllegalArgumentException("Ogni slot deve avere inizio e fine valorizzati.");
            }
            if (!Duration.between(slot.getDataOraInizio(), slot.getDataOraFine())
                    .equals(Duration.ofHours(1))) {
                throw new IllegalArgumentException("Ogni slot deve durare esattamente 60 minuti.");
            }
            if (previousEnd != null && !previousEnd.equals(slot.getDataOraInizio())) {
                throw new IllegalArgumentException("Gli slot devono essere consecutivi e ordinati.");
            }
            previousEnd = slot.getDataOraFine();
        }
    }

    private void lockMedico(Connection connection, Integer idMedico) throws SQLException {
        String stato = StatoMedico.blocca(connection, idMedico, true);
        if (stato == null) {
            throw new IllegalArgumentException("Il medico indicato non esiste.");
        }
        // Permesso MEDICO perso dopo il controllo HTTP: 403, non un conflitto di dominio.
        if (!StatoMedico.operativo(stato)) { throw new OperazioneNonAutorizzataException(StatoMedico.NON_OPERATIVO); }
    }

    private boolean hasOverlap(Connection connection, Integer idMedico,
                               LocalDateTime inizio, LocalDateTime fine) throws SQLException {
        /* Mutex medico già acquisito: prima lettura consistente successiva al mutex. */
        // creazione e modifica degli orari rispettano la stessa serializzazione.
        String sql = """
                SELECT id_disponibilita FROM disponibilita
                WHERE id_medico = ? AND data_ora_inizio < ? AND data_ora_fine > ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, idMedico);
            statement.setObject(2, fine);
            statement.setObject(3, inizio);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private Disponibilita insertSlot(Connection connection, Integer idMedico,
                                    LocalDateTime inizio, LocalDateTime fine) throws SQLException {
        String sql = """
                INSERT INTO disponibilita
                    (id_medico, data_ora_inizio, data_ora_fine, id_prestazione, id_stanza)
                VALUES (?, ?, ?, NULL, NULL)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, idMedico);
            statement.setObject(2, inizio);
            statement.setObject(3, fine);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Inserimento della disponibilità non riuscito.");
            }
            Integer idDisponibilita;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("ID della disponibilità non restituito dal database.");
                }
                idDisponibilita = keys.getInt(1);
            }
            return findById(connection, idDisponibilita)
                    .orElseThrow(() -> new SQLException("Disponibilità inserita non trovata."));
        }
    }

    Optional<Disponibilita> findById(Connection connection, Integer idDisponibilita)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + "WHERE d.id_disponibilita = ?")) {
            statement.setInt(1, idDisponibilita);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    private Disponibilita mapRow(ResultSet resultSet) throws SQLException {
        Utente utente = new Utente();
        utente.setIdUtente(resultSet.getInt("id_utente"));
        utente.setNome(resultSet.getString("utente_nome"));
        utente.setCognome(resultSet.getString("cognome"));
        utente.setEmail(resultSet.getString("email"));
        utente.setPasswordHash(resultSet.getString("password_hash"));
        String ruolo = resultSet.getString("ruolo_gestionale");
        utente.setRuoloGestionale(ruolo == null ? null : RuoloGestionale.valueOf(ruolo));

        Medico medico = new Medico();
        medico.setUtente(utente);
        medico.setSpecializzazione(resultSet.getString("specializzazione"));
        medico.setNumeroIscrizioneOrdine(resultSet.getString("numero_iscrizione_ordine"));

        Disponibilita disponibilita = new Disponibilita();
        disponibilita.setIdDisponibilita(resultSet.getInt("id_disponibilita"));
        disponibilita.setDataOraInizio(resultSet.getObject("data_ora_inizio", LocalDateTime.class));
        disponibilita.setDataOraFine(resultSet.getObject("data_ora_fine", LocalDateTime.class));
        disponibilita.setMedico(medico);

        Integer idPrestazione = resultSet.getObject("id_prestazione", Integer.class);
        if (idPrestazione != null) {
            Prestazione prestazione = new Prestazione();
            prestazione.setIdPrestazione(idPrestazione);
            prestazione.setNome(resultSet.getString("prestazione_nome"));
            prestazione.setDescrizione(resultSet.getString("prestazione_descrizione"));
            prestazione.setDurata(resultSet.getInt("durata"));
            prestazione.setPrezzo(resultSet.getBigDecimal("prezzo"));
            disponibilita.setPrestazione(prestazione);
        }

        Integer idStanza = resultSet.getObject("id_stanza", Integer.class);
        if (idStanza != null) {
            Stanza stanza = new Stanza();
            stanza.setIdStanza(idStanza);
            stanza.setNome(resultSet.getString("stanza_nome"));
            stanza.setDescrizione(resultSet.getString("stanza_descrizione"));
            disponibilita.setStanza(stanza);
        }
        return disponibilita;
    }
}
