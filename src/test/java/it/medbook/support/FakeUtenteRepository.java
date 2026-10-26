package it.medbook.support;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.UtenteAutenticatoResponse;
import it.medbook.exception.AnomaliaDatiAccountException;
import it.medbook.repository.UtenteRepository;
import java.sql.*;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeUtenteRepository extends UtenteRepository {
    public volatile Credenziali account;
    public volatile UtenteAutenticatoResponse identity;
    public volatile SQLException failure;
    public volatile SQLException idFailure;
    public final Map<Integer, UtenteAutenticatoResponse> identities = new ConcurrentHashMap<>();
    public int emailCalls, idCalls;
    public String requestedEmail;
    public Integer requestedId;
    public FakeUtenteRepository() {
        super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() { throw new AssertionError("Nessun JDBC nei test unitari auth."); }
        });
    }
    @Override public synchronized Optional<Credenziali> findByEmail(String email) throws SQLException {
        emailCalls++; requestedEmail = email;
        if (failure != null) { throw failure; }
        return Optional.ofNullable(account);
    }
    @Override public synchronized Optional<UtenteAutenticatoResponse> findById(Integer id) throws SQLException {
        idCalls++; requestedId = id;
        if (failure != null) { throw failure; }
        if (idFailure != null) { throw idFailure; }
        return Optional.ofNullable(identities.isEmpty() ? identity : identities.get(id));
    }

    /* Per default gli account delle fixture sono ATTIVO con generazione zero. */
    /** Raw access state: ATTIVO, DISABILITATO or any other text (anomalous). */
    public volatile String statoAccesso = UtenteRepository.ATTIVO;
    /** Committed generation; empty means out of domain (anomalous). */
    public volatile OptionalLong generazione = OptionalLong.of(0);
    /* Anomalie ulteriori restituite dalla lettura login, per esempio il ruolo. */
    public volatile Set<Anomalia> anomalie = Set.of();

    @Override public synchronized Optional<CredenzialiLogin> leggiCredenzialiLogin(String email) throws SQLException {
        emailCalls++; requestedEmail = email;
        if (failure != null) { throw failure; }
        if (account == null) { return Optional.empty(); }
        Set<Anomalia> found = EnumSet.noneOf(Anomalia.class);
        found.addAll(anomalie);
        String stato = statoCanonico();
        if (stato == null) { found.add(Anomalia.STATO); }
        if (generazione.isEmpty()) { found.add(Anomalia.GENERAZIONE); }
        return Optional.of(new CredenzialiLogin(account.identita().idUtente(), account.passwordHash(), stato, generazione,
                found, found.contains(Anomalia.RUOLO) ? null : account.identita()));
    }

    @Override public synchronized Optional<StatoSessione> findSessione(Integer id) throws SQLException {
        idCalls++; requestedId = id;
        if (failure != null) { throw failure; }
        if (idFailure != null) { throw idFailure; }
        UtenteAutenticatoResponse found = identities.isEmpty() ? identity : identities.get(id);
        if (found == null) { return Optional.empty(); }
        String stato = statoCanonico();
        if (stato == null || generazione.isEmpty() || anomalie.contains(Anomalia.RUOLO)) {
            throw new AnomaliaDatiAccountException();
        }
        return UtenteRepository.ATTIVO.equals(stato)
                ? Optional.of(new StatoSessione(found, generazione.getAsLong())) : Optional.empty();
    }

    private String statoCanonico() {
        String stato = statoAccesso;
        return UtenteRepository.ATTIVO.equals(stato) || UtenteRepository.DISABILITATO.equals(stato) ? stato : null;
    }
}
