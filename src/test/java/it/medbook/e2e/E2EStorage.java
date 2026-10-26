package it.medbook.e2e;

import it.medbook.referto.RefertoStorage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/*
 * Storage reale in directory temporanea esterna canonica. Su macOS /var è un link: toRealPath evita
 * il rifiuto. La pulizia rimuove solo la directory creata, senza seguire link, anche dopo
 * costruzione parziale; close è idempotente.
 */
public final class E2EStorage implements AutoCloseable {
    static final String PREFISSO = "medbook-e2e-referti-";
    private final Path base;
    private final Path radice;
    private final RefertoStorage storage;

    public E2EStorage() throws IOException { this(E2EGuasti.NESSUNO); }

    E2EStorage(E2EGuasti guasti) throws IOException {
        Path grezza = Files.createTempDirectory(PREFISSO);
        Path creata;
        try {
            creata = grezza.toRealPath();
        } catch (IOException | RuntimeException e) {
            /* Percorso non ancora canonico: rimuovere solo la directory vuota appena creata, senza seguire link. */
            try { Files.deleteIfExists(grezza); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        try {
            guasti.fase(E2EGuasti.Fase.STORAGE_CREATO, creata.toString());
            Path repository = Path.of("").toAbsolutePath().toRealPath();
            if (creata.startsWith(repository)) { throw new IOException("Directory temporanea dentro il repository."); }
            this.base = creata;
            this.radice = creata.resolve("archivio");
            this.storage = new RefertoStorage(radice);
        } catch (IOException | RuntimeException e) {
            try { rimuovi(creata); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public Path base() { return base; }
    public Path radice() { return radice; }
    public RefertoStorage storage() { return storage; }

    @Override public void close() throws IOException { rimuovi(base); }

    /* Rimuove la directory soltanto se temporanea e creata da questa classe. */
    static void rimuovi(Path dir) throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (dir.getFileName() == null || !dir.getFileName().toString().startsWith(PREFISSO) || !tmp.equals(dir.getParent())) {
            throw new IOException("Cleanup rifiutato: directory non creata dai test E2E.");
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) { return; }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) { throw e; }
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }
}
