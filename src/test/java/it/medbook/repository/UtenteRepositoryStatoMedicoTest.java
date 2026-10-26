package it.medbook.repository;
import it.medbook.config.*;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
/* Permesso medico operativo distinto dall'esistenza del profilo medico, senza database. */
class UtenteRepositoryStatoMedicoTest {
    static class Row {
        boolean medico=true,paziente=true;String stato="ATTIVO",ruolo=null;final List<String> sql=new ArrayList<>();
        ResultSet rows() {
            int[] n={0};
            return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
                case "next" -> n[0]++==0;
                case "getBoolean" -> switch((String)a[0]){case "medico" -> medico;case "paziente" -> paziente;default -> throw new SQLException("colonna "+a[0]);};
                case "getString" -> switch((String)a[0]){case "medico_stato" -> stato;case "ruolo_gestionale" -> ruolo;case "nome" -> "N";case "cognome" -> "C";
                    case "email" -> "e@example.invalid";case "password_hash" -> "hash";case "stato_accesso" -> "ATTIVO";default -> throw new SQLException("colonna "+a[0]);};
                // Connector/J rappresenta BIGINT UNSIGNED come BigInteger; account ATTIVO a generazione zero.
                case "getObject" -> switch((String)a[0]){case "generazione_autenticazione" -> java.math.BigInteger.ZERO;default -> throw new SQLException("colonna "+a[0]);};
                case "getInt" -> 7;
                default -> null;
            });
        }
        UtenteRepository repo() {
            return new UtenteRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()){@Override public Connection getConnection(){
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
                    case "prepareStatement" -> {sql.add((String)a[0]);yield Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},
                            (q,n,b)->n.getName().equals("executeQuery")?rows():null);}
                    default -> null;
                });
            }});
        }
    }
    @Test void activeDoctorHasPermissionThroughIdAndEmail()throws Exception {
        var r=new Row();
        assertTrue(r.repo().findById(7).orElseThrow().medico());
        assertTrue(r.repo().findByEmail("e@example.invalid").orElseThrow().identita().medico());
    }
    /* Varianti di ATTIVO ammesse dal CHECK ai_ci restano non canoniche e devono negare il permesso. */
    @ParameterizedTest @ValueSource(strings={"INATTIVO","attivo","Attivo","aTTIVO","inattivo","ATTIVO "," ATTIVO","SOSPESO",""})
    void anyStateOtherThanExactAttivoDeniesOnlyMedico(String stato)throws Exception {
        var r=new Row();r.stato=stato;r.ruolo="AMMINISTRATORE";
        var identity=r.repo().findById(7).orElseThrow();
        assertFalse(identity.medico());assertTrue(identity.paziente());assertEquals(it.medbook.model.RuoloGestionale.AMMINISTRATORE,identity.ruoloGestionale());
        assertFalse(r.repo().findByEmail("e@example.invalid").orElseThrow().identita().medico());
    }
    @Test void nullStateOrMissingProfileNeverGrantMedico()throws Exception {
        var r=new Row();r.stato=null;assertFalse(r.repo().findById(7).orElseThrow().medico());
        r=new Row();r.medico=false;r.stato="ATTIVO";assertFalse(r.repo().findById(7).orElseThrow().medico());
        r=new Row();r.medico=false;r.stato=null;r.paziente=true;var identity=r.repo().findById(7).orElseThrow();assertFalse(identity.medico());assertTrue(identity.paziente());
    }
    @Test void stateIsReadRawAndNeverComparedInSql()throws Exception {
        var r=new Row();r.ruolo="AMMINISTRATORE";r.repo().findById(7);r.repo().findByEmail("e@example.invalid");r.repo().findGestionali();
        assertEquals(3,r.sql.size());
        for(String sql:r.sql) {
            assertTrue(sql.contains("(SELECT m.stato FROM medico m WHERE m.id_utente=u.id_utente) AS medico_stato"),sql);
            assertTrue(sql.contains("EXISTS(SELECT 1 FROM medico m WHERE m.id_utente=u.id_utente) AS medico,"),sql);
            assertFalse(sql.toLowerCase(Locale.ROOT).contains("'attivo'"),sql);
        }
    }
    @ParameterizedTest @ValueSource(strings={"ATTIVO","INATTIVO","attivo","SOSPESO"})
    void uc26ReportsProfileInAnyState(String stato)throws Exception {
        var r=new Row();r.ruolo="AMMINISTRATORE";r.stato=stato;
        var account=r.repo().findGestionali().getFirst();assertTrue(account.medico());assertTrue(account.paziente());
        r=new Row();r.ruolo="OPERATORE_DESK";r.medico=false;r.stato=null;assertFalse(r.repo().findGestionali().getFirst().medico());
    }
}
