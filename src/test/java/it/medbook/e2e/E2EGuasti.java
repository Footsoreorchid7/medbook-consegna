package it.medbook.e2e;

import java.sql.Connection;

/*
 * Punti di guasto della sola infrastruttura di test. La configurazione predefinita non cambia
 * nulla; i ganci possono fallire in una fase o sostituire una chiamata JDBC del launcher.
 */
interface E2EGuasti {
    E2EGuasti NESSUNO = new E2EGuasti() { };

    /* Fasi di preparazione; il dettaglio identifica catalogo, directory storage o porta listener. */
    enum Fase { PRIMA_CREATE, CATALOGO_CREATO, MARKER_SCRITTO, SCHEMA_COPIATO, STORAGE_CREATO, SERVER_IN_ASCOLTO }

    /* Scopo della connessione launcher: verifica, creazione, marcatore, template o eliminazione. */
    enum Scopo { VERIFICA, CREA, MARKER, MODELLO, ELIMINA }

    default void fase(Fase fase, String dettaglio) { }

    default Connection connessione(Scopo scopo, Connection connessione) { return connessione; }

    /** Failure raised by a fault-injection test. */
    final class Iniettato extends RuntimeException {
        Iniettato(String messaggio) { super(messaggio); }
    }
}
