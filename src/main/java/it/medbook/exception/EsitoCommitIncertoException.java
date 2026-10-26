package it.medbook.exception;

import java.sql.SQLException;

/*
 * commit ha lanciato: il server potrebbe avere confermato oppure no. Risposta 500 con cookie
 * scaduto e nessuna nuova sessione; il recupero richiede nuovo login.
 */
public class EsitoCommitIncertoException extends SQLException {
    public EsitoCommitIncertoException(Throwable cause) { super("Esito del commit non determinato.", cause); }
}
