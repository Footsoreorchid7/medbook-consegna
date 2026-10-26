package it.medbook;

import it.medbook.service.BootstrapService;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/* Ingresso del comando interattivo: console, argomenti e assenza di endpoint HTTP. */
class BootstrapAmministratoreTest {
    static String stderr(Runnable r) {
        PrintStream original = System.err; ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try { r.run(); } finally { System.setErr(original); }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test void withoutConsoleOrWithArgumentsItExitsWithCodeTwoBeforeTouchingTheDatabase() {
        int[] esito = new int[1];
        String err = stderr(() -> esito[0] = BootstrapAmministratore.esegui(new String[0], null,
                () -> { throw new AssertionError("nessuna configurazione o connessione senza console"); }));
        assertEquals(BootstrapService.INPUT_NON_VALIDO, esito[0]); assertTrue(err.contains("Console interattiva richiesta"));
        for (String[] args : new String[][]{{"Password-segreta-123"}, {"--password", "x"}, {""}}) {
            err = stderr(() -> esito[0] = BootstrapAmministratore.esegui(args, null,
                    () -> { throw new AssertionError("gli argomenti sono rifiutati prima di tutto"); }));
            assertEquals(BootstrapService.INPUT_NON_VALIDO, esito[0]);
            assertFalse(err.contains("Password-segreta-123"), "l'argomento non viene ripetuto");
        }
        assertFalse(BootstrapAmministratore.interattiva(null));
    }

    @Test void noHttpEndpointSessionOrRoutingIsAdded() throws Exception {
        String main = Files.readString(Path.of("src/main/java/it/medbook/Main.java"));
        assertFalse(main.contains("Bootstrap"), "Main non avvia né espone il bootstrap");
        Matcher contesti = Pattern.compile("createContext\\(\"([^\"]+)\"").matcher(main);
        var percorsi = new java.util.ArrayList<String>(); while (contesti.find()) { percorsi.add(contesti.group(1)); }
        assertEquals(java.util.List.of("/api/auth", "/api/auth/register", "/api/prestazioni", "/api/medici", "/api/pazienti", "/api/utenti", "/api/stanze",
                "/api/disponibilita", "/api/prenotazioni", "/api/rimborsi"), percorsi);
        for (String file : new String[]{"src/main/java/it/medbook/BootstrapAmministratore.java", "src/main/java/it/medbook/service/BootstrapService.java"}) {
            String sorgente = Files.readString(Path.of(file));
            for (String vietato : new String[]{"com.sun.net.httpserver", "HttpServer", "SessionStore", "getenv", "System.in", "Properties"}) {
                assertFalse(sorgente.contains(vietato), file + " non deve usare " + vietato);
            }
        }
        for (String file : new String[]{"src/main/java/it/medbook/security/PoliticaAccesso.java", "src/main/java/it/medbook/controller/UtenteController.java",
                "src/main/java/it/medbook/controller/AutenticazioneController.java", "src/main/java/it/medbook/controller/RegistrazioneController.java"}) {
            assertFalse(Files.readString(Path.of(file)).toLowerCase(java.util.Locale.ROOT).contains("bootstrap"), file);
        }
        for (var c : BootstrapService.class.getConstructors()) {
            for (var tipo : c.getParameterTypes()) { assertNotEquals(it.medbook.security.SessionStore.class, tipo); }
        }
    }

    @Test void mavenExecutionInheritsTheTerminalWithoutChangingTheDefaultOne() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher execution = Pattern.compile("<execution>\\s*(?:<!--.*?-->\\s*)?<id>bootstrap-amministratore</id>(.*?)</execution>", Pattern.DOTALL).matcher(pom);
        assertTrue(execution.find());
        String config = execution.group(1);
        assertTrue(config.contains("<inheritIo>true</inheritIo>"));
        assertTrue(config.contains("<argument>it.medbook.BootstrapAmministratore</argument>"));
        assertFalse(config.contains("<goals>") || config.contains("<phase>"), "mai eseguito dal ciclo di vita di Maven");
        String predefinita = pom.substring(0, pom.indexOf("<executions>"));
        assertTrue(predefinita.contains("<argument>it.medbook.Main</argument>")); assertFalse(predefinita.contains("inheritIo"));
        assertEquals(1, pom.split("<inheritIo>", -1).length - 1);
    }
}
