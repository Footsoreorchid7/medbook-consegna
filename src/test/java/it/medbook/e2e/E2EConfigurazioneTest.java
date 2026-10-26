package it.medbook.e2e;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/* Configurazione, orologio manuale e fusione degli header del client senza server o database. */
class E2EConfigurazioneTest {
    static Map<String, String> completa() {
        Map<String, String> env = new HashMap<>();
        env.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:33861/medbook_test_e2e_template_nuovo");
        env.put("MEDBOOK_E2E_SERVER_UUID", "3db739be-c85e-11f1-9248-9a17c6349ba3");
        env.put("MEDBOOK_E2E_LAUNCHER_USERNAME", "e2elauncher"); env.put("MEDBOOK_E2E_LAUNCHER_PASSWORD", "x");
        env.put("MEDBOOK_E2E_USERNAME", "e2eruntime"); env.put("MEDBOOK_E2E_PASSWORD", "y");
        return env;
    }

    @Test void absentEnvironmentIsSkipInOptionalModeAndFailureInMandatoryMode() {
        assertEquals(new E2EConfigurazione.Assente(false), E2EConfigurazione.leggi(Map.of()));
        assertNull(E2EConfigurazione.perSetup(E2EConfigurazione.leggi(Map.of())), "facoltativa: nessun setup");
        var obbligatoria = E2EConfigurazione.leggi(Map.of("MEDBOOK_E2E_MODE", "obbligatoria"));
        assertEquals(new E2EConfigurazione.Assente(true), obbligatoria);
        assertThrows(IllegalStateException.class, () -> E2EConfigurazione.perSetup(obbligatoria));
    }

    @Test void partialMalformedOrIncompatibleConfigurationIsAnErrorNeverASkip() {
        Map<String, String> parziale = completa(); parziale.remove("MEDBOOK_E2E_PASSWORD");
        Map<String, String> applicativo = completa(); applicativo.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:33861/medbook");
        Map<String, String> condiviso = completa(); condiviso.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:33861/medbook_test");
        Map<String, String> remoto = completa(); remoto.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://10.0.0.5:33861/medbook_test_e2e_template_nuovo");
        Map<String, String> porta = completa(); porta.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:3306/medbook_test_e2e_template_nuovo");
        Map<String, String> parametri = completa(); parametri.put("MEDBOOK_E2E_TEMPLATE_URL", "jdbc:mysql://127.0.0.1:33861/medbook_test_e2e_template_nuovo?allowMultiQueries=true");
        Map<String, String> uuid = completa(); uuid.put("MEDBOOK_E2E_SERVER_UUID", "non-un-uuid");
        Map<String, String> stessoAccount = completa(); stessoAccount.put("MEDBOOK_E2E_USERNAME", "e2elauncher");
        Map<String, String> modo = completa(); modo.put("MEDBOOK_E2E_MODE", "forse");
        for (Map<String, String> env : new Map[]{parziale, applicativo, condiviso, remoto, porta, parametri, uuid, stessoAccount, modo}) {
            var esito = E2EConfigurazione.leggi(env);
            assertInstanceOf(E2EConfigurazione.Errata.class, esito, env.toString());
            assertThrows(IllegalStateException.class, () -> E2EConfigurazione.perSetup(esito));
        }
        Map<String, String> parzialeObbligatoria = new HashMap<>(parziale); parzialeObbligatoria.put("MEDBOOK_E2E_MODE", "obbligatoria");
        assertInstanceOf(E2EConfigurazione.Errata.class, E2EConfigurazione.leggi(parzialeObbligatoria));
    }

