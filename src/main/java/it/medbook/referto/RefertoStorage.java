package it.medbook.referto;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Archivio privato: richiede filesystem POSIX e rename atomico sul medesimo filesystem. */
public class RefertoStorage {
    public static final long MAX_BYTES = 10_485_760;
    private final Path root;
    private final Path staging;
    private final Path documents;
    private final Path scratch;
    private final java.util.function.Supplier<String> keys;

    public RefertoStorage(Path directory) throws IOException {
        this(directory, () -> UUID.randomUUID().toString());
    }
    RefertoStorage(Path directory, java.util.function.Supplier<String> keys) throws IOException {
        this.keys = keys;
        if (!directory.isAbsolute()) { throw new IOException("Directory referti assoluta richiesta."); }
        root = directory.normalize();
        if (root.startsWith(Path.of("").toAbsolutePath().normalize())) {
            throw new IOException("Archivio referti esterno al repository richiesto.");
        }
        // Non attraversare link, anche nei componenti antenati. Non cambiare permessi di directory altrui.
        Path repository = Path.of("").toAbsolutePath();
        for (Path part = root; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) { throw new IOException("Link non consentiti nell'archivio."); }
            // Il confronto lessicale non basta su filesystem con alias Unicode o altri alias fisici.
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(part, repository)) {
                throw new IOException("Archivio referti esterno al repository richiesto.");
            }
        }
        privateDirectory(root);
        staging = privateDirectory(root.resolve("staging"));
        documents = privateDirectory(root.resolve("documents"));
        scratch = privateDirectory(root.resolve("scratch"));
        if (!Files.getFileStore(staging).equals(Files.getFileStore(documents))) {
            throw new IOException("Staging e documenti devono condividere il filesystem.");
        }
    }
    private static Path privateDirectory(Path path) throws IOException {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                            .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IOException("Directory privata incompatibile.");
            }
        } catch (UnsupportedOperationException e) { throw new IOException("Permessi POSIX richiesti.", e); }
        return path;
    }
    public Path scratch() throws IOException { checkDirectories(); return scratch; }
    private void checkDirectories() throws IOException {
        privateDirectory(root); privateDirectory(staging); privateDirectory(documents); privateDirectory(scratch);
    }
    public Path receive(InputStream input, Long expectedLength) throws IOException {
        checkDirectories();
        if (expectedLength != null && (expectedLength <= 0 || expectedLength > MAX_BYTES)) {
            throw new UploadNonValidoException(expectedLength > MAX_BYTES ? 413 : 400);
        }
        Path file = Files.createTempFile(staging, "upload-", ".pdf",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            long count = 0;
            try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > MAX_BYTES) { throw new UploadNonValidoException(413); }
                    output.write(buffer, 0, read);
                }
            }
            if (count == 0 || (expectedLength != null && count != expectedLength)) {
                throw new UploadNonValidoException(400);
            }
            return file;
        } catch (IOException | RuntimeException | Error e) {
            try { Files.delete(file); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    /** Risorsa aperta: dimensione e byte appartengono allo stesso handle. */
    public static class Download implements AutoCloseable {
        private final java.nio.channels.SeekableByteChannel channel;
        private final long size;
        public Download(java.nio.channels.SeekableByteChannel channel) throws IOException {
            this.channel = channel;
            try { size = channel.size(); }
            catch (IOException | RuntimeException e) { try { channel.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
        }
        public long size() { return size; }
        public InputStream input() { return java.nio.channels.Channels.newInputStream(channel); }
        @Override public void close() throws IOException { channel.close(); }
    }
    public Download openDocument(String key) throws IOException {
        if (key == null || !key.matches("documents/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/document\\.pdf")) {
            throw new IOException("Chiave documento non valida.");
        }
        // Solo verifica: una consultazione non crea o ripristina directory.
        verifyPrivate(root); verifyPrivate(documents);
        String folder = key.split("/")[1];
        Download anchored = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(documents)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                Download opened = null;
                try (SecureDirectoryStream<Path> child = secure.newDirectoryStream(Path.of(folder), LinkOption.NOFOLLOW_LINKS)) {
                    var directoryView = child.getFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class);
                    if (!directoryView.readAttributes().permissions().equals(PosixFilePermissions.fromString("rwx------"))) {
                        throw new IOException("Directory privata incompatibile.");
                    }
                    var attrs = child.getFileAttributeView(Path.of("document.pdf"),
                            java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                    verifyFile(attrs);
                    opened = new Download(child.newByteChannel(Path.of("document.pdf"),
                            java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
                } catch (IOException | RuntimeException e) {
                    if (opened != null) { try { opened.close(); } catch (IOException close) { e.addSuppressed(close); } }
                    throw e;
                }
                anchored = opened;
            }
        } catch (IOException | RuntimeException e) {
            if (anchored != null) { try { anchored.close(); } catch (IOException close) { e.addSuppressed(close); } }
            throw e;
        }
        if (anchored != null) { return anchored; }
        // Provider senza SecureDirectoryStream: root privata accessibile solo a processi attendibili.
        // NOFOLLOW protegge l'ultimo componente; confronti fileKey rilevano sostituzioni osservabili,
        // ma non dimostrano l'assenza di ogni race ABA sugli antenati.
        Path directory = documents.resolve(folder); verifyPrivate(directory);
        Path file = directory.resolve("document.pdf");
        var before = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        verifyFile(before);
        if (before.fileKey() == null) { throw new IOException("Identità file non verificabile."); }
        var directoryBefore = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directoryBefore.fileKey() == null) { throw new IOException("Identità directory non verificabile."); }
        var opened = openReadChannel(file);
        try {
            verifyPrivate(root); verifyPrivate(documents); verifyPrivate(directory);
            var after = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            verifyFile(after);
            var directoryAfter = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.fileKey().equals(after.fileKey()) || !directoryBefore.fileKey().equals(directoryAfter.fileKey())) {
                throw new IOException("Documento modificato durante apertura.");
            }
            return new Download(opened);
        } catch (IOException | RuntimeException e) { try { opened.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
    }
    protected java.nio.channels.SeekableByteChannel openReadChannel(Path file) throws IOException {
        return Files.newByteChannel(file, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }
    private static void verifyPrivate(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) { throw new IOException("Link non consentiti."); }
        }
        var attrs = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || !attrs.permissions().equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("Directory privata incompatibile.");
        }
    }
    private static void verifyFile(java.nio.file.attribute.PosixFileAttributes attrs) throws IOException {
        if (!attrs.isRegularFile() || !attrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("Documento privato incompatibile.");
        }
    }
    public Candidate publish(Path file) throws IOException {
        checkDirectories();
        if (!file.getParent().equals(staging) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Candidato non valido.");
        }
        // ATOMIC_MOVE può sovrascrivere: prenotare una directory esclusiva impedisce una destinazione concorrente.
        String key = keys.get();
        if (!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) { throw new IOException("Chiave non valida."); }
        Path folder = documents.resolve(key);
        Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path target = folder.resolve("document.pdf");
        try {
            atomicMove(file, target);
            return new Candidate(target, "documents/" + key + "/document.pdf", "referto-" + key + ".pdf");
        } catch (IOException | RuntimeException e) {
            try { Files.delete(folder); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    protected void atomicMove(Path file, Path target) throws IOException {
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }
    public void discardStaging(Path file) throws IOException {
        if (!file.getParent().equals(staging)) { throw new IOException("Candidato non valido."); }
        Files.deleteIfExists(file);
    }
    /** Solo il candidato della richiesta, quando è certo che il DB non lo referenzia. */
    public void discard(Candidate candidate) throws IOException {
        checkDirectories();
        Path path = candidate.path();
        if (!path.getParent().getParent().equals(documents) || Files.isSymbolicLink(path.getParent())) {
            throw new IOException("Candidato non valido.");
        }
        Files.delete(path); Files.delete(path.getParent());
    }
    public record Candidate(Path path, String key, String name) { }
}
