package it.medbook.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Diagnostica di sola lettura: disponibile soltanto nel classpath di test. */
public final class TestPrenotazioneDiagnostics {
    private TestPrenotazioneDiagnostics() { }

    public static List<Integer> terminalReports() throws SQLException {
        // Nessuna factory ricevuta dal chiamante: la destinazione deve superare i controlli di isolamento.
        try (Connection connection = TestDatabaseConnections.isolatedFactory().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id_prenotazione FROM prenotazione
                     WHERE indisponibilita_segnalata = TRUE
                       AND stato IN ('RIFIUTATA','ANNULLATA','EFFETTUATA','PAZIENTE_ASSENTE','NON_EROGATA')
                     ORDER BY id_prenotazione
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<Integer> result = new ArrayList<>();
            while (rows.next()) { result.add(rows.getInt(1)); }
            return result;
        }
    }
}
