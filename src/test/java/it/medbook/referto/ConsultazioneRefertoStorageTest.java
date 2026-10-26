package it.medbook.referto;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsultazioneRefertoStorageTest {
    @TempDir Path temp;
    RefertoStorage storage;RefertoStorage.Candidate candidate;
    final byte[] bytes={0,2,(byte)255,4};
    @BeforeEach void setup()throws Exception {temp=temp.toRealPath();storage=new RefertoStorage(temp.resolve("private"));candidate=storage.publish(storage.receive(new ByteArrayInputStream(bytes),null));}
    @Test void sizeOriginalBytesAndClosedHandle()throws Exception {RefertoStorage.Download d=storage.openDocument(candidate.key());assertEquals(bytes.length,d.size());assertArrayEquals(bytes,d.input().readAllBytes());d.close();assertThrows(IOException.class,()->d.input().read());assertTrue(Files.exists(candidate.path()));}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"../secret","/etc/passwd","documents/../document.pdf","documents/not-uuid/document.pdf","documents/00000000-0000-0000-0000-000000000000/document.pdf/extra","documents\\00000000-0000-0000-0000-000000000000\\document.pdf"})void malformedKeys(String key){assertThrows(IOException.class,()->storage.openDocument(key));}
    @Test void missingDirectoryAndSymlinkFileRejected()throws Exception {Files.delete(candidate.path());assertThrows(IOException.class,()->storage.openDocument(candidate.key()));Files.createDirectory(candidate.path());assertThrows(IOException.class,()->storage.openDocument(candidate.key()));Files.delete(candidate.path());Path outside=Files.write(temp.resolve("outside"),bytes);Files.createSymbolicLink(candidate.path(),outside);assertThrows(IOException.class,()->storage.openDocument(candidate.key()));assertArrayEquals(bytes,Files.readAllBytes(outside));}
    @Test void symlinkFolderRejected()throws Exception {Files.delete(candidate.path());Files.delete(candidate.path().getParent());Files.createSymbolicLink(candidate.path().getParent(),temp);assertThrows(IOException.class,()->storage.openDocument(candidate.key()));}
    @Test void insecurePermissionsRejected()throws Exception {Files.setPosixFilePermissions(candidate.path(),PosixFilePermissions.fromString("rw-r--r--"));assertThrows(IOException.class,()->storage.openDocument(candidate.key()));}
    @Test void readingDoesNotRecreateMissingDirectory()throws Exception {Files.delete(candidate.path());Files.delete(candidate.path().getParent());assertThrows(IOException.class,()->storage.openDocument(candidate.key()));assertFalse(Files.exists(candidate.path().getParent()));}
    @Test void parallelReadsLeaveFileUnchanged()throws Exception {try(var executor=Executors.newFixedThreadPool(4)){var tasks=new java.util.ArrayList<Future<byte[]>>();for(int i=0;i<8;i++)tasks.add(executor.submit(()->{try(var d=storage.openDocument(candidate.key())){return d.input().readAllBytes();}}));for(var task:tasks)assertArrayEquals(bytes,task.get(3,TimeUnit.SECONDS));}assertArrayEquals(bytes,Files.readAllBytes(candidate.path()));}
    @Test void providerCapabilityIsObservable()throws Exception {try(var stream=Files.newDirectoryStream(temp)){System.out.println("UC09 SecureDirectoryStream supported: "+(stream instanceof SecureDirectoryStream<?>));}}
    @Test void controlledOpenFailureDoesNotModifyFile()throws Exception {
        var broken=new RefertoStorage(temp.resolve("private")){
            @Override protected java.nio.channels.SeekableByteChannel openReadChannel(Path file)throws IOException{throw new IOException("synthetic unreadable file");}
        };
        try(var directories=Files.newDirectoryStream(temp)) {
            if(directories instanceof SecureDirectoryStream<?>) { return; } /* Il guasto riguarda soltanto il percorso alternativo. */
        }
        assertThrows(IOException.class,()->broken.openDocument(candidate.key()));assertArrayEquals(bytes,Files.readAllBytes(candidate.path()));
    }
    @Test void controlledFolderReplacementIsRejectedAndHandleClosed()throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.nio.channels.SeekableByteChannel> opened=new java.util.concurrent.atomic.AtomicReference<>();
        var racing=new RefertoStorage(temp.resolve("private")){
            @Override protected java.nio.channels.SeekableByteChannel openReadChannel(Path file)throws IOException {
                var channel=super.openReadChannel(file);opened.set(channel);
                Path directory=file.getParent();Files.move(directory,directory.resolveSibling("old"));
                Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.write(directory.resolve("document.pdf"),new byte[]{9},StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(directory.resolve("document.pdf"),PosixFilePermissions.fromString("rw-------"));
                return channel;
            }
        };
        try(var directories=Files.newDirectoryStream(temp)){if(directories instanceof SecureDirectoryStream<?>)return;}
        assertThrows(IOException.class,()->racing.openDocument(candidate.key()));assertNotNull(opened.get());assertFalse(opened.get().isOpen());
    }

}
