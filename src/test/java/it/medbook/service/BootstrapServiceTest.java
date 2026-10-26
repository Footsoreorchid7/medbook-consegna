package it.medbook.service;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.exception.EsitoCommitIncertoException;
import it.medbook.repository.UtenteRepository;
import it.medbook.repository.UtenteRepository.BootstrapRifiutatoException;
import it.medbook.repository.UtenteRepository.BootstrapRifiutatoException.Motivo;
import it.medbook.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* Dialogo, validazione, codici di uscita e segretezza del bootstrap senza database. */
class BootstrapServiceTest {
    static final String PASSWORD = "Password-bootstrap";
    static final UtenteRepository.DestinazioneDatabase DESTINAZIONE =
            new UtenteRepository.DestinazioneDatabase("medbook_prova", "host-prova", 3306, "uuid-prova", "8.4.0");

    /* Terminale simulato: input e password ordinati; conserva gli array per verificarne la pulizia. */
    static final class Terminale implements BootstrapService.Terminale {
        final Deque<String> righe = new LinkedList<>();
        final Deque<char[]> password = new LinkedList<>();
        final List<char[]> consegnate = new ArrayList<>();
        final List<String> richieste = new ArrayList<>();
        final StringBuilder output = new StringBuilder();
        Terminale(List<String> righe, List<String> password) {
            this.righe.addAll(righe);
            for (String p : password) { this.password.add(p == null ? null : p.toCharArray()); }
        }
        @Override public String leggiRiga(String richiesta) { richieste.add(richiesta); return righe.isEmpty() ? null : righe.poll(); }
        @Override public char[] leggiPassword(String richiesta) {
            richieste.add(richiesta);
            char[] p = password.isEmpty() ? null : password.poll();
            if (p != null) { consegnate.add(p); }
            return p;
        }
        @Override public void scrivi(String messaggio) { output.append(messaggio).append('\n'); }
        boolean passwordAzzerate() { return consegnate.stream().allMatch(p -> new String(p).chars().allMatch(c -> c == 0)); }
    }
    static Terminale terminale(String nome, String cognome, String email, String p1, String p2, String conferma) {
        List<String> righe = new ArrayList<>(Arrays.asList(nome, cognome, email));
        if (conferma != null) { righe.add(conferma); }
        return new Terminale(righe, Arrays.asList(p1, p2));
    }
    static Terminale valido(String password) { return terminale(" Prima ", " Amministratrice ", " Admin@Example.INVALID ", password, password, "medbook_prova"); }

