package it.medbook.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/*
 * Ultima modifica effettiva di UTENTE, non storico: autore e istante nella stessa istruzione.
 * Operazioni senza cambiamenti e scritture limitate a MEDICO o PAZIENTE non aggiornano questi
 * metadati.
 */
final class UltimaModifica {
    /* Frammento SET per UPDATE, da associare ai parametri tramite imposta. */
    static final String SET = "modificato_da = ?, modificato_il = ?";

    private UltimaModifica() { }

    /*
     * Istante troncato ai secondi prima di MySQL, mai arrotondato. Letto dopo i lock decisivi e
     * appena prima della scrittura; senza lock, immediatamente prima. Non garantisce monotonia
     * stretta.
     */
    static LocalDateTime adesso(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /* Associa autore all'indice, NULL se ignoto o sistema, e istante all'indice successivo. */
    static void imposta(PreparedStatement statement, int indice, Integer autore, LocalDateTime il) throws SQLException {
        if (autore == null) { statement.setNull(indice, Types.INTEGER); } else { statement.setInt(indice, autore); }
        statement.setObject(indice + 1, Objects.requireNonNull(il));
    }
}
