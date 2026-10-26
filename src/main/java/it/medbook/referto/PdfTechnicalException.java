package it.medbook.referto;

import java.io.IOException;

/** Errori I/O dell'archivio/cache: non devono essere classificati come PDF malformati. */
final class PdfTechnicalException extends IOException {
    PdfTechnicalException(IOException cause) { super("Errore tecnico durante la validazione PDF.", cause); }
}
