package it.medbook.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Lettura non bloccante da usare soltanto dopo il mutex esclusivo sul medico. */
final class ImpegniMedico {
    private ImpegniMedico() { }

    static boolean haAppuntamenti(Connection connection, Integer medico, LocalDateTime inizio,
                                  LocalDateTime fine, int prenotazioneEsclusa) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pr.id_prenotazione FROM prenotazione pr
                JOIN disponibilita d ON d.id_disponibilita=pr.id_disponibilita
                WHERE pr.id_medico_effettivo=? AND pr.id_prenotazione<>?
                  AND pr.stato IN ('CONFERMATA','EFFETTUATA','PAZIENTE_ASSENTE','NON_EROGATA')
                  AND d.data_ora_inizio<? AND d.data_ora_fine>?
                """)) {
            statement.setInt(1, medico); statement.setInt(2, prenotazioneEsclusa);
            statement.setObject(3, fine); statement.setObject(4, inizio);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }
}