    static final class Repository extends UtenteRepository {
        boolean schema = true; long gestionali = 0; RuntimeException rifiuto; SQLException errore; boolean destinazioneFallita;
        final List<List<Object>> creazioni = new ArrayList<>();
        Repository() {
            super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
                @Override public Connection getConnection() { throw new AssertionError("Nessun JDBC nei test unitari del bootstrap."); }
            });
        }
        @Override public DestinazioneDatabase destinazione() throws SQLException {
            if (destinazioneFallita) { throw new SQLException("Communications link failure verso host-segreto:3306"); }
            return DESTINAZIONE;
        }
        @Override public boolean schemaAggiornato() { return schema; }
        @Override public long contaGestionali() { return gestionali; }
        @Override public int creaPrimoAmministratore(String nome, String cognome, String email, String hash, DestinazioneDatabase attesa) throws SQLException {
            creazioni.add(List.of(nome, cognome, email, hash, attesa));
            if (rifiuto != null) { throw rifiuto; }
            if (errore != null) { throw errore; }
            return 41;
        }
    }

    final PasswordHasher hasher = new PasswordHasher();

    @Test void createsWithValidatedDataAfterShowingTheDestinationAndTheTypedCatalog() {
        Repository repo = new Repository(); Terminale t = valido(PASSWORD);
        assertEquals(BootstrapService.CREATO, new BootstrapService(repo, hasher).esegui(t));
        assertEquals(1, repo.creazioni.size());
        List<Object> c = repo.creazioni.get(0);
        assertEquals(List.of("Prima", "Amministratrice", "admin@example.invalid"), c.subList(0, 3), "strip e email minuscola, come UC-26");
        assertTrue(hasher.verify(PASSWORD.toCharArray(), (String) c.get(3)), "hash PBKDF2 esistente");
        assertEquals(DESTINAZIONE, c.get(4), "la transazione riceve la destinazione confermata");
        for (String atteso : List.of("medbook_prova", "host-prova", "3306", "uuid-prova", "8.4.0", "id_utente 41")) {
            assertTrue(t.output.toString().contains(atteso), atteso);
        }
        assertTrue(t.output.indexOf("uuid-prova") < t.output.indexOf("Verrà creato"), "destinazione mostrata prima della conferma");
        assertFalse(t.output.toString().contains(PASSWORD)); assertFalse(t.output.toString().contains((String) c.get(3)));
        assertFalse(t.output.toString().contains("pbkdf2"));
        assertTrue(t.passwordAzzerate(), "array delle password azzerati");
        assertEquals(List.of("Nome: ", "Cognome: ", "Email: ", "Password (12-1024 caratteri): ", "Ripetere la password: ",
                "Per confermare digitare il nome del catalogo: "), t.richieste);
    }

    @Test void passwordLimitsAndUnicodeOfTheInternalPolicy() {
        Map<String, Boolean> casi = new LinkedHashMap<>();
        casi.put("x".repeat(11), false); casi.put("x".repeat(12), true); casi.put("x".repeat(1024), true); casi.put("x".repeat(1025), false);
        casi.put("abcdefghij😀", true);    // 10 + one pair = 12 Java chars
        casi.put("abcdefghi😀", false);    // 9 + one pair = 11
        casi.put("abcdefghijk\uD800", false); casi.put("\uDC00abcdefghijk", false); casi.put("abcde\uDE00\uD83Dfghij", false);
        casi.put("            ", true);              /* Dodici spazi: la password non viene trimmata. */
        casi.put("", false);
        casi.forEach((p, atteso) -> {
            Repository repo = new Repository(); Terminale t = valido(p);
            int esito = new BootstrapService(repo, hasher).esegui(t);
            assertEquals(atteso ? BootstrapService.CREATO : BootstrapService.INPUT_NON_VALIDO, esito, p.length() + " char");
            assertEquals(atteso ? 1 : 0, repo.creazioni.size());
            if (atteso) { assertTrue(hasher.verify(p.toCharArray(), (String) repo.creazioni.get(0).get(3)), "nessun trim"); }
            else { assertTrue(t.output.toString().contains("Password non valida")); assertFalse(t.richieste.contains("Per confermare digitare il nome del catalogo: ")); }
            assertTrue(t.passwordAzzerate());
        });
    }

    @Test void mismatchedOrMissingPasswordsAndInvalidDataWriteNothing() {
        List<Terminale> casi = List.of(
                terminale("Nome", "Cognome", "a@example.invalid", PASSWORD, PASSWORD + "x", "medbook_prova"),
                terminale("Nome", "Cognome", "a@example.invalid", PASSWORD, null, "medbook_prova"),
                terminale("Nome", "Cognome", "a@example.invalid", null, null, "medbook_prova"),
                terminale("", "Cognome", "a@example.invalid", PASSWORD, PASSWORD, "medbook_prova"),
                terminale("N\uD800", "Cognome", "a@example.invalid", PASSWORD, PASSWORD, "medbook_prova"),
                terminale("Nome", "c".repeat(101), "a@example.invalid", PASSWORD, PASSWORD, "medbook_prova"),
                terminale("Nome", "Co\u0007gnome", "a@example.invalid", PASSWORD, PASSWORD, "medbook_prova"),
                terminale("Nome", "Cognome", "non-email", PASSWORD, PASSWORD, "medbook_prova"),
                new Terminale(List.of(), List.of()));
        for (Terminale t : casi) {
            Repository repo = new Repository();
            assertEquals(BootstrapService.INPUT_NON_VALIDO, new BootstrapService(repo, hasher).esegui(t));
            assertTrue(repo.creazioni.isEmpty()); assertTrue(t.passwordAzzerate()); assertFalse(t.output.toString().contains(PASSWORD));
        }
    }

    @Test void confirmationMustBeTheExactCatalogName() {
        for (String conferma : Arrays.asList("", "si", "s", "MEDBOOK_PROVA", " medbook_prova", "medbook_prova ", "medbook", "uuid-prova", null)) {
            Repository repo = new Repository();
            Terminale t = terminale("Nome", "Cognome", "a@example.invalid", PASSWORD, PASSWORD, conferma);
            assertEquals(BootstrapService.INPUT_NON_VALIDO, new BootstrapService(repo, hasher).esegui(t), String.valueOf(conferma));
            assertTrue(repo.creazioni.isEmpty()); assertTrue(t.output.toString().contains("Conferma non ricevuta")); assertTrue(t.passwordAzzerate());
        }
    }

    @Test void preconditionsRefuseBeforeAskingForCredentials() {
        Repository schema = new Repository(); schema.schema = false; Terminale t1 = valido(PASSWORD);
        assertEquals(BootstrapService.SCHEMA_NON_AGGIORNATO, new BootstrapService(schema, hasher).esegui(t1));
        Repository inizializzato = new Repository(); inizializzato.gestionali = 1; Terminale t2 = valido(PASSWORD);
        assertEquals(BootstrapService.GIA_INIZIALIZZATO, new BootstrapService(inizializzato, hasher).esegui(t2));
        Repository giu = new Repository(); giu.destinazioneFallita = true; Terminale t3 = valido(PASSWORD);
        assertEquals(BootstrapService.ERRORE, new BootstrapService(giu, hasher).esegui(t3));
        assertFalse(t3.output.toString().contains("host-segreto"), "nessuna causa del driver in output");
        for (var c : List.of(schema, inizializzato, giu)) { assertTrue(c.creazioni.isEmpty()); }
        for (var t : List.of(t1, t2, t3)) { assertTrue(t.richieste.isEmpty(), "nessuna credenziale richiesta"); }
    }

    @Test void transactionOutcomesMapToTheExitCodes() {
        Map<Object, Integer> casi = new LinkedHashMap<>();
        casi.put(new BootstrapRifiutatoException(Motivo.GIA_INIZIALIZZATO), BootstrapService.GIA_INIZIALIZZATO);
        casi.put(new BootstrapRifiutatoException(Motivo.EMAIL_PRESENTE), BootstrapService.EMAIL_PRESENTE);
        casi.put(new BootstrapRifiutatoException(Motivo.SCHEMA_NON_AGGIORNATO), BootstrapService.SCHEMA_NON_AGGIORNATO);
        casi.put(new BootstrapRifiutatoException(Motivo.DESTINAZIONE_DIVERSA), BootstrapService.ERRORE);
        casi.put(new UtenteRepository.LockAccountTimeoutException(), BootstrapService.MUTEX_OCCUPATO);
        casi.put(new EsitoCommitIncertoException(new SQLException("commit fallito")), BootstrapService.ERRORE);
        casi.put(new SQLException("INSERT INTO utente ... pbkdf2-sha256$v1$segreto"), BootstrapService.ERRORE);
        casi.put(new IllegalStateException("interno"), BootstrapService.ERRORE);
        casi.forEach((errore, codice) -> {
            Repository repo = new Repository();
            if (errore instanceof SQLException s) { repo.errore = s; } else { repo.rifiuto = (RuntimeException) errore; }
            Terminale t = valido(PASSWORD);
            assertEquals(codice, new BootstrapService(repo, hasher).esegui(t), errore.getClass().getSimpleName());
            String out = t.output.toString();
            assertFalse(out.contains("INSERT") || out.contains("pbkdf2") || out.contains(PASSWORD) || out.contains("commit fallito"), out);
            if (errore instanceof EsitoCommitIncertoException) { assertTrue(out.contains("Esito incerto: verificare prima di ripetere.")); }
            assertTrue(t.passwordAzzerate());
        });
    }
}
