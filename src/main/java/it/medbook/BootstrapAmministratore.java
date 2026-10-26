package it.medbook;

import it.medbook.config.ConnectionFactory;
import it.medbook.repository.UtenteRepository;
import it.medbook.security.PasswordHasher;
import it.medbook.service.BootstrapService;
import java.io.Console;
import java.time.Clock;
import java.util.function.Supplier;

/*
 * Comando locale per il primo amministratore, separato da Main e senza HTTP. Richiede terminale
 * interattivo; credenziali solo da System.console, mai da argomenti, ambiente o file. Senza console
 * termina prima di leggere la configurazione database. Gli override database sono quelli della
 * configurazione comune.
 */
public final class BootstrapAmministratore {
    private BootstrapAmministratore() { }

    public static void main(String[] args) {
        System.exit(esegui(args, System.console(), () -> new BootstrapService(
                new UtenteRepository(new ConnectionFactory(), Clock.systemDefaultZone()), new PasswordHasher())));
    }

    /* Il servizio viene costruito solo dopo i controlli su console e argomenti; un rifiuto non accede al database. */
    static int esegui(String[] args, Console console, Supplier<BootstrapService> servizio) {
        if (args != null && args.length > 0) {
            System.err.println("Il comando non accetta argomenti: i dati si inseriscono soltanto nella console. Nessuna modifica.");
            return BootstrapService.INPUT_NON_VALIDO;
        }
        if (!interattiva(console)) {
            System.err.println("Console interattiva richiesta: avviare il comando da un terminale. Nessuna modifica.");
            return BootstrapService.INPUT_NON_VALIDO;
        }
        BootstrapService service;
        try { service = servizio.get(); }
        catch (RuntimeException e) {
            console.writer().println("Configurazione del database non disponibile (application.properties e MEDBOOK_DB_PASSWORD). Nessuna modifica.");
            console.writer().flush();
            return BootstrapService.ERRORE;
        }
        return service.esegui(terminale(console));
    }

    /*
     * Java 21 espone console solo con input e output terminale. Runtime più nuovi possono esporre
     * isTerminal anche con redirezioni: viene rispettato quando presente.
     */
    static boolean interattiva(Console console) {
        if (console == null) { return false; }
        try { return (Boolean) Console.class.getMethod("isTerminal").invoke(console); }
        catch (NoSuchMethodException e) { return true; }
        catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private static BootstrapService.Terminale terminale(Console console) {
        return new BootstrapService.Terminale() {
            @Override public String leggiRiga(String richiesta) { return console.readLine("%s", richiesta); }
            @Override public char[] leggiPassword(String richiesta) { return console.readPassword("%s", richiesta); }
            @Override public void scrivi(String messaggio) { console.writer().println(messaggio); console.writer().flush(); }
        };
    }
}
