package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.dto.*;
import it.medbook.exception.*;
import it.medbook.model.StatoRimborso;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class RimborsoRepository {
    private final ConnectionFactory factory;
    private final Clock clock;
    public RimborsoRepository(ConnectionFactory factory) { this(factory, Clock.systemDefaultZone()); }
    public RimborsoRepository(ConnectionFactory factory, Clock clock) {
        this.factory = Objects.requireNonNull(factory); this.clock = Objects.requireNonNull(clock);
    }
    private static final String READ = """
        SELECT r.id_rimborso,r.id_pagamento,r.stato,r.importo,r.motivazione,r.richiesto_il,r.verificato_il,
               p.id_prenotazione
        FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento
        JOIN prenotazione pr ON pr.id_prenotazione=p.id_prenotazione
        """;
    public List<RimborsoResponse> findByPaziente(Integer paziente) throws SQLException {
        validaId(paziente);
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(READ +
                " WHERE pr.id_paziente=? ORDER BY r.richiesto_il DESC,r.id_rimborso DESC")) {
            s.setInt(1,paziente);
            try (ResultSet r = s.executeQuery()) {
                List<RimborsoResponse> out = new ArrayList<>();
                while (r.next()) { var item = new RimborsoResponse(); map(r,item); out.add(item); }
                return out;
            }
        }
    }
    public List<RichiestaRimborsoDeskResponse> findRichieste() throws SQLException {
        return findDesk(null, "RICHIESTO");
    }
    public List<RichiestaRimborsoDeskResponse> findStoricoDesk(Integer operatore, String stato) throws SQLException {
        validaId(operatore);
        if (!Set.of("RICHIESTO", "APPROVATO", "RESPINTO", "TUTTI").contains(stato)) throw new IllegalArgumentException();
        return findDesk(operatore, stato);
    }
    private List<RichiestaRimborsoDeskResponse> findDesk(Integer operatore, String stato) throws SQLException {
        String sql = """
            SELECT r.verificato_da,op.nome AS nome_operatore,op.cognome AS cognome_operatore,r.id_rimborso,r.id_pagamento,r.stato,r.importo,r.motivazione,r.richiesto_il,r.verificato_il,
                   p.id_prenotazione,p.modalita,pr.id_paziente,u.nome,u.cognome,
                   d.id_prestazione,ps.nome AS nome_prestazione,d.data_ora_inizio,d.data_ora_fine
            FROM rimborso r JOIN pagamento p ON p.id_pagamento=r.id_pagamento
            JOIN prenotazione pr ON pr.id_prenotazione=p.id_prenotazione
            JOIN paziente pa ON pa.id_utente=pr.id_paziente JOIN utente u ON u.id_utente=pa.id_utente
            JOIN disponibilita d ON d.id_disponibilita=pr.id_disponibilita
            LEFT JOIN prestazione ps ON ps.id_prestazione=d.id_prestazione
            LEFT JOIN utente op ON op.id_utente=r.verificato_da
            WHERE (?='TUTTI' OR r.stato=?) ORDER BY r.richiesto_il ASC,r.id_rimborso ASC
            """;
        try (Connection c = factory.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (operatore != null) operator(c, operatore);
            s.setString(1, stato); s.setString(2, stato);
            try (ResultSet r = s.executeQuery()) {
                List<RichiestaRimborsoDeskResponse> out = new ArrayList<>();
                while (r.next()) {
                    var item = new RichiestaRimborsoDeskResponse();
                    item.setIdRimborso(r.getInt("id_rimborso")); item.setIdPagamento(r.getInt("id_pagamento"));
                    item.setIdPrenotazione(r.getInt("id_prenotazione")); item.setStato(StatoRimborso.valueOf(r.getString("stato")));
                    item.setImporto(r.getBigDecimal("importo")); item.setMotivazione(r.getString("motivazione"));
                    item.setRichiestoIl(r.getObject("richiesto_il",LocalDateTime.class)); item.setVerificatoIl(r.getObject("verificato_il",LocalDateTime.class));
                    item.setIdPaziente(r.getInt("id_paziente")); item.setNomePaziente(r.getString("nome")); item.setCognomePaziente(r.getString("cognome"));
                    item.setIdPrestazione(r.getObject("id_prestazione",Integer.class)); item.setNomePrestazione(r.getString("nome_prestazione"));
                    item.setDataOraInizio(r.getObject("data_ora_inizio",LocalDateTime.class)); item.setDataOraFine(r.getObject("data_ora_fine",LocalDateTime.class));
                    item.setModalitaPagamento(r.getString("modalita"));
                    item.setIdOperatore(r.getObject("verificato_da",Integer.class));
                    item.setNomeOperatore(r.getString("nome_operatore")); item.setCognomeOperatore(r.getString("cognome_operatore"));
                    out.add(item);
                }
                if (operatore != null) operator(c, operatore);
                return out;
            }
        }
    }
    private static void map(ResultSet r, RimborsoResponse item) throws SQLException {
        item.setIdRimborso(r.getInt("id_rimborso")); item.setIdPagamento(r.getInt("id_pagamento"));
        item.setIdPrenotazione(r.getInt("id_prenotazione")); item.setStato(StatoRimborso.valueOf(r.getString("stato")));
        item.setImporto(r.getBigDecimal("importo")); item.setMotivazione(r.getString("motivazione"));
        item.setRichiestoIl(r.getObject("richiesto_il",LocalDateTime.class)); item.setVerificatoIl(r.getObject("verificato_il",LocalDateTime.class));
    }
    // Mutex comune a tutti i flussi economici: disponibilità -> prenotazione -> pagamenti -> rimborso.
    // READ COMMITTED evita gap lock su rimborsi assenti di prenotazioni distinte. Nessun retry.
    public RimborsoResponse richiediAtomically(Integer prenotazione, Integer paziente, String motivazione) throws SQLException {
        validaId(prenotazione); validaId(paziente); validaMotivazione(motivazione);
        return transaction(c -> {
            patient(c,paziente);
            int slot = discoverySlot(c,prenotazione);
            lockSlot(c,slot);
            Booking booking = lockBooking(c,prenotazione,slot);
            if (booking.patient != paziente) throw missing();
            List<Payment> payments = lockPayments(c,prenotazione);
            Payment payment = eligible(booking,payments);
            try (PreparedStatement s = c.prepareStatement("SELECT id_rimborso FROM rimborso WHERE id_pagamento=? FOR UPDATE")) {
                s.setInt(1,payment.id);
                try (ResultSet r = s.executeQuery()) { if (r.next()) throw conflict(); }
            }
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            int id;
            try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO rimborso (id_pagamento,stato,importo,richiesto_il,motivazione)
                VALUES (?,'RICHIESTO',?,?,?)
                """,Statement.RETURN_GENERATED_KEYS)) {
                s.setInt(1,payment.id); s.setBigDecimal(2,payment.amount); s.setObject(3,now); s.setString(4,motivazione);
                if (s.executeUpdate()!=1) throw new SQLException("Rimborso non inserito.");
                try (ResultSet r = s.getGeneratedKeys()) {
                    if (!r.next() || r.getLong(1)<=0 || r.getLong(1)>Integer.MAX_VALUE) throw new SQLException("Identificativo rimborso non valido.");
                    id = (int)r.getLong(1);
                }
            } catch (SQLIntegrityConstraintViolationException e) {
                if (e.getErrorCode()==1062) throw new ConflittoPrenotazioneException("Richiesta di rimborso già presente.");
                throw e;
            }
            var out = new RimborsoResponse(); out.setIdRimborso(id); out.setIdPrenotazione(prenotazione);
            out.setIdPagamento(payment.id); out.setStato(StatoRimborso.RICHIESTO); out.setImporto(payment.amount);
            out.setMotivazione(motivazione); out.setRichiestoIl(now); return out;
        });
    }
    public DecisioneRimborsoResponse decidiAtomically(Integer rimborso, Integer operatore, boolean approva) throws SQLException {
        validaId(rimborso); validaId(operatore);
        return transaction(c -> {
            operator(c,operatore);
            int paymentId, bookingId, slot;
            try (PreparedStatement s = c.prepareStatement("""
                SELECT r.id_pagamento,p.id_prenotazione,pr.id_disponibilita FROM rimborso r
                LEFT JOIN pagamento p ON p.id_pagamento=r.id_pagamento
                LEFT JOIN prenotazione pr ON pr.id_prenotazione=p.id_prenotazione WHERE r.id_rimborso=?
                """)) {
                s.setInt(1,rimborso);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw missing();
                    if (r.getObject(1)==null || r.getObject(2)==null || r.getObject(3)==null) throw conflict();
                    paymentId=r.getInt(1); bookingId=r.getInt(2); slot=r.getInt(3);
                    if (r.next()) throw conflict();
                }
            }
            lockSlot(c,slot); Booking booking=lockBooking(c,bookingId,slot);
            List<Payment> payments=lockPayments(c,bookingId);
            if (payments.stream().filter(p -> p.id==paymentId).count()!=1) throw conflict();
            BigDecimal refundAmount;
            try (PreparedStatement s=c.prepareStatement("SELECT * FROM rimborso WHERE id_rimborso=? FOR UPDATE")) {
                s.setInt(1,rimborso);
                try (ResultSet r=s.executeQuery()) {
                    if (!r.next()) throw missing();
                    if (r.getInt("id_pagamento")!=paymentId || !"RICHIESTO".equals(r.getString("stato"))
                            || r.getObject("verificato_da")!=null || r.getObject("verificato_il")!=null
                            || r.getObject("provider_refund_id")!=null || r.getObject("rimborsato_il")!=null) throw conflict();
                    refundAmount=r.getBigDecimal("importo");
                }
            }
            if (approva) {
                Payment payment=eligible(booking,payments);
                if (payment.id!=paymentId || refundAmount==null || refundAmount.compareTo(payment.amount)!=0) throw conflict();
            }
            LocalDateTime now=LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            StatoRimborso state=approva?StatoRimborso.APPROVATO:StatoRimborso.RESPINTO;
            try (PreparedStatement s=c.prepareStatement("""
                UPDATE rimborso SET stato=?,verificato_da=?,verificato_il=?
                WHERE id_rimborso=? AND id_pagamento=? AND stato='RICHIESTO'
                """)) {
                s.setString(1,state.name()); s.setInt(2,operatore); s.setObject(3,now); s.setInt(4,rimborso); s.setInt(5,paymentId);
                if (s.executeUpdate()!=1) throw conflict();
            }
            var out=new DecisioneRimborsoResponse(); out.setIdRimborso(rimborso); out.setStato(state);
            out.setIdOperatore(operatore); out.setVerificatoIl(now); return out;
        });
    }
    private record Booking(int patient, String state, BigDecimal price, boolean pending) { }
    private record Payment(int id, String state, String mode, BigDecimal amount) { }
    private static Payment eligible(Booking booking,List<Payment> payments) {
        if (!"NON_EROGATA".equals(booking.state) || booking.pending || booking.price==null || booking.price.signum()<=0) throw conflict();
        Payment paid=null;
        for (Payment p:payments) {
            if ("FALLITO".equals(p.state)) continue;
            if (!"PAGATO".equals(p.state) || paid!=null) throw conflict();
            paid=p;
        }
        if (paid==null || !"ONLINE".equals(paid.mode) || paid.amount==null || paid.amount.signum()<=0 || paid.amount.compareTo(booking.price)!=0) throw conflict();
        return paid;
    }
    private static void patient(Connection c,int id)throws SQLException {
        try (PreparedStatement s=c.prepareStatement("SELECT id_utente FROM paziente WHERE id_utente=? FOR SHARE")) {
            s.setInt(1,id); try(ResultSet r=s.executeQuery()) { if(!r.next())throw missing(); }
        }
    }
    private static void operator(Connection c,int id)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente=? FOR SHARE")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()) {
                if(!r.next())throw missing();
                /* L'operatore deve essere esattamente ATTIVO. */
                if(!"OPERATORE_DESK".equals(r.getString("ruolo_gestionale")) || !"ATTIVO".equals(r.getString("stato_accesso")))throw new OperazioneNonAutorizzataException("Operazione consentita solo a OPERATORE_DESK.");
            }
        }
    }
    private static int discoverySlot(Connection c,int id)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id_disponibilita FROM prenotazione WHERE id_prenotazione=?")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()) {if(!r.next())throw missing();return r.getInt(1);}
        }
    }
    private static void lockSlot(Connection c,int id)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id_disponibilita FROM disponibilita WHERE id_disponibilita=? FOR UPDATE")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw conflict();}
        }
    }
    private static Booking lockBooking(Connection c,int id,int slot)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id_paziente,id_disponibilita,stato,prezzo,indisponibilita_segnalata FROM prenotazione WHERE id_prenotazione=? FOR UPDATE")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()) {
                if(!r.next())throw missing();if(r.getInt("id_disponibilita")!=slot)throw conflict();
                return new Booking(r.getInt("id_paziente"),r.getString("stato"),r.getBigDecimal("prezzo"),r.getBoolean("indisponibilita_segnalata"));
            }
        }
    }
    private static List<Payment> lockPayments(Connection c,int id)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id_pagamento,stato,modalita,importo FROM pagamento WHERE id_prenotazione=? ORDER BY id_pagamento FOR UPDATE")) {
            s.setInt(1,id);try(ResultSet r=s.executeQuery()) {List<Payment> out=new ArrayList<>();while(r.next())out.add(new Payment(r.getInt(1),r.getString(2),r.getString(3),r.getBigDecimal(4)));return out;}
        }
    }
    private static RisorsaNonTrovataException missing(){return new RisorsaNonTrovataException("Risorsa non trovata.");}
    private static ConflittoPrenotazioneException conflict(){return new ConflittoPrenotazioneException("Richiesta di rimborso incompatibile o incoerente.");}
    private static void validaId(Integer id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Identificativo non valido.");
    }
    private static void validaMotivazione(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 500) {
            throw new IllegalArgumentException("Motivazione obbligatoria, massimo 500 caratteri.");
        }
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            // utf8mb4 ammette caratteri Unicode scalari, non surrogati isolati.
            if ((cp >= 0xD800 && cp <= 0xDFFF) || (Character.isISOControl(cp) && cp != '\r' && cp != '\n' && cp != '\t')) {
                throw new IllegalArgumentException("Motivazione contenente caratteri non validi.");
            }
        }
    }
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T> T transaction(Work<T> work)throws SQLException {
        try(Connection c=factory.getConnection()) {
            boolean original=c.getAutoCommit();int isolation=c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Throwable failure=null;boolean completed=false;
            try {
                c.setAutoCommit(false);T out=work.run(c);c.commit();completed=true;return out;
            } catch(SQLException|RuntimeException|Error e) {
                failure=e;try{c.rollback();completed=true;}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;
            } finally {
                if(completed)try{c.setTransactionIsolation(isolation);c.setAutoCommit(original);}
                catch(SQLException restore){if(failure!=null)failure.addSuppressed(restore);else throw restore;}
            }
        }
    }
}
