package it.medbook.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.function.Function;

/** Parametri del listener; l'assenza degli override conserva il solo ascolto locale. */
public final class ServerConfig {
    private final InetSocketAddress address;

    public ServerConfig() { this(System::getenv); }

    ServerConfig(Function<String, String> environment) {
        String host = environment.apply("MEDBOOK_HTTP_HOST");
        String port = environment.apply("MEDBOOK_HTTP_PORT");
        host = host == null ? "127.0.0.1" : host.trim();
        port = port == null ? "8080" : port.trim();
        if (host.isEmpty() || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Configurazione del listener HTTP non valida.");
        }
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) {
            throw new IllegalStateException("Porta HTTP fuori intervallo 1–65535.");
        }
        try { address = new InetSocketAddress(InetAddress.getByName(host), number); }
        catch (UnknownHostException e) {
            throw new IllegalStateException("Indirizzo del listener HTTP non valido.", e);
        }
    }

    public InetSocketAddress address() { return address; }
}
