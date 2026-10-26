package it.medbook.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/*
 * Solo ATTIVO esatto è operativo. Il CHECK ai_ci ammette varianti non canoniche: confronto Java o
 * binario SQL, senza conversioni. INATTIVO e anomalie negano il permesso con messaggio neutro.
 */
final class StatoMedico {
    static final String NON_OPERATIVO = "Medico non operativo.";
    /* Filtro SQL su alias medico m, indipendente dalla collation. */
    static final String FILTRO_OPERATIVO_SQL = "CAST(m.stato AS BINARY) = CAST('ATTIVO' AS BINARY)";

    private StatoMedico() { }

    static boolean operativo(String stato) {
        return "ATTIVO".equals(stato);
    }

    /*
     * Acquisisce FOR SHARE o FOR UPDATE sulla riga medico e restituisce lo stato grezzo, oppure
     * null se assente; il chiamante decide l'errore.
     */
    static String blocca(Connection connection, int idMedico, boolean esclusivo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT stato FROM medico WHERE id_utente = ? " + (esclusivo ? "FOR UPDATE" : "FOR SHARE"))) {
            statement.setInt(1, idMedico);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { return null; }
                String stato = rows.getString("stato");
                return stato == null ? "" : stato;
            }
        }
    }
}
