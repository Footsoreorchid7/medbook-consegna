package it.medbook.repository;

import it.medbook.config.*;
import it.medbook.exception.AnomaliaDatiAccountException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/* Classificazione di stato accesso, generazione e ruolo senza database. */
class UtenteRepositoryAccessoTest {
    static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    static class Row {
        String stato = "ATTIVO", ruolo = null, hash = "hash"; Object generazione = BigInteger.ZERO; boolean presente = true;
        ResultSet rows() { return rows(""); }
        ResultSet rows(String sql) {
            int[] n = {0};
            /*
             * La ricerca rilegge prima il proprio attore: amministratore attivo canonico,
             * indipendente dalla riga in prova.
             */
            boolean attore = sql.startsWith("SELECT ruolo_gestionale, stato_accesso");
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                case "next" -> presente && n[0]++ == 0;
                case "getBoolean" -> false;
                case "getString" -> switch ((String) a[0]) {
                    case "stato_accesso" -> attore ? "ATTIVO" : stato; case "ruolo_gestionale" -> attore ? "AMMINISTRATORE" : ruolo;
                    case "password_hash" -> hash; case "codice_fiscale" -> null;
                    case "nome" -> "N"; case "cognome" -> "C"; case "medico_stato" -> null; case "email" -> "e@example.invalid";
                    default -> throw new SQLException("colonna " + a[0]);
                };
                case "getObject" -> {
                    if (!"generazione_autenticazione".equals(a[0])) { throw new SQLException("colonna " + a[0]); }
                    yield generazione;
                }
                case "getInt" -> 7;
                default -> null;
            });
        }
        UtenteRepository repo() {
            return new UtenteRepository(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() {
                    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                        case "prepareStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class},
                                (q, n, b) -> n.getName().equals("executeQuery") ? rows((String) a[0]) : null);
                        default -> null;
                    });
                }
            });
        }
    }

    @Test void canonicalStateIsExactAndAnythingElseIsAnomalous() {
        assertEquals("ATTIVO", UtenteRepository.statoAccesso("ATTIVO"));
        assertEquals("DISABILITATO", UtenteRepository.statoAccesso("DISABILITATO"));
        for (String raw : Arrays.asList(null, "", "attivo", "Attivo", "ÀTTIVO", "ATTIVO ", " ATTIVO", "disabilitato", "SOSPESO")) {
            assertNull(UtenteRepository.statoAccesso(raw), String.valueOf(raw));
        }
    }

    @Test void generationDomainIsZeroToLongMaxValue() throws Exception {
        var r = new Row();
        for (Object ok : List.of(BigInteger.ZERO, BigInteger.ONE, MAX, 42L)) {
            r.generazione = ok;
            assertEquals(((Number) ok).longValue(), UtenteRepository.generazione(r.rows()).orElseThrow(), ok.toString());
        }
        for (Object anomalo : Arrays.asList(MAX.add(BigInteger.ONE), BigInteger.TWO.pow(64).subtract(BigInteger.ONE),
                BigInteger.valueOf(-1), -1L, null, "0")) {
            r.generazione = anomalo;
            var rs = r.rows(); assertTrue(rs.next());
            assertTrue(UtenteRepository.generazione(rs).isEmpty(), String.valueOf(anomalo));
        }
    }

    @Test void loginReadNeverThrowsForDataAnomaliesAndReportsEachOne() throws Exception {
        var r = new Row(); r.stato = "attivo"; r.generazione = MAX.add(BigInteger.ONE); r.ruolo = "amministratore";
        var c = r.repo().leggiCredenzialiLogin("e@example.invalid").orElseThrow();
        assertEquals(EnumSet.allOf(UtenteRepository.Anomalia.class), c.anomalie());
        assertNull(c.statoAccesso()); assertTrue(c.generazione().isEmpty()); assertNull(c.identita());
        assertEquals("hash", c.passwordHash()); assertEquals(7, c.idUtente());
        assertFalse(c.toString().contains("hash"), "toString non espone il verificatore");
        r = new Row(); r.stato = "DISABILITATO"; r.generazione = MAX; r.ruolo = "OPERATORE_DESK";
        c = r.repo().leggiCredenzialiLogin("e@example.invalid").orElseThrow();
        assertTrue(c.anomalie().isEmpty()); assertEquals("DISABILITATO", c.statoAccesso());
        assertEquals(Long.MAX_VALUE, c.generazione().getAsLong()); assertNotNull(c.identita());
        r.presente = false; assertTrue(r.repo().leggiCredenzialiLogin("e@example.invalid").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"STATO", "GENERAZIONE", "RUOLO"})
    void sessionReadAndFindByIdThrowTypedAnomaly(String quale) {
        var r = new Row();
        switch (quale) {
            case "STATO" -> r.stato = "Attivo";
            case "GENERAZIONE" -> r.generazione = MAX.add(BigInteger.ONE);
            default -> r.ruolo = "amministratore";
        }
        assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().findSessione(7));
        assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().findById(7));
    }

    @Test void disabledOrMissingAccountHasNoIdentityAndActiveOneCarriesGeneration() throws Exception {
        var r = new Row(); r.stato = "DISABILITATO";
        assertTrue(r.repo().findSessione(7).isEmpty()); assertTrue(r.repo().findById(7).isEmpty());
        r.stato = "ATTIVO"; r.generazione = MAX;
        var s = r.repo().findSessione(7).orElseThrow();
        assertEquals(Long.MAX_VALUE, s.generazione()); assertEquals(7, s.identita().idUtente());
        assertEquals(s.identita(), r.repo().findById(7).orElseThrow());
        r.presente = false; assertTrue(r.repo().findSessione(7).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"ATTIVO", "DISABILITATO"})
    void adminListIncludesValidActiveAndDisabledAccounts(String stato) throws Exception {
        var r = new Row(); r.stato = stato; r.ruolo = "OPERATORE_DESK"; r.generazione = MAX;
        var list = r.repo().findGestionali();
        assertEquals(1, list.size()); assertEquals(7, list.getFirst().idUtente());
        assertEquals(it.medbook.model.RuoloGestionale.OPERATORE_DESK, list.getFirst().ruoloGestionale());
    }
    @ParameterizedTest @ValueSource(strings = {"STATO", "STATO_NULL", "GENERAZIONE_NULL", "GENERAZIONE_OLTRE_MAX", "GENERAZIONE_2_64", "RUOLO"})
    void adminListThrowsTypedAnomalyForEveryAnomalousRow(String quale) {
        for (String stato : List.of("ATTIVO", "DISABILITATO")) {
            var r = new Row(); r.stato = stato; r.ruolo = "AMMINISTRATORE";
            switch (quale) {
                case "STATO" -> r.stato = stato.toLowerCase(Locale.ROOT);
                case "STATO_NULL" -> r.stato = null;
                case "GENERAZIONE_NULL" -> r.generazione = null;
                case "GENERAZIONE_OLTRE_MAX" -> r.generazione = MAX.add(BigInteger.ONE);
                case "GENERAZIONE_2_64" -> r.generazione = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
                default -> r.ruolo = "amministratore";
            }
            var e = assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().findGestionali(), quale + "/" + stato);
            assertEquals("Dati account non coerenti.", e.getMessage());
        }
    }
    @Test void disabledAccountWithAnomalousRoleThrowsInsteadOfEmpty() throws Exception {
        var r = new Row(); r.stato = "DISABILITATO"; r.ruolo = "operatore_desk";
        assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().findSessione(7));
        assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().findById(7));
        r.ruolo = "OPERATORE_DESK";
        assertTrue(r.repo().findSessione(7).isEmpty()); assertTrue(r.repo().findById(7).isEmpty());
        r.ruolo = null;
        assertTrue(r.repo().findSessione(7).isEmpty(), "nessun ruolo gestionale non è un'anomalia");
    }

    @Test void searchPrefixEscapesLikeMetacharactersWithBang() {
        assertEquals("a!%b!_c!!d\\e", UtenteRepository.likeLetterale("a%b_c!d\\e"));
        assertEquals("abc", UtenteRepository.likeLetterale("abc"));
    }
    @ParameterizedTest @ValueSource(strings = {"attivo", "ATTIVO ", "SOSPESO"})
    void searchFailsOnNonCanonicalStateInAResultRow(String stato) {
        var r = new Row(); r.stato = stato;
        assertThrows(AnomaliaDatiAccountException.class, () -> r.repo().cerca(7,
                new UtenteRepository.FiltriRicerca(null, "abc", null, null, null, null, 0, 20)));
    }

    @Test void anomalyIsDistinctFromRealSqlErrorsAndCarriesNoValue() {
        var e = new AnomaliaDatiAccountException();
        assertInstanceOf(SQLException.class, e); assertNull(e.getCause()); assertEquals("Dati account non coerenti.", e.getMessage());
        assertFalse(new SQLException("driver") instanceof AnomaliaDatiAccountException);
    }
}
