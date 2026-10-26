package it.medbook.repository;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC simulato: non apre connessioni e non modifica dati. */
class SegnalazioniPazienteTest {
    @Test void patientComesFromBookingWithoutAdditionalPersonalOrClinicalData() throws Exception {
        List<String> queries = new ArrayList<>();
        int[] closed = {0}, index = {-1};
        ResultSet rows = proxy(ResultSet.class, (m,a) -> switch(m) {
            case "next" -> ++index[0] < 2;
            case "close" -> { closed[0]++; yield null; }
            case "getInt" -> switch((String)a[0]) {
                case "id_prenotazione" -> 11 + index[0];
                case "id_paziente" -> 9 + index[0];
                default -> 5;
            };
            case "getString" -> switch((String)a[0]) {
                case "stato" -> index[0] == 0 ? "RICHIESTA" : "CONFERMATA";
                case "nome_paziente" -> index[0] == 0 ? "Paola" : "Anna";
                case "cognome_paziente" -> "Prova";
                default -> "Sintetico";
            };
            case "getBoolean" -> true;
            case "getObject" -> ((Class<?>)a[1]) == LocalDateTime.class
                ? LocalDateTime.of(2026,10,28,8,0)
                : ("id_medico_effettivo".equals(a[0]) && index[0] == 0 ? null : 5);
            default -> throw new AssertionError(m);
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (m,a) -> switch(m) {
            case "executeQuery" -> rows;
            case "close" -> { closed[0]++; yield null; }
            default -> throw new AssertionError(m);
        });
        Connection connection = proxy(Connection.class, (m,a) -> switch(m) {
            case "prepareStatement" -> { queries.add((String)a[0]); yield statement; }
            case "close" -> { closed[0]++; yield null; }
            default -> throw new AssertionError(m);
        });
        var factory = new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public Connection getConnection() { return connection; }
        };
        var result = new PrenotazioneRepository(factory).findSegnalazioniPendenti();
        assertEquals(List.of(11,12), result.stream().map(r -> r.getIdPrenotazione()).toList());
        assertEquals(List.of(9,10), result.stream().map(r -> r.getIdPaziente()).toList());
        assertEquals(List.of("Paola","Anna"), result.stream().map(r -> r.getNomePaziente()).toList());
        assertEquals("Prova",result.get(0).getCognomePaziente());
        assertNull(result.get(0).getIdMedicoEffettivo());
        assertEquals(5,result.get(1).getIdMedicoEffettivo());
        String sql = queries.get(0);
        assertTrue(sql.contains("JOIN utente up ON up.id_utente = pr.id_paziente"));
        assertTrue(sql.contains("ORDER BY d.data_ora_inizio, pr.id_prenotazione"));
        for (String forbidden : List.of("email", "telefono", "codice_fiscale", "referto", "password")) assertFalse(sql.contains(forbidden));
        assertEquals(3,closed[0]);
    }
    interface Call { Object invoke(String method,Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a));
    }
}
