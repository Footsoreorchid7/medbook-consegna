package it.medbook.exception;

/** UC-23 domain conflict on a doctor profile or on its account: mapped to HTTP 409. */
public class ConflittoMedicoException extends RuntimeException {
    public ConflittoMedicoException(String message) {
        super(message);
    }

    public ConflittoMedicoException(String message, Throwable cause) {
        super(message, cause);
    }
}
