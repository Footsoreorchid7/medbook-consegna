package it.medbook.referto;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class PdfValidator {
    private static final java.util.logging.Logger PDF_LOGGER = java.util.logging.Logger.getLogger("org.apache.pdfbox");
    static {
        // Nel classpath MedBook commons-logging usa JUL. Evitare diagnostica del parser con dati del PDF.
        PDF_LOGGER.setLevel(java.util.logging.Level.OFF);
    }
    private final QpdfValidator qpdf;
    private final Path scratch;
    private final Semaphore permits = new Semaphore(2, true);
    private static final Set<String> FORBIDDEN_KEYS = Set.of("JS", "JavaScript", "OpenAction", "AA",
            "EmbeddedFiles", "EF", "AF", "RichMedia", "RichMediaContent", "RichMediaSettings", "XFA",
            "3D", "3DD", "Movie", "Sound");
    private static final Set<String> FORBIDDEN_ACTIONS = Set.of("JavaScript", "Launch", "URI", "GoToR", "GoToE",
            "SubmitForm", "ImportData", "Rendition", "Movie", "Sound", "RichMediaExecute", "Hide",
            "ResetForm", "SetOCGState", "Thread", "Trans");
    public PdfValidator(QpdfValidator qpdf, Path scratch) { this.qpdf = qpdf; this.scratch = scratch; }
    public void validate(Path original) throws IOException {
        try {
            if (!permits.tryAcquire(30, TimeUnit.SECONDS)) { throw new IOException("Validatore occupato."); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Validazione interrotta.", e); }
        try {
            qpdf.validate(original);
            // Non affidare la sicurezza alla sola modalità non-lenient: qpdf precede questo parsing.
            // Apertura fuori dal catch sintattico: file mancante/illeggibile è un errore tecnico.
            try (RandomAccessReadBufferedFile input = new TechnicalRead(original);
                 PrivateStreamCache cache = new PrivateStreamCache(scratch)) {
                validateDocument(input, cache);
            }
        } finally { permits.release(); }
    }
    private void validateDocument(RandomAccessReadBufferedFile input, PrivateStreamCache cache) throws IOException {
        try (PDDocument document = new PDFParser(input, "", null, null, () -> cache).parse(false)) {
                if (document.isEncrypted()) { throw new PdfRifiutatoException(); }
                var cos = document.getDocument();
                if (cos.getXrefTable().size() > 50_000) { throw new PdfRifiutatoException(); }
                List<COSBase> roots = new ArrayList<>(); roots.add(cos.getTrailer());
                // Ispezionare anche gli oggetti indiretti fuori dal grafo del catalogo.
                for (var key : cos.getXrefTable().keySet()) { roots.add(cos.getObjectFromPool(key)); }
                inspectAll(roots);
                COSBase pages = document.getDocumentCatalog().getCOSObject().getItem(COSName.PAGES);
                int count = pages(pages, null, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (count < 1 || count > 200 || count != document.getNumberOfPages()) { throw new PdfRifiutatoException(); }
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) { throw new PdfRifiutatoException(); }
              catch (PdfRifiutatoException e) { throw e; }
              catch (IOException e) {
                  for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                      if (cause instanceof PdfTechnicalException technical) { throw technical; }
                  }
                  throw new PdfRifiutatoException();
              }
    }
    static void inspect(COSBase root) throws IOException { inspectAll(List.of(root)); }
    /*
     * Profondità dei soli contenitori diretti Dictionary/Array/Stream, radice uno. COSObject
     * interrompe la catena ma non l'ispezione. Inventario per identità una volta per oggetto; Kahn
     * considera ogni predecessore. Tempo O(V+E), memoria O(V), limiti anche sugli archi duplicati.
     */
    private static void inspectAll(List<COSBase> roots) throws IOException {
        IdentityHashMap<COSBase, ContainerState> objects = new IdentityHashMap<>();
        ArrayDeque<COSBase> pending = new ArrayDeque<>();
        for (COSBase root : roots) { discover(root, objects, pending); }
        long links = 0;
        while (!pending.isEmpty()) {
            COSBase object = pending.remove();
            if (object instanceof COSDictionary dictionary) {
                String type = dictionary.getNameAsString(COSName.TYPE);
                String subtype = dictionary.getNameAsString(COSName.SUBTYPE);
                if ("EmbeddedFile".equals(type) || "Filespec".equals(type)
                        || Set.of("FileAttachment", "RichMedia", "3D").contains(subtype == null ? "" : subtype)) {
                    throw new PdfRifiutatoException();
                }
                if (FORBIDDEN_ACTIONS.contains(dictionary.getNameAsString(COSName.S, ""))) { throw new PdfRifiutatoException(); }
                for (COSName key : dictionary.keySet()) {
                    if (FORBIDDEN_KEYS.contains(key.getName())) { throw new PdfRifiutatoException(); }
                }
            }
            Iterator<COSBase> children = children(object);
            while (children.hasNext()) {
                if (links == 1_000_000) { throw new PdfRifiutatoException(); }
                links++;
                COSBase child = children.next();
                discover(child, objects, pending);
                if (container(object) && container(child)) { objects.get(child).incoming++; }
            }
        }
        ArrayDeque<COSBase> ready = new ArrayDeque<>();
        int containers = 0, processed = 0;
        for (var entry : objects.entrySet()) {
            if (container(entry.getKey())) {
                containers++;
                if (entry.getValue().incoming == 0) { ready.add(entry.getKey()); }
            }
        }
        /* Ogni predecessore diretto contribuisce: condivisioni non accorciano i percorsi lunghi. */
        while (!ready.isEmpty()) {
            COSBase object = ready.remove(); processed++;
            ContainerState parent = objects.get(object);
            if (parent.depth > 64) { throw new PdfRifiutatoException(); }
            Iterator<COSBase> children = children(object);
            while (children.hasNext()) {
                COSBase child = children.next();
                if (container(child)) {
                    ContainerState state = objects.get(child);
                    state.depth = Math.max(state.depth, parent.depth + 1);
                    if (--state.incoming == 0) { ready.add(child); }
                }
            }
        }
        if (processed != containers) { throw new PdfRifiutatoException(); }
    }
    private static boolean container(COSBase object) { return object instanceof COSDictionary || object instanceof COSArray; }
    private static void discover(COSBase object, IdentityHashMap<COSBase, ContainerState> objects,
            ArrayDeque<COSBase> pending) throws IOException {
        if (object == null) { throw new PdfTechnicalException(new IOException("Riferimento COS non risolto.")); }
        if (!objects.containsKey(object)) {
            if (objects.size() == 50_000) { throw new PdfRifiutatoException(); }
            objects.put(object, new ContainerState()); pending.add(object);
        }
    }
    private static Iterator<COSBase> children(COSBase object) throws IOException {
        if (object instanceof COSObject indirect) {
            COSBase target = indirect.getObject();
            // PDFBox può assorbire IOException. Cause sconosciute sono errori tecnici; COSNull resta lecito.
            if (target == null) { throw new PdfTechnicalException(new IOException("Riferimento COS non risolto.")); }
            return List.of(target).iterator();
        }
        if (object instanceof COSDictionary dictionary) { return dictionary.getValues().iterator(); }
        if (object instanceof COSArray array) { return array.iterator(); }
        return Collections.emptyIterator();
    }
    private static final class ContainerState { long incoming; int depth = 1; }

    /*
     * Visita postordine iterativa: cicli nell’albero pagine vietati anche se quelli indiretti
     * generali sono ammessi.
     */
    private static int pages(COSBase value, COSDictionary parent, Set<COSBase> seen, int ignoredDepth) throws IOException {
        record PageFrame(COSBase value, COSDictionary parent, boolean finish) { }
        Deque<PageFrame> work = new ArrayDeque<>();
        IdentityHashMap<COSDictionary, Integer> counts = new IdentityHashMap<>();
        work.push(new PageFrame(value, parent, false));
        while (!work.isEmpty()) {
            PageFrame frame = work.pop();
            COSBase resolved = resolve(frame.value());
            if (!(resolved instanceof COSDictionary dictionary)) { throw new PdfRifiutatoException(); }
            if (frame.finish()) {
                COSArray kids = (COSArray) resolve(dictionary.getItem(COSName.KIDS));
                int count = 0;
                for (int i = 0; i < kids.size(); i++) {
                    Integer childCount = counts.get(resolve(kids.get(i)));
                    if (childCount == null || childCount > 200 - count) { throw new PdfRifiutatoException(); }
                    count += childCount;
                }
                if (count < 1 || count != dictionary.getInt(COSName.COUNT, -1)) { throw new PdfRifiutatoException(); }
                counts.put(dictionary, count); continue;
            }
            if (!seen.add(dictionary) || seen.size() > 50_000) { throw new PdfRifiutatoException(); }
            if (frame.parent() != null && resolve(dictionary.getItem(COSName.PARENT)) != frame.parent()) { throw new PdfRifiutatoException(); }
            if (frame.parent() == null && dictionary.containsKey(COSName.PARENT)) { throw new PdfRifiutatoException(); }
            String type = dictionary.getNameAsString(COSName.TYPE);
            if ("Page".equals(type)) { counts.put(dictionary, 1); continue; }
            if (!"Pages".equals(type) || !(resolve(dictionary.getItem(COSName.KIDS)) instanceof COSArray kids)) { throw new PdfRifiutatoException(); }
            if (kids.size() > 50_000 - work.size()) { throw new PdfRifiutatoException(); }
            work.push(new PageFrame(dictionary, frame.parent(), true));
            for (int i = kids.size() - 1; i >= 0; i--) { work.push(new PageFrame(kids.get(i), dictionary, false)); }
        }
        return counts.get((COSDictionary) resolve(value));
    }
    private static COSBase resolve(COSBase value) throws IOException {
        Set<COSBase> wrappers = Collections.newSetFromMap(new IdentityHashMap<>());
        while (value instanceof COSObject indirect) {
            if (!wrappers.add(value)) { throw new PdfRifiutatoException(); }
            value = indirect.getObject();
            if (value == null) { throw new PdfTechnicalException(new IOException("Riferimento COS non risolto.")); }
        }
        return value;
    }
    private static final class TechnicalRead extends RandomAccessReadBufferedFile {
        TechnicalRead(Path path) throws IOException { super(path); }
        @Override public int read() throws IOException { try { return super.read(); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public int read(byte[] b, int off, int len) throws IOException { try { return super.read(b, off, len); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public void seek(long position) throws IOException { try { super.seek(position); } catch (IOException e) { throw new PdfTechnicalException(e); } }
    }
}
