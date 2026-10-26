package it.medbook.exception;

import java.sql.SQLException;

/*
 * Dati account non canonici o generazione fuori dominio, distinti dai guasti driver tramite tipo
 * dell'eccezione. Estende SQLException per conservare il 500 generico; messaggio fisso senza valore
 * memorizzato o causa driver.
 */
public class AnomaliaDatiAccountException extends SQLException {
    public AnomaliaDatiAccountException() {
        super("Dati account non coerenti.");
    }
}
