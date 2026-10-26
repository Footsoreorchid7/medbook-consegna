package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC simulato: nessuna connessione a database applicativi o di test. */
class AgendaRefertoPresenteTest {
    @Test void mapsPresenceAndAbsenceWithoutExposingDocumentMetadataOrChangingOrder() throws Exception {
        List<String> sql = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        int[] closed = {0};
        Connection connection = proxy(Connection.class, (method,args) -> switch(method) {
            case "prepareStatement" -> {
                String query=(String)args[0]; sql.add(query);
                yield proxy(PreparedStatement.class,(m,a) -> switch(m) {
                    case "setInt", "setObject" -> { params.add(a[1]); yield null; }
                    case "close" -> { closed[0]++; yield null; }
                    case "executeQuery" -> {
                        boolean agenda=query.contains("FROM prenotazione pr");
                        int[] row={-1};
                        yield proxy(ResultSet.class,(r,v) -> switch(r) {
                            case "next" -> ++row[0] < (agenda?2:1);
                            case "close" -> { closed[0]++; yield null; }
                            case "getBoolean" -> "referto_presente".equals(v[0]) && row[0]==0;
                            case "getString" -> switch((String)v[0]) {
                                case "stato" -> "EFFETTUATA";
                                case "nome_paziente" -> "Nome";
                                case "cognome_paziente" -> "Sintetico";
                                default -> null;
                            };
                            case "getObject" -> switch((String)v[0]) {
                                case "id_prenotazione" -> row[0]==0?12:27;
                                case "id_medico_effettivo", "id_medico_titolare" -> 5;
                                case "gestita_da" -> 3;
                                case "id_paziente" -> 9;
                                case "id_disponibilita" -> row[0]+10;
                                case "data_ora_gestione", "data_ora_inizio", "data_ora_fine" -> LocalDateTime.of(2026,10,20,9,0);
                                default -> null;
                            };
                            default -> throw new AssertionError(r);
                        });
                    }
                    default -> throw new AssertionError(m);
                });
            }
            case "close" -> { closed[0]++; yield null; }
            default -> throw new AssertionError(method);
        });
        var factory=new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() { return connection; }
        };
        var rows=new PrenotazioneRepository(factory,Clock.systemUTC()).findAgenda(5,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31));
        assertEquals(List.of(12,27),rows.stream().map(r->r.getIdPrenotazione()).toList());
        assertTrue(rows.get(0).isRefertoPresente()); assertFalse(rows.get(1).isRefertoPresente());
        assertEquals(it.medbook.model.StatoPrenotazione.EFFETTUATA,rows.get(0).getStato());
        assertTrue(sql.get(1).contains("EXISTS (SELECT 1 FROM referto r WHERE r.id_prenotazione = pr.id_prenotazione) AS referto_presente"));
        assertFalse(sql.get(1).contains("percorso_file")); assertFalse(sql.get(1).contains("nome_file"));
        assertEquals(List.of(5,LocalDateTime.of(2026,10,1,0,0),LocalDateTime.of(2026,11,1,0,0),5,5,5),params);
        assertEquals(5,closed[0]);
    }
    interface Call { Object invoke(String method,Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a));
    }
}
