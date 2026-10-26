package it.medbook.e2e;

import it.medbook.referto.PdfRifiutatoException;
import it.medbook.referto.PdfValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Sostituisce soltanto la validazione PDF esterna per isolare le autorizzazioni. Repository,
 * storage e servizio restano reali. Ammette solo documenti registrati byte per byte, con firma PDF
 * ed EOF validi; nessun processo esterno. La validazione qpdf/PDFBox è verificata separatamente.
 */
public final class FixturePdfValidator extends PdfValidator {
    private final Set<String> accettati = ConcurrentHashMap.newKeySet();

    public FixturePdfValidator(Path scratch) {
        /* Il validatore qpdf applicativo non viene eseguito: validate è sovrascritto. */
        super(null, scratch);
    }

    /** PDF sintetico minimo; etichetta distingue documenti di fixture diverse. */
    public static byte[] documento(String etichetta) {
        if (!etichetta.matches("[A-Za-z0-9-]{1,40}")) { throw new IllegalArgumentException("Etichetta non valida."); }
        return ("%PDF-1.7\n% referto fittizio " + etichetta + "\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /* Registra un PDF di fixture ammesso e restituisce SHA-256 per le asserzioni. */
    public String accetta(byte[] documento) {
        String hash = sha256(documento);
        accettati.add(hash);
        return hash;
    }

    @Override public void validate(Path original) throws IOException {
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Documento di staging assente."); }
        byte[] contenuto = Files.readAllBytes(original);
        String testo = new String(contenuto, StandardCharsets.ISO_8859_1);
        if (!testo.startsWith("%PDF-1.") || !testo.stripTrailing().endsWith("%%EOF") || !accettati.contains(sha256(contenuto))) {
            throw new PdfRifiutatoException();
        }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
