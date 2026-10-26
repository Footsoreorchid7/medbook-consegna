package it.medbook.e2e;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Configurazione letta solo da MEDBOOK_E2E_*, mai da proprietà applicative. Assenza completa
 * consente skip solo in modalità facoltativa; configurazione parziale, vuota o errata fallisce
 * sempre. URL loopback su porta dedicata non 3306 e template ammesso; UUID obbligatorio. Launcher
 * con DDL sui soli cataloghi di esecuzione e SELECT sul template, runtime con soli DML sui
 * cataloghi di esecuzione. Lo script scrive variabili in file privati e i test verificano i
 * privilegi effettivi.
 */
public final class E2EConfigurazione {
    public static final String MODE = "MEDBOOK_E2E_MODE";
    public static final List<String> VARIABILI = List.of("MEDBOOK_E2E_TEMPLATE_URL", "MEDBOOK_E2E_SERVER_UUID",
            "MEDBOOK_E2E_LAUNCHER_USERNAME", "MEDBOOK_E2E_LAUNCHER_PASSWORD", "MEDBOOK_E2E_USERNAME", "MEDBOOK_E2E_PASSWORD");
    static final Pattern URL = Pattern.compile(
            "jdbc:mysql://(127\\.0\\.0\\.1|localhost):([0-9]{1,5})/(medbook_test_e2e_template_[a-z0-9_]{1,30})");
    static final Pattern UUID_SERVER = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public sealed interface Esito permits Assente, Errata, Completa { boolean obbligatoria(); }
    public record Assente(boolean obbligatoria) implements Esito { }
    public record Errata(boolean obbligatoria, String motivo) implements Esito { }
    /** Le password non compaiono in toString. */
    public record Completa(boolean obbligatoria, String host, int porta, String template, String serverUuid,
                           String launcherUser, String launcherPassword, String runtimeUser, String runtimePassword) implements Esito {
        public String urlCatalogo(String catalogo) { return "jdbc:mysql://" + host + ":" + porta + "/" + catalogo; }
        @Override public String toString() {
            return "Completa[" + host + ":" + porta + "/" + template + ", uuid=" + serverUuid + ", obbligatoria=" + obbligatoria + "]";
        }
    }

    private E2EConfigurazione() { }

    public static Esito daAmbiente() { return leggi(System.getenv()); }

    public static Esito leggi(Map<String, String> env) {
        Objects.requireNonNull(env);
        String mode = env.get(MODE);
        boolean obbligatoria;
        if (mode == null || mode.equals("facoltativa")) { obbligatoria = false; }
        else if (mode.equals("obbligatoria")) { obbligatoria = true; }
        else { return new Errata(false, MODE + " deve valere 'facoltativa' oppure 'obbligatoria' (vuoto non ammesso)."); }

        /* Assenza significa nessuna variabile di connessione definita: un valore vuoto è un errore. */
        List<String> definite = VARIABILI.stream().filter(env::containsKey).toList();
        if (definite.isEmpty()) { return new Assente(obbligatoria); }
        List<String> vuote = definite.stream().filter(v -> env.get(v) == null || env.get(v).isBlank()).toList();
        if (!vuote.isEmpty()) { return new Errata(obbligatoria, "Variabili E2E definite ma vuote: " + vuote); }
        if (definite.size() < VARIABILI.size()) {
            List<String> mancanti = VARIABILI.stream().filter(v -> !env.containsKey(v)).toList();
            return new Errata(obbligatoria, "Configurazione E2E parziale; mancano: " + mancanti);
        }
        Matcher url = URL.matcher(env.get("MEDBOOK_E2E_TEMPLATE_URL"));
        if (!url.matches()) {
            return new Errata(obbligatoria, "MEDBOOK_E2E_TEMPLATE_URL non ammesso: richiesti loopback, porta dedicata, "
                    + "catalogo medbook_test_e2e_template_<suffisso>, nessun parametro.");
        }
        int porta = Integer.parseInt(url.group(2));
        if (porta < 1024 || porta > 65535 || porta == 3306) {
            return new Errata(obbligatoria, "Porta E2E non ammessa: 1024-65535, diversa da 3306.");
        }
        String uuid = env.get("MEDBOOK_E2E_SERVER_UUID");
        if (!UUID_SERVER.matcher(uuid).matches()) { return new Errata(obbligatoria, "MEDBOOK_E2E_SERVER_UUID malformato."); }
        String launcher = env.get("MEDBOOK_E2E_LAUNCHER_USERNAME"), runtime = env.get("MEDBOOK_E2E_USERNAME");
        if (launcher.equals(runtime)) {
            return new Errata(obbligatoria, "Launcher e runtime devono usare account distinti (privilegi minimi).");
        }
        return new Completa(obbligatoria, url.group(1), porta, url.group(3), uuid, launcher,
                env.get("MEDBOOK_E2E_LAUNCHER_PASSWORD"), runtime, env.get("MEDBOOK_E2E_PASSWORD"));
    }

    /* Configurazione per BeforeAll: null solo se assente e facoltativa; altrimenti completa o errore esplicito. */
    public static Completa perSetup(Esito esito) {
        return switch (esito) {
            case Completa c -> c;
            case Assente a when !a.obbligatoria() -> null;
            case Assente a -> throw new IllegalStateException("Modalità E2E obbligatoria: nessuna variabile MEDBOOK_E2E_* configurata.");
            case Errata e -> throw new IllegalStateException("Configurazione E2E non valida: " + e.motivo());
        };
    }
}
