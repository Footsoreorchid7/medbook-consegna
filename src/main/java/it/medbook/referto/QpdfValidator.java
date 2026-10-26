package it.medbook.referto;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Invocazione senza shell; output drenato e scartato, mai conservato o registrato. */
public class QpdfValidator {
    private final Path executable;
    private final Duration timeout;
    public QpdfValidator(Path executable, Duration timeout) throws IOException {
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)
                || timeout.isNegative() || timeout.isZero()) {
            throw new IOException("Configurazione qpdf non valida.");
        }
        this.executable = executable;
        this.timeout = timeout;
    }
    public void verifyVersion() throws IOException {
        Result result = run(List.of(executable.toString(), "--version"), true);
        String version = result.output();
        if (result.exit() != 0 || !version.matches("(?s)qpdf version 12\\.4\\.2(?:\\s.*)?")) {
            throw new IOException("È richiesto qpdf 12.4.2.");
        }
    }
    public void validate(Path original) throws IOException {
        int exit = run(List.of(executable.toString(), "--check", original.toAbsolutePath().toString()), false).exit();
        if (exit == 2 || exit == 3) { throw new PdfRifiutatoException(); }
        if (exit != 0) { throw new IOException("Errore tecnico del validatore PDF."); }
    }
    protected Process startProcess(List<String> args) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        // Il subprocess non necessita di credenziali DB, cookie o configurazione dell'applicazione.
        builder.environment().clear(); builder.environment().put("LC_ALL", "C");
        try { return builder.start(); }
        catch (IOException e) { throw new IOException("Validatore PDF non disponibile.", e); }
    }
    private Result run(List<String> args, boolean version) throws IOException {
        long started = System.nanoTime();
        long executionEnd = started + timeout.toNanos();
        long cleanupEnd = executionEnd + TimeUnit.SECONDS.toNanos(2);
        Process process = startProcess(args);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Set<ProcessHandle> descendants = new HashSet<>();
        Future<String> out = workers.submit(() -> drain(process.getInputStream(), version));
        Future<String> err = workers.submit(() -> drain(process.getErrorStream(), false));
        IOException failure = null;
        Result result = null;
        boolean interrupted = false;
        try {
            while (process.isAlive()) {
                process.descendants().forEach(descendants::add);
                long remaining = executionEnd - System.nanoTime();
                if (remaining <= 0) { throw new IOException("Timeout del validatore PDF."); }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
            }
            String output = out.get(remaining(executionEnd), TimeUnit.NANOSECONDS);
            err.get(remaining(executionEnd), TimeUnit.NANOSECONDS);
            result = new Result(process.exitValue(), output);
        } catch (InterruptedException e) {
            interrupted = true; failure = new IOException("Validazione interrotta.", e);
        } catch (ExecutionException | TimeoutException e) {
            failure = new IOException("Errore tecnico del validatore PDF.", e);
        } catch (IOException e) { failure = e; }
          catch (RuntimeException e) { failure = new IOException("Errore tecnico del validatore PDF.", e); }
        finally {
            /*
             * Non chiamare ExecutorService.close: lettori pipe bloccati non devono trattenere il
             * chiamante indefinitamente.
             */
            interrupted |= Thread.interrupted();
            List<Future<?>> cleanup = new ArrayList<>();
            try { process.descendants().forEach(descendants::add); }
            catch (RuntimeException e) { failure = append(failure, new IOException("Discendenti non verificabili.", e)); }
            for (ProcessHandle child : descendants) {
                cleanup.add(workers.submit(() -> {
                    if (child.isAlive()) { child.destroyForcibly(); }
                    try { if (child.isAlive()) { child.onExit().get(remaining(cleanupEnd), TimeUnit.NANOSECONDS); } }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Terminazione interrotta.", e); }
                    catch (ExecutionException | TimeoutException e) { throw new IOException("Discendente non terminato.", e); }
                    return null;
                }));
            }
            cleanup.add(workers.submit(() -> {
                if (process.isAlive()) { process.destroyForcibly(); }
                try {
                    if (!process.waitFor(remaining(cleanupEnd), TimeUnit.NANOSECONDS)) { throw new IOException("Validatore non terminato."); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Terminazione interrotta.", e); }
                  catch (TimeoutException e) { throw new IOException("Terminazione non verificata.", e); }
                return null;
            }));
            /* Compiti indipendenti: close fallito o bloccato non deve impedire gli altri tentativi di pulizia. */
            for (Closeable stream : List.of(process.getInputStream(), process.getErrorStream(), process.getOutputStream())) {
                cleanup.add(workers.submit(() -> { stream.close(); return null; }));
            }
            out.cancel(true); err.cancel(true);
            for (Future<?> task : cleanup) {
                try { task.get(remaining(cleanupEnd), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; failure = append(failure, new IOException("Cleanup interrotto.", e)); }
                catch (ExecutionException | TimeoutException e) { failure = append(failure, new IOException("Cleanup del validatore incompleto.", e)); }
            }
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(Math.max(0, cleanupEnd - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    failure = append(failure, new IOException("Lettori del validatore non terminati entro la deadline."));
                }
            } catch (InterruptedException e) { interrupted = true; failure = append(failure, new IOException("Cleanup interrotto.", e)); }
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
        if (failure != null) { throw failure; }
        return result;
    }
    private static long remaining(long end) throws TimeoutException {
        long remaining = end - System.nanoTime();
        if (remaining <= 0) { throw new TimeoutException("Deadline del validatore superata."); }
        return remaining;
    }
    private static IOException append(IOException primary, IOException secondary) {
        if (primary == null) { return secondary; }
        primary.addSuppressed(secondary); return primary;
    }
    private static String drain(InputStream stream, boolean capture) throws IOException {
        try (stream) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (capture && prefix.size() < 256) { prefix.write(buffer, 0, Math.min(n, 256 - prefix.size())); }
            }
            return prefix.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private record Result(int exit, String output) { }
}
