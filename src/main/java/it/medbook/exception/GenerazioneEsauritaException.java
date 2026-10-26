package it.medbook.exception;

/*
 * Generazione già Long.MAX_VALUE: impossibile incrementare. Rollback senza cambiamenti e 409
 * generico, mai il valore.
 */
public class GenerazioneEsauritaException extends RuntimeException {
    public GenerazioneEsauritaException() { super("Operazione non disponibile per questo account."); }
}
