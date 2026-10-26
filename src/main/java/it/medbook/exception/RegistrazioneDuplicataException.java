package it.medbook.exception;

/** Non distingue pubblicamente email e codice fiscale già presenti. */
public final class RegistrazioneDuplicataException extends RuntimeException {
    public RegistrazioneDuplicataException(Throwable cause){
        super("Registrazione non disponibile con i dati forniti.",cause);
    }
}
