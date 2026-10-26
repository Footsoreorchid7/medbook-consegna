package it.medbook.referto;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class QpdfValidatorTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp() throws IOException { temp = temp.toRealPath(); }
    Path executable(String commands) throws IOException {
        Path script=Files.createTempFile(temp,"qpdf-",".sh");
        Files.writeString(script,"#!/bin/sh\n"+commands+"\n");
        Files.setPosixFilePermissions(script,PosixFilePermissions.fromString("rwx------")); return script;
    }
    QpdfValidator validator(String commands) throws IOException { return new QpdfValidator(executable(commands),Duration.ofSeconds(3)); }
    @Test void successAndOnlySeparateArguments() throws Exception {
        Path pdf=Files.write(temp.resolve("spaces ; $(no-shell).pdf"),new byte[]{1});
        validator("test \"$#\" = 2 && test \"$1\" = --check && test -f \"$2\" || exit 5\nexit 0").validate(pdf);
    }
    @ParameterizedTest @ValueSource(ints={2,3}) void documentErrorAndWarningRejected(int code) throws Exception {
        assertThrows(PdfRifiutatoException.class, () -> validator("exit "+code).validate(temp.resolve("original.pdf")));
    }
    @Test void unexpectedExitIsTechnical() throws Exception {
        var e=assertThrows(IOException.class, () -> validator("exit 7").validate(temp.resolve("original.pdf")));
        assertFalse(e instanceof PdfRifiutatoException);
    }
    @Test void unavailableNoFallback() throws Exception {
        Path file=executable("exit 0"); var validator=new QpdfValidator(file,Duration.ofSeconds(1)); Files.delete(file);
        assertThrows(IOException.class, () -> validator.validate(temp.resolve("original.pdf")));
        assertThrows(IOException.class, () -> new QpdfValidator(Path.of("qpdf"),Duration.ofSeconds(1)));
    }

    @Test void timeoutTerminatesProcess() throws Exception {
        Path file=executable("exec /bin/sleep 30");
        var started=new java.util.concurrent.atomic.AtomicReference<Process>();
        var validator=new QpdfValidator(file,Duration.ofMillis(500)) {
            @Override protected Process startProcess(java.util.List<String> args)throws IOException {
                Process process=super.startProcess(args);started.set(process);return process;
            }
        };
        IOException error=assertThrows(IOException.class, () -> validator.validate(temp.resolve("original.pdf")));
        assertTrue(error.getMessage().contains("Timeout"));assertNotNull(started.get());
        assertFalse(started.get().isAlive());assertFalse(ProcessHandle.of(started.get().pid()).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void bothOutputsDrainedWithoutBufferingOrExposure() throws Exception {
        validator("i=0; while [ $i -lt 10000 ]; do printf 'untrusted-data-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; printf 'untrusted-data-yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy' >&2; i=$((i+1)); done\nexit 0").validate(temp.resolve("original.pdf"));
    }
    @Test void exactVersionRequired() throws Exception {
        validator("printf 'qpdf version 12.4.2\\n'\nexit 0").verifyVersion();
        assertThrows(IOException.class, () -> validator("printf 'qpdf version 12.4.1\\n'\nexit 0").verifyVersion());
    }
    @Test void interruptionPreservedAndProcessTerminated() throws Exception {
        var started=new java.util.concurrent.atomic.AtomicReference<Process>();
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var ready=new java.util.concurrent.CountDownLatch(1);
        var validator=new QpdfValidator(executable("exec /bin/sleep 30"),Duration.ofSeconds(1)) {
            @Override protected Process startProcess(java.util.List<String> args)throws IOException {Process p=super.startProcess(args);started.set(p);ready.countDown();return p;}
        };
        Thread thread=new Thread(()->{try{validator.validate(temp.resolve("original.pdf"));}catch(Throwable e){error.set(e);interrupted.set(Thread.currentThread().isInterrupted());}});
        thread.start();assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));thread.interrupt();thread.join(4000);
        try { assertFalse(thread.isAlive());assertInstanceOf(IOException.class,error.get());assertTrue(interrupted.get());assertFalse(started.get().isAlive()); }
        finally { if(started.get()!=null)started.get().destroyForcibly(); }
    }
    @Test void inheritedPipeHasBoundedDeadlineAndKnownChildTermination()throws Exception {
        Path pid=temp.resolve("child.pid");
        /* La preparazione termina subito: assorbe il costo della prima esecuzione del file. */
        /* Così il limite misurato non scade prima della scrittura del PID figlio. */
        Path script=executable("[ \"$1\" = --prepare ] && exit 0\n/bin/sleep 30 &\necho $! > '"+pid+"'\n/bin/sleep 0.2\nexit 0");
        Process prepare=new ProcessBuilder(script.toString(),"--prepare").start();
        assertTrue(prepare.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,prepare.exitValue());
        var validator=new QpdfValidator(script,Duration.ofSeconds(2));long start=System.nanoTime();
        try {
            IOException error=assertThrows(IOException.class,()->validator.validate(temp.resolve("original.pdf")));
            /* La shell è terminata; il figlio mantiene la pipe ereditata aperta quando scade il limite. */
            assertInstanceOf(java.util.concurrent.TimeoutException.class,error.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(4))<0);
            assertTrue(Files.exists(pid),"PID del figlio scritto prima della deadline");
            long child=Long.parseLong(Files.readString(pid).trim());
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if(Files.exists(pid))ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
    @Test void failedStreamCleanupDoesNotSkipOtherResources()throws Exception {
        var stdinClosed=new java.util.concurrent.atomic.AtomicBoolean();
        var stdoutClosed=new java.util.concurrent.atomic.AtomicBoolean();
        var stderrClosed=new java.util.concurrent.atomic.AtomicBoolean();
        Process fake=new Process(){
            final InputStream input=new ByteArrayInputStream(new byte[0]){@Override public void close()throws IOException{stdoutClosed.set(true);throw new IOException("synthetic close");}};
            final InputStream error=new ByteArrayInputStream(new byte[0]){@Override public void close(){stderrClosed.set(true);}};
            final OutputStream output=new ByteArrayOutputStream(){@Override public void close(){stdinClosed.set(true);}};
            public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return error;}public OutputStream getOutputStream(){return output;}
            public int waitFor(){return 0;}public boolean waitFor(long n,java.util.concurrent.TimeUnit u){return true;}
            public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){}
            public java.util.stream.Stream<ProcessHandle> descendants(){return java.util.stream.Stream.empty();}
        };
        var validator=new QpdfValidator(executable("exit 0"),Duration.ofMillis(500)){@Override protected Process startProcess(java.util.List<String> args){return fake;}};
        IOException error=assertThrows(IOException.class,()->validator.validate(temp.resolve("original.pdf")));
        assertInstanceOf(java.util.concurrent.ExecutionException.class,error.getCause());assertTrue(error.getSuppressed().length>0);assertTrue(stdinClosed.get());assertTrue(stdoutClosed.get());assertTrue(stderrClosed.get());
    }
    @Test void uninterruptibleReaderCannotBlockCallerIndefinitely()throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        InputStream blocked=new InputStream(){public int read(){boolean interrupted=false;while(true){try{release.await();break;}catch(InterruptedException e){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();return -1;}public void close(){}};
        Process fake=new Process(){
            public InputStream getInputStream(){return blocked;}public InputStream getErrorStream(){return InputStream.nullInputStream();}public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public int waitFor(){return 0;}public boolean waitFor(long n,java.util.concurrent.TimeUnit u){return true;}public int exitValue(){return 0;}public boolean isAlive(){return false;}public void destroy(){}public java.util.stream.Stream<ProcessHandle> descendants(){return java.util.stream.Stream.empty();}
        };
        var validator=new QpdfValidator(executable("exit 0"),Duration.ofMillis(100)){@Override protected Process startProcess(java.util.List<String> args){return fake;}};
        long start=System.nanoTime();try{assertThrows(IOException.class,()->validator.validate(temp.resolve("original.pdf")));assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(4))<0);}finally{release.countDown();}
    }

    @Test void runtimeProcessFailureRetainsCauseAndSuppressedCleanupFailure()throws Exception {
        Process fake=new Process(){
            public InputStream getInputStream(){return InputStream.nullInputStream();}public InputStream getErrorStream(){return InputStream.nullInputStream();}public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public int waitFor(){return 0;}public int exitValue(){return 0;}public boolean isAlive(){throw new IllegalStateException("synthetic process failure");}public void destroy(){}public java.util.stream.Stream<ProcessHandle> descendants(){return java.util.stream.Stream.empty();}
        };
        var validator=new QpdfValidator(executable("exit 0"),Duration.ofMillis(100)){@Override protected Process startProcess(java.util.List<String> args){return fake;}};
        IOException error=assertThrows(IOException.class,()->validator.validate(temp.resolve("original.pdf")));
        assertInstanceOf(IllegalStateException.class,error.getCause());assertEquals("synthetic process failure",error.getCause().getMessage());assertTrue(error.getSuppressed().length>0);
    }

}
