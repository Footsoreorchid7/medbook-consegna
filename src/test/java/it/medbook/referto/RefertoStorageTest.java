package it.medbook.referto;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class RefertoStorageTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp() throws IOException { temp = temp.toRealPath(); }
    RefertoStorage storage() throws IOException { return new RefertoStorage(temp.resolve("private")); }
    @Test void privateStreamingPublicationAndSafeCleanup() throws Exception {
        var storage = storage(); byte[] data = {0, 1, (byte)255};
        var staged = storage.receive(new ByteArrayInputStream(data), 3L);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(staged));
        var published = storage.publish(staged);
        assertArrayEquals(data, Files.readAllBytes(published.path())); assertFalse(Files.exists(staged));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(published.path()));
        assertFalse(Path.of(published.key()).isAbsolute());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(published.path().getParent()));
        storage.discard(published); assertFalse(Files.exists(published.path()));
    }
    @Test void missingLengthAcceptedAndKeysDifferent() throws Exception {
        var storage = storage(); var a = storage.publish(storage.receive(new ByteArrayInputStream(new byte[]{1}), null));
        var b = storage.publish(storage.receive(new ByteArrayInputStream(new byte[]{2}), null));
        assertNotEquals(a.key(), b.key()); assertArrayEquals(new byte[]{1}, Files.readAllBytes(a.path()));
    }
    @Test void exactLimitAccepted() throws Exception {
        var storage = storage(); var path = storage.receive(new ByteArrayInputStream(new byte[(int)RefertoStorage.MAX_BYTES]), RefertoStorage.MAX_BYTES);
        assertEquals(RefertoStorage.MAX_BYTES, Files.size(path));
    }
    @Test void oversizeWithAndWithoutLengthRejectedAndCleaned() throws Exception {
        var storage = storage();
        assertEquals(413, assertThrows(UploadNonValidoException.class, () -> storage.receive(InputStream.nullInputStream(), RefertoStorage.MAX_BYTES+1)).getStatus());
        assertEquals(413, assertThrows(UploadNonValidoException.class, () -> storage.receive(new ByteArrayInputStream(new byte[(int)RefertoStorage.MAX_BYTES+1]), null)).getStatus());
        try (var paths = Files.list(temp.resolve("private/staging"))) { assertEquals(0, paths.count()); }
    }
    @Test void emptyAndFalseLengthRejected() throws Exception {
        var storage = storage();
        for (long expected : new long[]{0, -1, 2}) {
            assertThrows(UploadNonValidoException.class, () -> storage.receive(new ByteArrayInputStream(new byte[]{1}), expected));
        }
        assertThrows(UploadNonValidoException.class, () -> storage.receive(InputStream.nullInputStream(), null));
    }
    @Test void inputErrorRemovesOnlyOwnStagingFile() throws Exception {
        var storage = storage(); var existing = storage.receive(new ByteArrayInputStream(new byte[]{1}), null);
        assertThrows(IOException.class, () -> storage.receive(new InputStream(){ public int read() throws IOException { throw new IOException("synthetic"); }}, null));
        assertTrue(Files.exists(existing));
        try (var paths = Files.list(existing.getParent())) { assertEquals(1, paths.count()); }
    }
    @Test void rejectsSymlinkRootAndChildAndPermissiveDirectory() throws Exception {
        Files.createDirectory(temp.resolve("target")); Files.createSymbolicLink(temp.resolve("link"), temp.resolve("target"));
        assertThrows(IOException.class, () -> new RefertoStorage(temp.resolve("link")));
        var storage = storage(); Files.delete(temp.resolve("private/staging"));
        Files.createSymbolicLink(temp.resolve("private/staging"), temp.resolve("target"));
        assertThrows(IOException.class, () -> storage.receive(new ByteArrayInputStream(new byte[]{1}), null));
        Files.delete(temp.resolve("private/staging")); Files.setPosixFilePermissions(temp.resolve("private"), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> new RefertoStorage(temp.resolve("private")));
    }
    @Test void relativeOrRepositoryDirectoryRejected() {
        assertThrows(IOException.class, () -> new RefertoStorage(Path.of("relative")));
        assertThrows(IOException.class, () -> new RefertoStorage(Path.of("").toAbsolutePath().resolve("test-private")));
    }
    @Test void externalOrSymlinkCandidateRejected() throws Exception {
        var storage = storage(); Path external=Files.write(temp.resolve("outside"),new byte[]{1});
        assertThrows(IOException.class, () -> storage.publish(external));
        var staged=storage.receive(new ByteArrayInputStream(new byte[]{2}),null); Files.delete(staged); Files.createSymbolicLink(staged,external);
        assertThrows(IOException.class, () -> storage.publish(staged)); assertArrayEquals(new byte[]{1},Files.readAllBytes(external));
    }
    @Test void collisionNeverOverwritesExistingDocument() throws Exception {
        String key="12345678-1234-1234-1234-123456789abc";
        var storage=new RefertoStorage(temp.resolve("private"),()->key);
        var first=storage.publish(storage.receive(new ByteArrayInputStream(new byte[]{1}),null));
        var second=storage.receive(new ByteArrayInputStream(new byte[]{2}),null);
        assertThrows(FileAlreadyExistsException.class,()->storage.publish(second));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(first.path()));assertTrue(Files.exists(second));
    }
    @Test void noFallbackWhenAtomicMoveUnsupported() throws Exception {
        var storage=new RefertoStorage(temp.resolve("private")) {
            @Override protected void atomicMove(Path file,Path target)throws IOException {
                throw new AtomicMoveNotSupportedException(file.toString(),target.toString(),"synthetic");
            }
        };
        var staged=storage.receive(new ByteArrayInputStream(new byte[]{1}),null);
        assertThrows(AtomicMoveNotSupportedException.class,()->storage.publish(staged));assertTrue(Files.exists(staged));
        try(var paths=Files.list(temp.resolve("private/documents"))){assertEquals(0,paths.count());}
    }

    @Test void repositoryFilesystemAliasIsRejectedBeforeAnyDirectoryCreation() throws Exception {
        Path repository=Path.of("").toAbsolutePath();
        Path alias=Path.of(java.text.Normalizer.normalize(repository.toString(),java.text.Normalizer.Form.NFC));
        assertTrue(Files.isSameFile(repository,alias));
        IOException error=assertThrows(IOException.class,()->new RefertoStorage(alias));
        assertEquals("Archivio referti esterno al repository richiesto.",error.getMessage());
    }

}