    @Test void explicitlyEmptyVariablesAreAnErrorNotAnAbsentEnvironment() {
        Map<String, String> tutteVuote = new HashMap<>();
        E2EConfigurazione.VARIABILI.forEach(v -> tutteVuote.put(v, ""));
        Map<String, String> unaVuota = completa(); unaVuota.put("MEDBOOK_E2E_SERVER_UUID", "");
        Map<String, String> spazi = completa(); spazi.put("MEDBOOK_E2E_PASSWORD", "  ");
        Map<String, String> soloUnaVuota = new HashMap<>(Map.of("MEDBOOK_E2E_TEMPLATE_URL", ""));
        for (Map<String, String> env : List.of(tutteVuote, unaVuota, spazi, soloUnaVuota)) {
            var esito = assertInstanceOf(E2EConfigurazione.Errata.class, E2EConfigurazione.leggi(env), env.keySet().toString());
            assertTrue(esito.motivo().contains("vuote"), esito.motivo());
            assertThrows(IllegalStateException.class, () -> E2EConfigurazione.perSetup(esito));
            Map<String, String> obbligatoria = new HashMap<>(env); obbligatoria.put("MEDBOOK_E2E_MODE", "obbligatoria");
            assertInstanceOf(E2EConfigurazione.Errata.class, E2EConfigurazione.leggi(obbligatoria));
        }
        assertInstanceOf(E2EConfigurazione.Errata.class, E2EConfigurazione.leggi(Map.of("MEDBOOK_E2E_MODE", "")), "modalità vuota");
        Map<String, String> modoVuoto = completa(); modoVuoto.put("MEDBOOK_E2E_MODE", "");
        assertInstanceOf(E2EConfigurazione.Errata.class, E2EConfigurazione.leggi(modoVuoto));
        /* Solo variabili di connessione davvero assenti consentono lo skip facoltativo. */
        assertEquals(new E2EConfigurazione.Assente(false), E2EConfigurazione.leggi(Map.of("PATH", "/usr/bin", "MEDBOOK_TEST_DB_URL", "")));
    }

    @Test void runCatalogPatternAcceptsOnlyGeneratedNames() {
        assertTrue(E2ELauncher.CATALOGO.matcher("medbook_test_e2e_r20310110080000_0123456789abcdef").matches());
        for (String nome : new String[]{"medbook", "medbook_test", "medbook_test_bootstrap", "medbook_test_e2e_template_nuovo",
                "medbook_test_e2e_r20310110080000_0123abcd", "medbook_test_e2e_r20310110080000_0123456789ABCDEF",
                "medbook_test_e2e_r2031011008000_0123456789abcdef", "medbook_test_e2e_r20310110080000_0123456789abcdef`; DROP DATABASE medbook; --"}) {
            assertFalse(E2ELauncher.CATALOGO.matcher(nome).matches(), nome);
        }
    }

    @Test void completeConfigurationKeepsPasswordsOutOfToString() {
        var c = assertInstanceOf(E2EConfigurazione.Completa.class, E2EConfigurazione.leggi(completa()));
        assertEquals(33861, c.porta()); assertEquals("medbook_test_e2e_template_nuovo", c.template());
        assertEquals("jdbc:mysql://127.0.0.1:33861/medbook_test_e2e_r20310110080000_0123abcd", c.urlCatalogo("medbook_test_e2e_r20310110080000_0123abcd"));
        assertFalse(c.toString().contains("x,") || c.toString().contains("y]") || c.toString().contains("password"));
        Map<String, String> obbligatoria = completa(); obbligatoria.put("MEDBOOK_E2E_MODE", "obbligatoria");
        assertTrue(((E2EConfigurazione.Completa) E2EConfigurazione.leggi(obbligatoria)).obbligatoria());
    }

    @Test void manualClockWithZoneSharesTheInstantAndKeepsTheZone() {
        ManualClock clock = new ManualClock(Instant.parse("2031-01-10T08:00:00Z"));
        ManualClock roma = clock.withZone(ZoneId.of("Europe/Rome"));
        assertSame(clock, clock.withZone(ZoneOffset.UTC));
        assertEquals(ZoneId.of("Europe/Rome"), roma.getZone());
        assertTrue(clock.condivideIstante(roma));
        clock.advance(Duration.ofHours(2));
        assertEquals(clock.instant(), roma.instant(), "una copia di zona non è un secondo orologio");
        roma.set(Instant.parse("2031-02-01T00:00:00Z"));
        assertEquals(Instant.parse("2031-02-01T00:00:00Z"), clock.instant());
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> clock.withZone(null));
    }

    @Test void headerOverridesAreCaseInsensitiveAndEmptyValuesRemoveDefaults() {
        var h = E2EClient.intestazioni("http://127.0.0.1:1", "application/json", "c=v", "tok",
                Map.of("origin", "http://altro.example", "x-csrf-token", "", "CONTENT-type", "application/pdf"));
        assertEquals(3, h.size(), h.toString());
        assertEquals("http://altro.example", h.get("Origin"));
        assertEquals("application/pdf", h.get("content-type"));
        assertNull(h.get("X-CSRF-Token"));
        assertEquals("c=v", h.get("cookie"));
    }
}
