package it.medbook.referto;

import java.io.IOException;

public class UploadNonValidoException extends IOException {
    private final int status;
    public UploadNonValidoException(int status) { super("Caricamento non valido."); this.status = status; }
    public int getStatus() { return status; }
}
