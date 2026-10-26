package it.medbook.service;

import it.medbook.exception.EsitoCommitIncertoException;
import it.medbook.repository.UtenteRepository;
import it.medbook.repository.UtenteRepository.BootstrapRifiutatoException;
import it.medbook.security.PasswordHasher;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/*
 * Primo amministratore da terminale locale, senza HTTP o sessione automatica. Nessuna scrittura se
 * esiste un ruolo gestionale, anche disabilitato o non canonico. Password in char[] ripuliti nel
 * finally e mai stampate; messaggi fissi senza SQL, cause, hash o segreti.
 */
public final class BootstrapService {
    public static final int CREATO = 0, ERRORE = 1, INPUT_NON_VALIDO = 2, GIA_INIZIALIZZATO = 3, EMAIL_PRESENTE = 4,
            MUTEX_OCCUPATO = 5, SCHEMA_NON_AGGIORNATO = 6;

    /* Terminale interattivo: null a fine input; leggiPassword non deve mostrare eco. */
    public interface Terminale {
        String leggiRiga(String richiesta);
        char[] leggiPassword(String richiesta);
        void scrivi(String messaggio);
    }

    private final UtenteRepository repository;
    private final PasswordHasher hasher;

    public BootstrapService(UtenteRepository repository, PasswordHasher hasher) {
        this.repository = Objects.requireNonNull(repository);
        this.hasher = Objects.requireNonNull(hasher);
    }

    /* Esegue il dialogo e restituisce il codice di uscita. */
    public int esegui(Terminale terminale) {
        Objects.requireNonNull(terminale);
        try {
            UtenteRepository.DestinazioneDatabase destinazione = repository.destinazione();
            terminale.scrivi("Database di destinazione:");
            terminale.scrivi("  catalogo:    " + destinazione.catalogo());
            terminale.scrivi("  host:        " + destinazione.hostname());
            terminale.scrivi("  porta:       " + destinazione.porta());
            terminale.scrivi("  server_uuid: " + destinazione.serverUuid());
            terminale.scrivi("  versione:    " + destinazione.versione());
            if (!repository.schemaAggiornato()) {
                terminale.scrivi("Schema non aggiornato: migrazione 003 assente o incompleta. Nessuna modifica.");
                return SCHEMA_NON_AGGIORNATO;
            }
            if (repository.contaGestionali() > 0) {
                terminale.scrivi("Sistema già inizializzato: esiste già un account gestionale. Nessuna modifica.");
                return GIA_INIZIALIZZATO;
            }
            String nome, cognome, email;
            try {
                nome = ValidazioneAccount.testo(terminale.leggiRiga("Nome: "), 100);
                cognome = ValidazioneAccount.testo(terminale.leggiRiga("Cognome: "), 100);
                email = ValidazioneAccount.email(terminale.leggiRiga("Email: "));
            } catch (IllegalArgumentException e) {
                terminale.scrivi("Dati non validi. Nessuna modifica.");
                return INPUT_NON_VALIDO;
            }
            String hash;
            char[] password = null, ripetuta = null;
            try {
                password = terminale.leggiPassword("Password (12-1024 caratteri): ");
                ripetuta = terminale.leggiPassword("Ripetere la password: ");
                if (password == null || ripetuta == null || !Arrays.equals(password, ripetuta)) {
                    terminale.scrivi("Le password non coincidono. Nessuna modifica.");
                    return INPUT_NON_VALIDO;
                }
                try { ValidazioneAccount.password(password); }
                catch (IllegalArgumentException e) {
                    terminale.scrivi("Password non valida: da 12 a 1024 caratteri, senza caratteri Unicode malformati. Nessuna modifica.");
                    return INPUT_NON_VALIDO;
                }
                terminale.scrivi("Verrà creato l'account AMMINISTRATORE " + nome + " " + cognome + " <" + email + ">"
                        + " nel catalogo " + destinazione.catalogo() + " del server " + destinazione.serverUuid() + ".");
                String conferma = terminale.leggiRiga("Per confermare digitare il nome del catalogo: ");
                if (!destinazione.catalogo().equals(conferma)) {
                    terminale.scrivi("Conferma non ricevuta. Nessuna modifica.");
                    return INPUT_NON_VALIDO;
                }
                hash = hasher.hash(password);
            } finally {
                if (password != null) { Arrays.fill(password, '\0'); }
                if (ripetuta != null) { Arrays.fill(ripetuta, '\0'); }
            }
            int id = repository.creaPrimoAmministratore(nome, cognome, email, hash, destinazione);
            terminale.scrivi("Amministratore creato (id_utente " + id + "). Accedere con il login dell'applicazione.");
            return CREATO;
        } catch (BootstrapRifiutatoException e) {
            switch (e.motivo()) {
                case GIA_INIZIALIZZATO -> { terminale.scrivi("Sistema già inizializzato: esiste già un account gestionale. Nessuna modifica."); return GIA_INIZIALIZZATO; }
                case EMAIL_PRESENTE -> { terminale.scrivi("Email già presente: nessun account esistente viene promosso. Nessuna modifica."); return EMAIL_PRESENTE; }
                case SCHEMA_NON_AGGIORNATO -> { terminale.scrivi("Schema non aggiornato: migrazione 003 assente o incompleta. Nessuna modifica."); return SCHEMA_NON_AGGIORNATO; }
                default -> { terminale.scrivi("Il database raggiunto non è quello confermato. Nessuna modifica."); return ERRORE; }
            }
        } catch (UtenteRepository.LockAccountTimeoutException e) {
            terminale.scrivi("Gestione account occupata: riprovare. Nessuna modifica.");
            return MUTEX_OCCUPATO;
        } catch (EsitoCommitIncertoException e) {
            terminale.scrivi("Esito incerto: verificare prima di ripetere.");
            return ERRORE;
        } catch (SQLException | RuntimeException e) {
            terminale.scrivi("Errore interno. Nessun dettaglio viene mostrato.");
            return ERRORE;
        }
    }
}
