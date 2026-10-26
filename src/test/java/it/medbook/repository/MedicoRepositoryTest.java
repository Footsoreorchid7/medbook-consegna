package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.ConflittoMedicoException;
import it.medbook.exception.OperazioneNonAutorizzataException;
import it.medbook.exception.RisorsaNonTrovataException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* JDBC sintetico: ordine lock, decisione dell'attore, classificazione duplicati e rollback. */
class MedicoRepositoryTest {
    static final String UTENTE_LOCK = "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? FOR UPDATE";
    static final String MEDICO_LOCK = "SELECT stato FROM medico WHERE id_utente = ? FOR UPDATE";

    static class Jdbc {
        final List<String> events = new ArrayList<>();
        final Map<Integer, String> utenti = new HashMap<>(Map.of(7, "AMMINISTRATORE", 9, ""));
        final Map<Integer, String> medici = new HashMap<>();
        /** UC-26: raw access state per UTENTE row; absent means ATTIVO. */
        final Map<Integer, String> stati = new HashMap<>();
        ResultSet utente(int id) {
            if (!utenti.containsKey(id)) { return rows(List.of()); }
            Map<String, Object> r = new HashMap<>(); r.put("ruolo_gestionale", utenti.get(id)); r.put("stato_accesso", stati.getOrDefault(id, "ATTIVO"));
            return rows(List.of(r));
        }
        final Map<String, SQLException> failUpdate = new HashMap<>();
        boolean emailPresente, numeroPresente;
        Jdbc() { utenti.put(9, null); }
        ResultSet rows(List<Map<String, Object>> data) {
            int[] i = {-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                case "next" -> ++i[0] < data.size();
                case "getString" -> (String) data.get(i[0]).get(a[0] instanceof Integer ? "1" : (String) a[0]);
                case "getInt" -> a[0] instanceof Integer ? 1 : (Integer) data.get(i[0]).get(a[0]);
                case "getLong" -> 50L;
                case "getBoolean" -> false;
                case "wasNull" -> false;
                default -> null;
            });
        }
        static List<Map<String, Object>> one(String key, Object value) { Map<String, Object> r = new HashMap<>(); r.put(key, value); return List.of(r); }
        ResultSet query(String sql, Map<Integer, Object> args) {
            if (sql.contains("GET_LOCK") || sql.contains("RELEASE_LOCK")) { events.add(sql.contains("GET_LOCK") ? "acquire" : "release"); return rows(List.of(Map.of())); }
            events.add(sql + " " + args.values());
            if (sql.equals(UTENTE_LOCK)) { return utente((Integer) args.get(1)); }
            if (sql.equals(MEDICO_LOCK)) { int id = (Integer) args.get(1); return rows(medici.containsKey(id) ? one("stato", medici.get(id)) : List.of()); }
            if (sql.startsWith("SELECT 1 FROM utente WHERE email")) { return rows(emailPresente ? List.of(Map.of()) : List.of()); }
            if (sql.startsWith("SELECT 1 FROM medico WHERE numero_iscrizione_ordine")) { return rows(numeroPresente ? List.of(Map.of()) : List.of()); }
            Map<String, Object> r = new HashMap<>(Map.of("id_utente", 50, "nome", "N", "cognome", "C", "email", "e", "specializzazione", "S",
                    "numero_iscrizione_ordine", "O", "stato", "ATTIVO")); r.put("ruolo_gestionale", null);
            return rows(List.of(r));
        }
        Connection connection() {
            boolean[] auto = {true}; int[] iso = {Connection.TRANSACTION_REPEATABLE_READ};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                case "getAutoCommit" -> auto[0];
                case "getTransactionIsolation" -> iso[0];
                case "getCatalog" -> "medbook_test_unit";
                case "setAutoCommit" -> { auto[0] = (boolean) a[0]; events.add("auto " + a[0]); yield null; }
                case "setTransactionIsolation" -> { iso[0] = (int) a[0]; yield null; }
                case "commit" -> { events.add("commit"); yield null; }
                case "rollback" -> { events.add("rollback"); yield null; }
                case "close" -> null;
                case "prepareStatement" -> statement(((String) a[0]).replaceAll("\\s+", " ").trim());
                default -> null;
            });
        }
        PreparedStatement statement(String sql) {
            Map<Integer, Object> args = new TreeMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                if (m.getName().startsWith("set")) { args.put((Integer) a[0], a[1]); return null; }
                return switch (m.getName()) {
                    case "executeQuery" -> query(sql, args);
                    case "executeUpdate" -> {
                        String key = sql.split(" ")[0] + " " + sql.split(" ")[sql.startsWith("UPDATE") ? 1 : 2];
                        events.add(key);
                        if (failUpdate.containsKey(key)) { throw failUpdate.get(key); }
                        yield 1;
                    }
                    case "getGeneratedKeys" -> rows(List.of(Map.of()));
                    default -> null;
                };
            });
        }
        MedicoRepository repo() {
            return new MedicoRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) { @Override public Connection getConnection() { return connection(); } });
        }
        List<String> locks() { return events.stream().filter(e -> e.contains("FOR UPDATE")).map(e -> e.replace(" FOR UPDATE", "")).toList(); }
    }

    @Test void createLinkAndUpdatesLockUtenteAscendingThenMedicoInsideTheMutex() throws Exception {
        var j = new Jdbc(); j.repo().crea(7, "N", "C", "e@x.invalid", "hash", "S", "O");
        assertEquals(List.of("acquire", "auto false", UTENTE_LOCK + " [7]", "INSERT utente", "INSERT medico"), j.events.subList(0, 5));
        assertEquals(List.of("commit", "auto true", "release"), j.events.subList(j.events.size() - 3, j.events.size()));
        /* ID dell'attore sopra o sotto il destinatario: prima UTENTE in ordine crescente, poi MEDICO. */
        j = new Jdbc(); j.utenti.put(9, "AMMINISTRATORE"); j.utenti.put(7, null); j.repo().collega(9, 7, "S", "O");
        assertEquals(List.of("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [7]", "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [9]",
                "SELECT stato FROM medico WHERE id_utente = ? [7]"), j.locks());
        j = new Jdbc(); j.utenti.put(12, null); j.medici.put(12, "INATTIVO"); j.repo().aggiornaDatiProfessionali(7, 12, "S", "O");
        assertEquals(List.of("SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [7]", "SELECT ruolo_gestionale, stato_accesso FROM utente WHERE id_utente = ? [12]",
                "SELECT stato FROM medico WHERE id_utente = ? [12]"), j.locks());
        assertTrue(j.events.indexOf("UPDATE medico") > j.events.indexOf(MEDICO_LOCK + " [12]"));
        j = new Jdbc(); j.medici.put(9, "ATTIVO"); j.repo().aggiornaDatiPersonali(7, 9, "N", "C", "e@x.invalid");
        assertTrue(j.events.contains("UPDATE utente")); assertTrue(j.events.contains("commit"));
    }

    @Test void demotedOrMissingActorGets403BeforeAnyTargetDecision() {
        for (String ruolo : Arrays.asList("OPERATORE_DESK", null, "amministratore")) {
            for (boolean targetPresente : new boolean[]{true, false}) {
                var j = new Jdbc(); j.utenti.put(7, ruolo); if (!targetPresente) { j.utenti.remove(9); } else { j.medici.put(9, "ATTIVO"); }
                assertThrows(OperazioneNonAutorizzataException.class, () -> j.repo().aggiornaDatiProfessionali(7, 9, "S", "O"));
                assertThrows(OperazioneNonAutorizzataException.class, () -> j.repo().collega(7, 9, "S", "O"));
                assertFalse(j.events.stream().anyMatch(e -> e.startsWith("SELECT stato") || e.startsWith("INSERT") || e.startsWith("UPDATE")));
                assertFalse(j.events.contains("commit")); assertTrue(j.events.contains("rollback"));
            }
        }
        var j = new Jdbc(); j.utenti.remove(7);
        assertThrows(OperazioneNonAutorizzataException.class, () -> j.repo().crea(7, "N", "C", "e@x.invalid", "hash", "S", "O"));
        assertFalse(j.events.contains("INSERT utente"));
        var k = new Jdbc(); k.utenti.remove(9);
        assertThrows(RisorsaNonTrovataException.class, () -> k.repo().aggiornaDatiPersonali(7, 9, "N", "C", "e@x.invalid"));
    }

    @Test void duplicatesAreRecognizedByCodeStateAndKeyReReadNotByMessage() {
        for (String message : Arrays.asList("Duplicate entry 'e' for key 'utente.uq_utente_email'", "Valore duplicato 'e' per la chiave", "", null)) {
            var j = new Jdbc(); j.emailPresente = true; j.failUpdate.put("INSERT utente", new SQLException(message, "23000", 1062));
            var e = assertThrows(ConflittoMedicoException.class, () -> j.repo().crea(7, "N", "C", "e@x.invalid", "hash", "S", "O"));
            assertEquals(MedicoRepository.EMAIL_DUPLICATA, e.getMessage()); assertSame(j.failUpdate.get("INSERT utente"), e.getCause());
            var k = new Jdbc(); k.numeroPresente = true; k.failUpdate.put("INSERT medico", new SQLException(message, "23000", 1062));
            assertEquals(MedicoRepository.NUMERO_DUPLICATO, assertThrows(ConflittoMedicoException.class, () -> k.repo().crea(7, "N", "C", "e@x.invalid", "hash", "S", "O")).getMessage());
            assertTrue(k.events.contains("rollback")); assertFalse(k.events.contains("commit"));
        }
        /* La rilettura esclude il medico stesso durante la modifica. */
        var u = new Jdbc(); u.medici.put(9, "ATTIVO"); u.numeroPresente = true; u.failUpdate.put("UPDATE medico", new SQLException("x", "23000", 1062));
        assertEquals(MedicoRepository.NUMERO_DUPLICATO, assertThrows(ConflittoMedicoException.class, () -> u.repo().aggiornaDatiProfessionali(7, 9, "S", "O")).getMessage());
        assertTrue(u.events.contains("SELECT 1 FROM medico WHERE numero_iscrizione_ordine = ? AND id_utente <> ? [O, 9]"));
    }

    @Test void otherSqlErrorsStaySqlErrorsAndRollBackTheWholeCreation() {
        /* 1062 non confermato dalla rilettura, altri codici o SQLSTATE: nessun 409. */
        for (SQLException error : List.of(new SQLException("dup", "23000", 1062), new SQLException("x", "22001", 1406),
                new SQLException("x", "HY000", 1062), new SQLException("x", "40001", 1213))) {
            var j = new Jdbc(); j.failUpdate.put("INSERT medico", error);
            assertSame(error, assertThrows(SQLException.class, () -> j.repo().crea(7, "N", "C", "e@x.invalid", "hash", "S", "O")));
            /* L'INSERT UTENTE della stessa transazione viene annullato, mai confermato. */
            assertTrue(j.events.indexOf("INSERT utente") < j.events.indexOf("rollback")); assertFalse(j.events.contains("commit"));
        }
    }

    @Test void linkAndUpdateRulesAreCheckedUnderLock() {
        var j = new Jdbc(); j.utenti.put(9, "OPERATORE_DESK");
        assertEquals(MedicoRepository.RUOLO_GESTIONALE, assertThrows(ConflittoMedicoException.class, () -> j.repo().collega(7, 9, "S", "O")).getMessage());
        var k = new Jdbc(); k.medici.put(9, "INATTIVO");
        assertEquals(MedicoRepository.PROFILO_PRESENTE, assertThrows(ConflittoMedicoException.class, () -> k.repo().collega(7, 9, "S", "O")).getMessage());
        for (String anomalo : List.of("attivo", "Attivo", "", "SOSPESO")) {
            var a = new Jdbc(); a.medici.put(9, anomalo);
            assertEquals(MedicoRepository.STATO_NON_VALIDO, assertThrows(ConflittoMedicoException.class, () -> a.repo().aggiornaDatiProfessionali(7, 9, "S", "O")).getMessage());
            assertEquals(MedicoRepository.STATO_NON_VALIDO, assertThrows(ConflittoMedicoException.class, () -> a.repo().aggiornaDatiPersonali(7, 9, "N", "C", "e@x.invalid")).getMessage());
            assertFalse(a.events.stream().anyMatch(e -> e.startsWith("UPDATE")));
        }
        var g = new Jdbc(); g.utenti.put(9, "AMMINISTRATORE"); g.medici.put(9, "ATTIVO");
        assertEquals(MedicoRepository.DATI_PERSONALI_GESTIONALE, assertThrows(ConflittoMedicoException.class, () -> g.repo().aggiornaDatiPersonali(7, 9, "N", "C", "e@x.invalid")).getMessage());
        assertFalse(g.events.contains("UPDATE utente"));
        var m = new Jdbc(); m.utenti.put(9, null);
        assertThrows(RisorsaNonTrovataException.class, () -> m.repo().aggiornaDatiProfessionali(7, 9, "S", "O"));
    }
    @Test void f6UsesOrderedLocksPlainBookingReadAndOnlyStateUpdate() throws Exception {
        var j = new Jdbc() {
            @Override ResultSet query(String sql, Map<Integer,Object> args) {
                if ((sql.startsWith("SELECT pr.stato") || sql.startsWith("SELECT 1 FROM prenotazione"))) { events.add(sql + " " + args.values()); return rows(List.of()); }
                return super.query(sql,args);
            }
        };
        j.medici.put(9,"ATTIVO");
        j.repo().cambiaStatoMedico(7,9,"INATTIVO");
        assertEquals(List.of(UTENTE_LOCK.replace(" FOR UPDATE", "") + " [7]",
                UTENTE_LOCK.replace(" FOR UPDATE", "") + " [9]", MEDICO_LOCK.replace(" FOR UPDATE", "") + " [9]"),j.locks());
        String read=j.events.stream().filter(e->e.startsWith("SELECT pr.stato")).findFirst().orElseThrow();
        assertFalse(read.contains("FOR UPDATE")); assertFalse(read.contains("data_ora")); assertTrue(read.endsWith("[9, 9]"));
        assertEquals(1,j.events.stream().filter(e->e.equals("UPDATE medico")).count());
        assertTrue(j.events.contains("commit"));
    }
    @Test void f6IdempotentChecksLocksWithoutWritingAndSqlFailureRollsBack() throws Exception {
        var j=new Jdbc();j.medici.put(9,"ATTIVO");j.repo().cambiaStatoMedico(7,9,"ATTIVO");
        assertEquals(3,j.locks().size());assertFalse(j.events.contains("UPDATE medico"));assertTrue(j.events.contains("commit"));
        var failing=new Jdbc() {
            @Override ResultSet query(String sql,Map<Integer,Object> args) {
                if((sql.startsWith("SELECT pr.stato") || sql.startsWith("SELECT 1 FROM prenotazione"))) return rows(List.of());return super.query(sql,args);
            }
        };
        failing.medici.put(9,"ATTIVO");var error=new SQLException("synthetic failure");failing.failUpdate.put("UPDATE medico",error);
        assertSame(error,assertThrows(SQLException.class,()->failing.repo().cambiaStatoMedico(7,9,"INATTIVO")));
        assertTrue(failing.events.contains("rollback"));assertFalse(failing.events.contains("commit"));assertTrue(failing.events.contains("release"));
    }
}
