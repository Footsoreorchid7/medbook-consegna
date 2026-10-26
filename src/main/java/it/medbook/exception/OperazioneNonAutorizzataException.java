package it.medbook.exception;

public class OperazioneNonAutorizzataException extends RuntimeException {
    public OperazioneNonAutorizzataException(String message) {
        super(message);
    }
}
