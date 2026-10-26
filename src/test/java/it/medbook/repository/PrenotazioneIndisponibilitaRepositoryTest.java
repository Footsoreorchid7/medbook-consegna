package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.exception.ConflittoPrenotazioneException;
import it.medbook.exception.RisorsaNonTrovataException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC simulato: verifica il protocollo del Repository senza server né credenziali. */
class PrenotazioneIndisponibilitaRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 15, 8, 0);

    @Test
    void locksAllSlotsThenBookingsInIdOrderReadsClockOnceAndUpdatesOnlyFlag() throws SQLException {
        JdbcFixture f = new JdbcFixture();
        var response = f.repository().segnalaIndisponibilitaAtomically(7, List.of(2, 1));
        assertEquals(List.of("S10", "S20", "P1", "P2", "CLOCK", "U1", "U2", "COMMIT", "AUTO:true"), f.events);
        assertEquals(Set.of(1, 2), f.committed);
        assertEquals(List.of(2, 1), response.getIdPrenotazioni());
        assertTrue(response.isIndisponibilitaSegnalata());
        assertTrue(f.closed);
    }

    @Test
    void alreadyPendingRemainsIdempotentButConditionsAreCheckedAgain() throws SQLException {
        JdbcFixture f = new JdbcFixture(); f.committed.add(1); f.committed.add(2);
        f.repository().segnalaIndisponibilitaAtomically(7, List.of(1, 2));
        assertFalse(f.events.stream().anyMatch(event -> event.startsWith("U")));
        assertEquals(1, f.events.stream().filter("CLOCK"::equals).count());
        f.events.clear(); f.expired = true;
        assertThrows(ConflittoPrenotazioneException.class, () -> f.repository().segnalaIndisponibilitaAtomically(7, List.of(1, 2)));
        assertEquals(Set.of(1, 2), f.committed);
        assertTrue(f.events.contains("ROLLBACK"));
    }

    @Test
    void failureDuringSecondUpdateRollsBackFirstUpdateAndRestoresAutocommit() {
        JdbcFixture f = new JdbcFixture(); f.failSecondUpdate = true;
        assertThrows(SQLException.class, () -> f.repository().segnalaIndisponibilitaAtomically(7, List.of(1, 2)));
        assertTrue(f.events.contains("U1")); assertTrue(f.events.contains("ROLLBACK"));
        assertEquals("AUTO:true", f.events.getLast());
        assertTrue(f.committed.isEmpty()); assertTrue(f.closed);
    }

    @Test
    void invalidMemberOrDoctorPreventsEntireSelection() {
        JdbcFixture f = new JdbcFixture(); f.secondState = "RIFIUTATA";
        assertThrows(ConflittoPrenotazioneException.class, () -> f.repository().segnalaIndisponibilitaAtomically(7, List.of(1, 2)));
        assertTrue(f.committed.isEmpty());
        f.secondState = "RICHIESTA";
        assertThrows(RisorsaNonTrovataException.class, () -> f.repository().segnalaIndisponibilitaAtomically(8, List.of(1, 2)));
        assertTrue(f.committed.isEmpty());
    }

    private static class JdbcFixture {
        final List<String> events = new ArrayList<>();
        final Set<Integer> committed = new HashSet<>();
        Set<Integer> pending = new HashSet<>();
        boolean failSecondUpdate, expired, closed;
        String secondState = "RICHIESTA";

        PrenotazioneRepository repository() {
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { events.add("CLOCK"); return NOW.toInstant(ZoneOffset.UTC); }
            };
            return new PrenotazioneRepository(new ConnectionFactory(it.medbook.config.TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() {
                    pending = new HashSet<>(committed);
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "getAutoCommit" -> true;
                            case "setAutoCommit" -> { if (Boolean.TRUE.equals(args[0])) { events.add("AUTO:true"); } yield null; }
                            case "prepareStatement" -> statement((String) args[0]);
                            case "commit" -> { committed.clear(); committed.addAll(pending); events.add("COMMIT"); yield null; }
                            case "rollback" -> { pending = new HashSet<>(committed); events.add("ROLLBACK"); yield null; }
                            case "close" -> { closed = true; yield null; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
                }
            }, clock);
        }

        PreparedStatement statement(String sql) {
            Map<Integer, Object> parameters = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("set")) { parameters.put((Integer) args[0], args[1]); return null; }
                if (method.getName().equals("close")) { return null; }
                int id = (Integer) parameters.get(1);
                if (method.getName().equals("executeUpdate")) {
                    assertTrue(sql.contains("SET indisponibilita_segnalata = TRUE"));
                    assertFalse(sql.contains("SET stato"));
                    events.add("U" + id);
                    if (failSecondUpdate && id == 2) { throw new SQLException("INSERT/UPDATE simulato fallito"); }
                    pending.add(id); return 1;
                }
                if (!method.getName().equals("executeQuery")) { throw new UnsupportedOperationException(method.getName()); }
                Map<String, Object> row = new HashMap<>();
                if (sql.contains("FROM medico")) { row.put("id_utente", 7); }
                else if (sql.contains("FROM disponibilita")) {
                    events.add("S" + id); row.put("id_medico", 7);
                    row.put("data_ora_inizio", expired ? NOW : NOW.plusHours(1));
                } else if (sql.contains("FOR UPDATE")) {
                    events.add("P" + id);
                    row.put("id_disponibilita", id == 1 ? 20 : 10);
                    row.put("stato", id == 1 ? "RICHIESTA" : secondState);
                    row.put("indisponibilita_segnalata", pending.contains(id));
                } else { row.put("id_disponibilita", id == 1 ? 20 : 10); }
                boolean[] read = {false};
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (p, m, a) -> {
                    if (m.getName().equals("next")) { boolean next = !read[0]; read[0] = true; return next; }
                    if (m.getName().equals("close")) { return null; }
                    Object value = a[0] instanceof Integer ? row.values().iterator().next() : row.get(a[0]);
                    return switch (m.getName()) {
                        case "getInt" -> value;
                        case "getString", "getObject", "getBoolean" -> value;
                        default -> throw new UnsupportedOperationException(m.getName());
                    };
                });
            });
        }
    }
}
