package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.PagamentoResponse;
import it.medbook.dto.PagamentoPubblicoResponse;
import it.medbook.payment.*;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import it.medbook.exception.*;
import it.medbook.model.ModalitaPagamento;
import it.medbook.model.StatoPagamento;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

public class PagamentoRepository {
    private final ConnectionFactory factory;
    private final Clock clock;
    private final GatewayPagamento gateway;
    public PagamentoRepository(ConnectionFactory factory) { this(factory, Clock.systemDefaultZone()); }
    public PagamentoRepository(ConnectionFactory factory, Clock clock) {
        this(factory, clock, new GatewayPagamentoSimulato());
    }
    public PagamentoRepository(ConnectionFactory factory, Clock clock, GatewayPagamento gateway) {
        this.factory = Objects.requireNonNull(factory); this.clock = Objects.requireNonNull(clock);
        this.gateway = Objects.requireNonNull(gateway);
    }

    // Tutti i futuri scrittori devono bloccare la prenotazione prima dei pagamenti.
    // Nessun retry automatico: una ripetizione dopo commit riceve 409.
    public PagamentoResponse registraInSedeAtomically(Integer idPrenotazione, Integer idOperatore,
            ModalitaPagamento modalita, BigDecimal importoAtteso) throws SQLException {
        if (idPrenotazione == null || idPrenotazione <= 0 || idOperatore == null || idOperatore <= 0
                || (modalita != ModalitaPagamento.DESK_POS && modalita != ModalitaPagamento.DESK_CONTANTI)
                || importoAtteso == null || importoAtteso.signum() < 0
                || importoAtteso.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("Dati di pagamento non validi.");
        }
        try { importoAtteso.setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Importo non valido."); }
        try (Connection connection = factory.getConnection()) {
            boolean original = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            // Evita gap lock sui pagamenti assenti di prenotazioni diverse.
            // Il mutex della prenotazione serializza tutti gli scrittori dello stesso incasso.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Throwable failure = null;
            boolean completed = false;
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=? FOR SHARE")) {
                    statement.setInt(1, idOperatore);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Operatore non trovato."); }
                        if (!"OPERATORE_DESK".equals(rows.getString("ruolo_gestionale"))
                                || !"ATTIVO".equals(rows.getString("stato_accesso"))) { // UC-26 §2.6: active actor
                            throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
                        }
                    }
                }
                int slot;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=?")) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        slot = rows.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                    statement.setInt(1, slot);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Disponibilità non trovata."); }
                    }
                }
                BigDecimal prezzo;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_disponibilita,stato,gestita_da,data_ora_gestione,id_medico_effettivo,
                               indisponibilita_segnalata,prezzo
                        FROM prenotazione WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) { throw new RisorsaNonTrovataException("Prenotazione non trovata."); }
                        if (rows.getInt("id_disponibilita") != slot || !"CONFERMATA".equals(rows.getString("stato"))
                                || rows.getObject("gestita_da") == null || rows.getObject("data_ora_gestione") == null
                                || rows.getObject("id_medico_effettivo") == null) {
                            throw new ConflittoPrenotazioneException("Prenotazione non pagabile o incoerente.");
                        }
                        if (rows.getBoolean("indisponibilita_segnalata")) {
                            throw new ConflittoPrenotazioneException("Indisponibilità ancora da gestire.");
                        }
                        prezzo = rows.getBigDecimal("prezzo");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id_pagamento,stato FROM pagamento WHERE id_prenotazione=?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    statement.setInt(1, idPrenotazione);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (!"FALLITO".equals(rows.getString("stato"))) {
                                throw new ConflittoPrenotazioneException("Pagamento già registrato o in attesa.");
                            }
                        }
                    }
                }
                if (prezzo == null || prezzo.signum() <= 0) {
                    throw new ConflittoPrenotazioneException("Nessun importo dovuto.");
                }
                if (prezzo.compareTo(importoAtteso) != 0) {
                    throw new ConflittoPrenotazioneException("Importo atteso diverso dal prezzo della prenotazione.");
                }
                LocalDateTime adesso = LocalDateTime.now(clock).withNano(0);
                int id;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,
                                               provider_payment_id,creato_il,pagato_il)
                        VALUES (?,?,?,?,'PAGATO',NULL,?,?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setInt(1,idPrenotazione); statement.setInt(2,idOperatore);
                    statement.setString(3,modalita.name()); statement.setBigDecimal(4,prezzo);
                    statement.setObject(5,adesso); statement.setObject(6,adesso);
                    if (statement.executeUpdate() != 1) { throw new SQLException("Pagamento non inserito."); }
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next() || keys.getLong(1) <= 0 || keys.getLong(1) > Integer.MAX_VALUE) {
                            throw new SQLException("Identificativo pagamento non valido.");
                        }
                        id = (int) keys.getLong(1);
                    }
                }
                PagamentoResponse result = new PagamentoResponse();
                result.setIdPagamento(id); result.setIdPrenotazione(idPrenotazione); result.setIdOperatore(idOperatore);
                result.setModalita(modalita); result.setImporto(prezzo); result.setStato(StatoPagamento.PAGATO);
                result.setCreatoIl(adesso); result.setPagatoIl(adesso);
                connection.commit(); completed = true;
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try { connection.rollback(); completed = true; }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally {
                if (completed) {
                    try {
                        connection.setTransactionIsolation(originalIsolation);
                        connection.setAutoCommit(original);
                    }
                    catch (SQLException restoreError) {
                        if (failure != null) { failure.addSuppressed(restoreError); }
                        else { throw restoreError; }
                    }
                }
            }
        }
    }

    public static void validaOnline(Integer prenotazione, Integer paziente, BigDecimal importo) {
        validaId(prenotazione); validaId(paziente);
        if (importo == null || importo.signum() <= 0 || importo.scale() > 2
                || importo.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("Importo atteso non valido.");
        }
    }
    private static void validaId(Integer id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("ID non valido.");
    }

    public PagamentoPubblicoResponse registraOnlineAtomically(Integer prenotazione, Integer paziente,
            BigDecimal importoAtteso) throws SQLException {
        validaOnline(prenotazione, paziente, importoAtteso);
        try (Connection c = factory.getConnection()) {
            boolean originalAutoCommit = c.getAutoCommit();
            int originalIsolation = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Throwable failure = null;
            boolean completed = false;
            try {
                c.setAutoCommit(false);
                try (PreparedStatement s = c.prepareStatement("SELECT id_utente FROM paziente WHERE id_utente=? FOR SHARE")) {
                    s.setInt(1, paziente);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                    }
                }
                int slot;
                try (PreparedStatement s = c.prepareStatement("SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=?")) {
                    s.setInt(1, prenotazione);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                        slot = r.getInt(1);
                    }
                }
                try (PreparedStatement s = c.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
                    s.setInt(1, slot);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                    }
                }
                BigDecimal prezzo;
                try (PreparedStatement s = c.prepareStatement("""
                        SELECT id_paziente,id_disponibilita,stato,gestita_da,data_ora_gestione,
                               id_medico_effettivo,indisponibilita_segnalata,prezzo
                        FROM prenotazione WHERE id_prenotazione=? FOR UPDATE
                        """)) {
                    s.setInt(1, prenotazione);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt("id_paziente") != paziente) {
                            throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                        }
                        if (r.getInt("id_disponibilita") != slot || !"CONFERMATA".equals(r.getString("stato"))
                                || r.getObject("gestita_da") == null || r.getObject("data_ora_gestione") == null
                                || r.getObject("id_medico_effettivo") == null || r.getBoolean("indisponibilita_segnalata")) {
                            throw new ConflittoPrenotazioneException("Prenotazione non pagabile o incoerente.");
                        }
                        prezzo = r.getBigDecimal("prezzo");
                    }
                }
                try (PreparedStatement s = c.prepareStatement("""
                        SELECT id_pagamento,stato FROM pagamento WHERE id_prenotazione=?
                        ORDER BY id_pagamento FOR UPDATE
                        """)) {
                    s.setInt(1, prenotazione);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            if (!"FALLITO".equals(r.getString("stato"))) {
                                throw new ConflittoPrenotazioneException("Pagamento già registrato o in attesa.");
                            }
                        }
                    }
                }
                if (prezzo == null || prezzo.signum() <= 0 || prezzo.compareTo(importoAtteso) != 0) {
                    throw new ConflittoPrenotazioneException("Importo dovuto non valido o diverso dall'importo atteso.");
                }
                String providerId = "SIM-" + UUID.randomUUID();
                // Solo calcolo Java sincrono: nessun I/O o attesa dentro la transazione.
                StatoPagamento esito;
                try { esito = gateway.paga(providerId, prezzo); }
                catch (RuntimeException error) { throw new IllegalStateException("Errore tecnico del simulatore.", error); }
                if (esito != StatoPagamento.PAGATO && esito != StatoPagamento.FALLITO) {
                    throw new IllegalStateException("Esito del simulatore non valido.");
                }
                LocalDateTime now = LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                LocalDateTime paid = esito == StatoPagamento.PAGATO ? now : null;
                int id;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO pagamento (id_prenotazione,registrato_da,modalita,importo,stato,
                                               provider_payment_id,creato_il,pagato_il)
                        VALUES (?,NULL,'ONLINE',?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    s.setInt(1, prenotazione); s.setBigDecimal(2, prezzo); s.setString(3, esito.name());
                    s.setString(4, providerId); s.setObject(5, now); s.setObject(6, paid);
                    if (s.executeUpdate() != 1) throw new SQLException("Pagamento non inserito.");
                    try (ResultSet keys = s.getGeneratedKeys()) {
                        if (!keys.next() || keys.getLong(1) <= 0 || keys.getLong(1) > Integer.MAX_VALUE) {
                            throw new SQLException("Identificativo pagamento non valido.");
                        }
                        id = (int) keys.getLong(1);
                    }
                }
                // Riverifica la riga prima del commit; DTO costruito dai valori persistiti.
                PagamentoPubblicoResponse result;
                try (PreparedStatement s = c.prepareStatement("SELECT * FROM pagamento WHERE id_pagamento=?")) {
                    s.setInt(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt("id_prenotazione") != prenotazione
                                || r.getObject("registrato_da") != null || !"ONLINE".equals(r.getString("modalita"))
                                || !esito.name().equals(r.getString("stato")) || !providerId.equals(r.getString("provider_payment_id"))
                                || prezzo.compareTo(r.getBigDecimal("importo")) != 0
                                || !now.equals(r.getObject("creato_il", LocalDateTime.class))
                                || !Objects.equals(paid, r.getObject("pagato_il", LocalDateTime.class))) {
                            throw new SQLException("Pagamento persistito non coerente.");
                        }
                        result = mapPubblico(r);
                    }
                }
                c.commit(); completed = true;
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try { c.rollback(); completed = true; }
                catch (SQLException secondary) { e.addSuppressed(secondary); }
                throw e;
            } finally {
                if (completed) {
                    try { c.setTransactionIsolation(originalIsolation); c.setAutoCommit(originalAutoCommit); }
                    catch (SQLException secondary) {
                        if (failure != null) failure.addSuppressed(secondary); else throw secondary;
                    }
                }
            }
        }
    }

    public List<PagamentoPubblicoResponse> findByPrenotazionePaziente(Integer prenotazione, Integer paziente)
            throws SQLException {
        validaId(prenotazione); validaId(paziente);
        // Una SELECT verifica proprietà e legge la lista nello stesso snapshot, senza lock di scrittura.
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT pr.id_prenotazione,p.id_pagamento,p.modalita,p.importo,p.stato,p.creato_il,p.pagato_il
                FROM prenotazione pr JOIN paziente pa ON pa.id_utente=pr.id_paziente
                LEFT JOIN pagamento p ON p.id_prenotazione=pr.id_prenotazione
                WHERE pr.id_prenotazione=? AND pa.id_utente=?
                ORDER BY p.creato_il DESC,p.id_pagamento DESC
                """)) {
            s.setInt(1, prenotazione); s.setInt(2, paziente);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new RisorsaNonTrovataException("Prenotazione non trovata.");
                List<PagamentoPubblicoResponse> results = new ArrayList<>();
                do { if (r.getObject("id_pagamento") != null) results.add(mapPubblico(r)); } while (r.next());
                return results;
            }
        }
    }
    private static PagamentoPubblicoResponse mapPubblico(ResultSet r) throws SQLException {
        var out = new PagamentoPubblicoResponse();
        out.setIdPagamento(r.getInt("id_pagamento")); out.setIdPrenotazione(r.getInt("id_prenotazione"));
        out.setModalita(ModalitaPagamento.valueOf(r.getString("modalita"))); out.setImporto(r.getBigDecimal("importo"));
        out.setStato(StatoPagamento.valueOf(r.getString("stato")));
        out.setCreatoIl(r.getObject("creato_il", LocalDateTime.class)); out.setPagatoIl(r.getObject("pagato_il", LocalDateTime.class));
        return out;
    }

}
