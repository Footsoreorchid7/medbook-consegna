package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import it.medbook.config.AuthConfig;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.model.RuoloGestionale;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.*;
import it.medbook.service.AutenticazioneService;
import it.medbook.support.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/*
 * Errore durante la risposta login dopo gli header: nessuna seconda risposta, candidato revocato,
 * exchange chiuso ed errore originale propagato. Il doppio rifiuta il secondo invio come il server
 * JDK.
 */
class AutenticazioneControllerInvioTest {
    static final String ORIGIN = "http://localhost:8080";
    static final String LOGIN = "{\"email\":\"solo-test@example.invalid\",\"password\":\"Solo-test-invio\"}";
    static final PasswordHasher HASHER = new PasswordHasher();
    static String hash;
    final MutableClock clock = new MutableClock();
    final FakeUtenteRepository repository = new FakeUtenteRepository();
    final SessionStore store = new SessionStore(clock, 100);
    final AuthConfig config = new AuthConfig("local-http", ORIGIN);
    AutenticazioneController controller;

    @BeforeAll static void hash() { hash = HASHER.hash("Solo-test-invio".toCharArray()); }
    @BeforeEach void prepare() {
        repository.identity = new UtenteAutenticatoResponse(7, "Nome", "Sintetico", false, false, RuoloGestionale.AMMINISTRATORE);
        repository.account = new UtenteRepository.Credenziali(repository.identity, hash);
        controller = new AutenticazioneController(new AutenticazioneService(repository, HASHER, store), store,
                new LoginThrottle(clock, 100, 10), config, new ObjectMapper());
    }

    static final class Guasto extends HttpExchange {
        final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        final Exception fault; final boolean onClose;
        int headerSends, status = -1; boolean closed, inputClosed, outputClosed;
        final InputStream input = new ByteArrayInputStream(LOGIN.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { inputClosed = true; super.close(); }
        };
        final OutputStream output = new OutputStream() {
            @Override public void write(int b) throws IOException { if (!onClose) { fail(); } }
            @Override public void write(byte[] b, int off, int len) throws IOException { if (!onClose) { fail(); } }
            @Override public void close() throws IOException { outputClosed = true; if (onClose) { fail(); } }
        };
        Guasto(Exception fault, boolean onClose) {
            this.fault = fault; this.onClose = onClose;
            requestHeaders.set("Origin", ORIGIN); requestHeaders.set("Content-Type", "application/json");
        }
        void fail() throws IOException {
            if (fault instanceof IOException io) { throw io; }
            throw (RuntimeException) fault;
        }
        @Override public void sendResponseHeaders(int code, long length) throws IOException {
            headerSends++;
            if (headerSends > 1) { throw new IOException("headers already sent"); }
            status = code;
        }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/api/auth/login"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { throw new UnsupportedOperationException(); }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    void assertSingleSendRevokedAndClosed(Supplier<Exception> fault, boolean onClose) {
        Exception original = fault.get();
        Guasto exchange = new Guasto(original, onClose);
        Exception thrown = assertThrows(Exception.class, () -> controller.handle(exchange));
        assertSame(original, thrown, "errore originario non mascherato da un secondo invio");
        assertEquals(1, exchange.headerSends, "una sola emissione degli header");
        assertEquals(200, exchange.status);
        assertNotNull(exchange.responseHeaders.getFirst("Set-Cookie"), "header già preparati e inviati");
        assertEquals(0, store.size(), "sessione candidata revocata");
        assertTrue(exchange.closed, "scambio chiuso"); assertTrue(exchange.inputClosed); assertTrue(exchange.outputClosed);
    }

    @Test void uncheckedFaultWhileWritingBodyIsNotFollowedByA500() {
        assertSingleSendRevokedAndClosed(() -> new IllegalStateException("guasto iniettato"), false);
        assertSingleSendRevokedAndClosed(() -> new IllegalArgumentException("guasto iniettato"), false);
    }
    @Test void uncheckedFaultWhileClosingBodyIsNotFollowedByA500() {
        assertSingleSendRevokedAndClosed(() -> new IllegalStateException("guasto iniettato"), true);
    }
    @Test void ioFaultWhileWritingBodyPropagatesOnce() {
        assertSingleSendRevokedAndClosed(() -> new IOException("connessione chiusa"), false);
    }
    @Test void errorsBeforeHeadersStillGetTheNormalResponse() throws Exception {
        repository.failure = new java.sql.SQLException("driver");
        var exchange = new TestHttpExchange("POST", "/api/auth/login", LOGIN);
        exchange.requestHeaders.set("Origin", ORIGIN);
        controller.handle(exchange);
        assertEquals(500, exchange.status); assertEquals("{\"error\":\"Errore interno del server.\"}", exchange.body());
        assertNull(exchange.responseHeaders.getFirst("Set-Cookie")); assertTrue(exchange.closed); assertEquals(0, store.size());
    }
}
