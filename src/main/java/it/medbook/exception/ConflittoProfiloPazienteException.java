package it.medbook.exception;
/** Solo messaggi costanti, senza dati personali. Nessuna sovrascrittura di profili esistenti. */
public class ConflittoProfiloPazienteException extends RuntimeException {
    public ConflittoProfiloPazienteException(String message) { super(message); }
}
