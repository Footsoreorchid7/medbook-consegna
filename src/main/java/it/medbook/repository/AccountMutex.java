package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/*
 * Mutex account nominato per catalogo su connessione dedicata attorno a transazione READ COMMITTED.
 * Va acquisito prima di lock riga o verifiche definitive, poi UTENTE in ordine ID e MEDICO. Il
 * lavoro non deve confermare, annullare, cambiare autocommit o isolamento. Vietato annidare
 * execute: una nuova sessione attenderebbe il lock mantenuto dal chiamante fino al timeout.
 */
final class AccountMutex {
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private AccountMutex() { }

    static String lockName(String catalog) throws SQLException {
        if(catalog==null || catalog.isBlank())throw new SQLException("Catalogo non disponibile.");
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "medbook.accounts."+java.util.HexFormat.of().formatHex(digest,0,20);
        }catch(java.security.NoSuchAlgorithmException e){throw new SQLException("Mutex non disponibile.",e);}
    }

    private static boolean lockResult(Connection c,String sql,String name) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,name);try(ResultSet r=s.executeQuery()) {
                if(!r.next())throw new SQLException("Risultato mutex mancante.");
                int value=r.getInt(1);if(r.wasNull())throw new SQLException("Errore mutex.");
                if(value==1)return true;if(value==0)return false;throw new SQLException("Risultato mutex non valido.");
            }
        }
    }

    /*
     * Esegue e conferma sotto mutex. Un errore di pulizia dopo commit riuscito viene propagato: la
     * scrittura può essere persistita nonostante l'errore restituito.
     */
    static <T> T execute(ConnectionFactory connectionFactory,Work<T> work) throws SQLException {
        try(Connection c=connectionFactory.getConnection()) {
            boolean originalAuto=c.getAutoCommit();int originalIsolation=c.getTransactionIsolation();
            if(!originalAuto)throw new SQLException("Connection dedicata con autocommit richiesta.");
            String mutex=lockName(c.getCatalog());
            boolean acquired=false,started=false,completed=false;Throwable failure=null;
            try {
                if(!lockResult(c,"SELECT GET_LOCK(?, 5)",mutex))throw new UtenteRepository.LockAccountTimeoutException();
                acquired=true;
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                /* Una chiamata JDBC fallita può aver cambiato autocommit: tentare rollback prima del rilascio. */
                started=true;c.setAutoCommit(false);
                T result=work.run(c);
                c.commit();completed=true;return result;
            }catch(SQLException | RuntimeException | Error e) {
                Throwable primary=e;
                failure=primary;
                if(started && !completed)try{c.rollback();completed=true;}catch(SQLException x){primary.addSuppressed(x);}
                if(primary instanceof SQLException x)throw x;if(primary instanceof Error x)throw x;throw (RuntimeException)primary;
            }finally {
                // Le azioni di pulizia sono indipendenti. Mai rilasciare il mutex con transazione aperta incerta.
                Throwable cleanup=null;
                if(completed || !started) {
                    try{if(c.getTransactionIsolation()!=originalIsolation)c.setTransactionIsolation(originalIsolation);}
                    catch(SQLException x){cleanup=x;}
                    if(started)try{c.setAutoCommit(originalAuto);}catch(SQLException x){if(cleanup==null)cleanup=x;else cleanup.addSuppressed(x);}
                    if(acquired)try{if(!lockResult(c,"SELECT RELEASE_LOCK(?)",mutex))throw new SQLException("Rilascio mutex non riuscito.");}
                    catch(SQLException x){if(cleanup==null)cleanup=x;else cleanup.addSuppressed(x);}
                }
                /* La connessione fisica dedicata viene sempre chiusa, anche con acquisizione o rilascio ambigui. */
                if(cleanup!=null){if(failure!=null)failure.addSuppressed(cleanup);else throw (SQLException)cleanup;}
            }
        }
    }
}
