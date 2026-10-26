package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.exception.RegistrazioneDuplicataException;
import java.sql.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

public class RegistrazioneRepository {
    private final ConnectionFactory connectionFactory;
    private final Clock clock;
    public RegistrazioneRepository(ConnectionFactory connectionFactory){this(connectionFactory,Clock.systemDefaultZone());}
    public RegistrazioneRepository(ConnectionFactory connectionFactory,Clock clock){
        this.connectionFactory=Objects.requireNonNull(connectionFactory);this.clock=Objects.requireNonNull(clock);
    }

    /** UNIQUE è il controllo definitivo anche in concorrenza: nessun SELECT preliminare soggetto a race.
     * Non modifica utenti esistenti e non assegna ruoli o profili medico.
     */
    public Integer registraPaziente(String nome,String cognome,String email,String passwordHash,
                                    String codiceFiscale,LocalDate dataNascita,String telefono) throws SQLException {
        try(Connection connection=connectionFactory.getConnection()){
            boolean originalAutoCommit=connection.getAutoCommit();boolean completed=false;Throwable failure=null;
            connection.setAutoCommit(false);
            try{
                int id;
                /* Senza lock: istante immediatamente prima dell'INSERT; autore non ancora noto. */
                var modificatoIl=UltimaModifica.adesso(clock);
                try(PreparedStatement statement=connection.prepareStatement(
                        "INSERT INTO utente(nome,cognome,email,password_hash,ruolo_gestionale,modificato_da,modificato_il) VALUES(?,?,?,?,NULL,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    statement.setString(1,nome);statement.setString(2,cognome);statement.setString(3,email);statement.setString(4,passwordHash);
                    UltimaModifica.imposta(statement,5,null,modificatoIl);
                    if(statement.executeUpdate()!=1){throw new SQLException("Creazione utente non completata.");}
                    try(ResultSet keys=statement.getGeneratedKeys()){
                        if(!keys.next()){throw new SQLException("Identificativo generato mancante.");}
                        long generated=keys.getLong(1);
                        if(generated<=0 || generated>Integer.MAX_VALUE){throw new SQLException("Identificativo fuori intervallo.");}
                        id=(int)generated;
                    }
                }
                try(PreparedStatement statement=connection.prepareStatement(
                        "INSERT INTO paziente(id_utente,codice_fiscale,data_nascita,telefono) VALUES(?,?,?,?)")){
                    statement.setInt(1,id);statement.setString(2,codiceFiscale);statement.setObject(3,dataNascita);statement.setString(4,telefono);
                    if(statement.executeUpdate()!=1){throw new SQLException("Creazione paziente non completata.");}
                }
                connection.commit();completed=true;return id;
            }catch(SQLException | RuntimeException | Error e){
                failure=e;
                try{connection.rollback();completed=true;}catch(SQLException rollbackError){e.addSuppressed(rollbackError);}
                // Una FK o un errore SQL diverso non viene scambiato per duplicazione.
                if(e instanceof SQLException sql && sql.getErrorCode()==1062){throw new RegistrazioneDuplicataException(sql);}
                throw e;
            }finally{
                // Se il rollback fallisce, non riattivare autocommit: chiudere la connessione.
                if(completed){
                    try{connection.setAutoCommit(originalAutoCommit);}
                    catch(SQLException restoreError){if(failure!=null){failure.addSuppressed(restoreError);}else{throw restoreError;}}
                }
            }
        }
    }
}
