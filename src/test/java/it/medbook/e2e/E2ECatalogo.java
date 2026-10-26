package it.medbook.e2e;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/*
 * Catalogo esclusivo di una classe, creato da E2ELauncher. La factory runtime ha soli privilegi DML
 * e verifica catalogo e UUID a ogni connessione; soltanto il launcher può eliminarlo.
 */
public final class E2ECatalogo {
    private final String nome;
    private final ConnectionFactory factory;

    E2ECatalogo(String nome, E2EConfigurazione.Completa cfg) {
        this.nome = nome;
        String url = cfg.urlCatalogo(nome);
        /* Configurazione base sintetica non collegabile: getConnection è interamente sostituito. */
        this.factory = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() throws SQLException {
                Connection c = DriverManager.getConnection(url, cfg.runtimeUser(), cfg.runtimePassword());
                try {
                    E2ELauncher.verificaServer(c, nome, cfg.serverUuid());
                    return c;
                } catch (SQLException | RuntimeException e) {
                    try { c.close(); } catch (SQLException close) { e.addSuppressed(close); }
                    throw e;
                }
            }
        };
    }

    public String nome() { return nome; }
    public ConnectionFactory factory() { return factory; }
}
