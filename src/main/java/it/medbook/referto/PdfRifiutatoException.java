package it.medbook.referto;

import java.io.IOException;

/** Documento non ammissibile, distinto da indisponibilità o errori tecnici. */
public class PdfRifiutatoException extends IOException {
    public PdfRifiutatoException() { super("Documento PDF non valido o non consentito."); }
}
