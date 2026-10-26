package it.medbook.referto;

import org.apache.pdfbox.io.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** Quota totale della cache su disco, non dell'heap o della CPU del parser. */
final class PrivateStreamCache implements RandomAccessStreamCache {
    static final long LIMIT = 128L * 1024 * 1024;
    interface FileOperations {
        RandomAccessFile open(Path path) throws IOException;
        void delete(Path path) throws IOException;
    }
    private static final FileOperations FILES = new FileOperations() {
        public RandomAccessFile open(Path path) throws IOException { return new RandomAccessFile(path.toFile(), "rw"); }
        public void delete(Path path) throws IOException { Files.deleteIfExists(path); }
    };
    private final FileOperations files;
    private final Path directory;
    private final List<Buffer> buffers = new ArrayList<>();
    private long allocated;
    private boolean closed;
    PrivateStreamCache(Path directory) { this(directory, FILES); }
    /* Gancio di package per iniettare guasti file nei test senza cambiare l'API pubblica. */
    PrivateStreamCache(Path directory, FileOperations files) { this.directory = directory; this.files = files; }
    @Override public synchronized RandomAccess createBuffer() throws IOException {
        if (closed) { throw new PdfTechnicalException(new IOException("Cache chiusa.")); }
        Path file;
        try {
            file = Files.createTempFile(directory, "cache-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (IOException e) { throw new PdfTechnicalException(e); }
        try { Buffer buffer = new Buffer(file); buffers.add(buffer); return buffer; }
        catch (IOException e) { try { files.delete(file); } catch (IOException cleanup) { e.addSuppressed(cleanup); } throw new PdfTechnicalException(e); }
    }
    private synchronized void reserve(long increase) throws IOException {
        if (closed && increase > 0) { throw new PdfTechnicalException(new IOException("Cache chiusa.")); }
        if (increase > LIMIT - allocated) { throw new PdfRifiutatoException(); }
        allocated += increase;
    }
    @Override public void close() throws IOException {
        List<Buffer> snapshot;
        synchronized (this) { closed = true; snapshot = List.copyOf(buffers); }
        IOException failure = null;
        for (Buffer buffer : snapshot) {
            try { buffer.close(); } catch (IOException e) {
                if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
            }
        }
        if (failure != null) { throw new PdfTechnicalException(failure); }
    }
    private final class Buffer implements RandomAccess {
        private final Path path;
        private final RandomAccessFile file;
        private long size;
        private boolean done;
        Buffer(Path path) throws IOException { this.path = path; file = files.open(path); }
        @Override public synchronized int read() throws IOException { try { return file.read(); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized int read(byte[] b, int off, int len) throws IOException { try { return file.read(b, off, len); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized long getPosition() throws IOException { try { return file.getFilePointer(); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized void seek(long pos) throws IOException { try { file.seek(pos); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized long length() throws IOException { try { return file.length(); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized boolean isClosed() { return done; }
        @Override public synchronized boolean isEOF() throws IOException { return getPosition() >= length(); }
        @Override public RandomAccessReadView createView(long start, long len) { return new RandomAccessReadView(this, start, len); }
        private void grow(int count) throws IOException {
            long next = Math.max(size, Math.addExact(getPosition(), count));
            reserve(next - size); size = next;
        }
        @Override public synchronized void write(int value) throws IOException { grow(1); try { file.write(value); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized void write(byte[] b) throws IOException { write(b, 0, b.length); }
        @Override public synchronized void write(byte[] b, int off, int len) throws IOException { grow(len); try { file.write(b, off, len); } catch (IOException e) { throw new PdfTechnicalException(e); } }
        @Override public synchronized void clear() throws IOException { try { file.setLength(0); file.seek(0); } catch (IOException e) { throw new PdfTechnicalException(e); } reserve(-size); size = 0; }
        @Override public synchronized void close() throws IOException {
            if (done) { return; }
            done = true;
            IOException failure = null;
            try { file.close(); } catch (IOException e) { failure = e; }
            try { reserve(-size); } catch (IOException e) {
                if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
            }
            size = 0;
            try { files.delete(path); } catch (IOException e) {
                if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
            }
            if (failure != null) { throw new PdfTechnicalException(failure); }
        }
    }
}
