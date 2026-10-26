package it.medbook.service;

import it.medbook.config.ConnectionFactory;
import it.medbook.config.TestDatabaseConnections;
import it.medbook.dto.*;
import it.medbook.repository.MedicoRepository;
import it.medbook.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/* Validazione e normalizzazione prima di ogni chiamata repository, senza database. */
class MedicoServiceTest {
    static final String PASSWORD = " password medico ";
    static final String EMOJI = "😀";

    static class Repo extends MedicoRepository {
        int calls; final List<Object> args = new ArrayList<>();
        Repo() { super(new ConnectionFactory(TestDatabaseConnections.simulatedConfig()) {
            @Override public java.sql.Connection getConnection() { throw new AssertionError("nessun JDBC nei test del service"); } }); }
        @Override public MedicoResponse cambiaStatoMedico(int a, int m, String stato) { return reply(a, m, stato); }
        MedicoResponse reply(Object... values) { calls++; args.clear(); args.addAll(Arrays.asList(values)); return new MedicoResponse(9, "N", "C", "e", "S", "O", "ATTIVO", false, null); }
        @Override public MedicoResponse crea(int a, String n, String c, String e, String h, String s, String o) { return reply(a, n, c, e, h, s, o); }
        @Override public MedicoResponse collega(int a, int u, String s, String o) { return reply(a, u, s, o); }
        @Override public MedicoResponse aggiornaDatiProfessionali(int a, int m, String s, String o) { return reply(a, m, s, o); }
        @Override public MedicoResponse aggiornaDatiPersonali(int a, int m, String n, String c, String e) { return reply(a, m, n, c, e); }
    }
    final Repo repo = new Repo();
    final PasswordHasher hasher = new PasswordHasher();
    final MedicoService service = new MedicoService(repo, hasher);

    static NuovoMedicoRequest nuovo(String nome, String cognome, String email, String password, String spec, String numero) {
        return new NuovoMedicoRequest(nome, cognome, email, password, spec, numero);
    }
    static NuovoMedicoRequest valido() { return nuovo(" Anna ", " Rossi ", " Anna.Rossi@Example.invalid ", PASSWORD, " Cardiologia ", " ORD-1 "); }

    @Test void createNormalizesLikeUc26AndHashesWithoutTrim() throws Exception {
        service.crea(7, valido());
        assertEquals(List.of(7, "Anna", "Rossi", "anna.rossi@example.invalid"), repo.args.subList(0, 4));
        assertEquals(List.of("Cardiologia", "ORD-1"), repo.args.subList(5, 7));
        String hash = (String) repo.args.get(4);
        assertTrue(hasher.verify(PASSWORD.toCharArray(), hash)); assertFalse(hasher.verify(PASSWORD.strip().toCharArray(), hash));
        assertFalse(hash.contains(PASSWORD.strip()));
    }

    @Test void passwordIsTwelveToTenTwentyFourJavaChars() throws Exception {
        for (String ok : List.of("a".repeat(12), "a".repeat(1024), EMOJI.repeat(6), "            ")) {
            var r = valido(); service.crea(7, nuovo(r.nome(), r.cognome(), r.email(), ok, r.specializzazione(), r.numeroIscrizioneOrdine()));
            assertTrue(hasher.verify(ok.toCharArray(), (String) repo.args.get(4)));
        }
        int calls = repo.calls;
        for (String bad : Arrays.asList("a".repeat(11), "a".repeat(1025), "a".repeat(11) + "\uD800", "\uDC00" + "a".repeat(11), "", null)) {
            var r = valido();
            assertThrows(IllegalArgumentException.class, () -> service.crea(7, nuovo(r.nome(), r.cognome(), r.email(), bad, r.specializzazione(), r.numeroIscrizioneOrdine())));
        }
        assertEquals(calls, repo.calls, "nessuna chiamata al repository");
    }

    @Test void everyFieldIsValidatedBeforeTheRepository() throws Exception {
        /* Limiti in code point dopo strip: nome/cognome 100, specializzazione 150, iscrizione 50. */
        // strip rimuove anche U+001C–U+001F agli estremi; i controlli interni restano rifiutati.
        var r = valido();
        service.crea(7, nuovo(EMOJI.repeat(100), "c".repeat(100), r.email(), PASSWORD, EMOJI.repeat(150), "n".repeat(50)));
        assertEquals(1, repo.calls);
        List<NuovoMedicoRequest> invalidi = List.of(
                nuovo("n".repeat(101), "C", r.email(), PASSWORD, "S", "O"), nuovo("N", "c".repeat(101), r.email(), PASSWORD, "S", "O"),
                nuovo("N", "C", r.email(), PASSWORD, "s".repeat(151), "O"), nuovo("N", "C", r.email(), PASSWORD, "S", "o".repeat(51)),
                nuovo("  ", "C", r.email(), PASSWORD, "S", "O"), nuovo("N", "C", r.email(), PASSWORD, "\t", "O"),
                nuovo("N", "C", r.email(), PASSWORD, "S", " "), nuovo("N\u0007", "C", r.email(), PASSWORD, "S", "O"),
                nuovo("N", "C", r.email(), PASSWORD, "Spec\u0000", "O"), nuovo("N", "C", r.email(), PASSWORD, "S", "O\u001FX"),
                nuovo("\uD800", "C", r.email(), PASSWORD, "S", "O"), nuovo("N", "C", r.email(), PASSWORD, "\uDC00", "O"),
                nuovo("N", "C", "senza-chiocciola", PASSWORD, "S", "O"), nuovo("N", "C", ".a@example.invalid", PASSWORD, "S", "O"),
                nuovo("N", "C", "a..b@example.invalid", PASSWORD, "S", "O"), nuovo("N", "C", "a".repeat(65) + "@example.invalid", PASSWORD, "S", "O"),
                nuovo("N", "C", "à@example.invalid", PASSWORD, "S", "O"), nuovo(null, "C", r.email(), PASSWORD, "S", "O"),
                nuovo("N", "C", r.email(), PASSWORD, null, "O"), nuovo("N", "C", r.email(), PASSWORD, "S", null));
        for (var request : invalidi) { assertThrows(IllegalArgumentException.class, () -> service.crea(7, request), request.toString()); }
        for (Integer id : Arrays.asList(null, 0, -1)) { assertThrows(IllegalArgumentException.class, () -> service.crea(id, valido())); }
        assertThrows(IllegalArgumentException.class, () -> service.crea(7, null));
        assertEquals(1, repo.calls, "nessuna chiamata al repository");
    }

    @Test void linkAndUpdatesValidateAndNormalize() throws Exception {
        service.collega(7, 9, new CollegamentoMedicoRequest(" Neurologia ", " ORD-9 "));
        assertEquals(List.of(7, 9, "Neurologia", "ORD-9"), repo.args);
        service.aggiornaDatiProfessionali(7, 9, new DatiProfessionaliMedicoRequest(" Pediatria ", " ORD-10 "));
        assertEquals(List.of(7, 9, "Pediatria", "ORD-10"), repo.args);
        service.aggiornaDatiPersonali(7, 9, new DatiPersonaliMedicoRequest(" Luca ", " Bianchi ", " Luca@Example.invalid "));
        assertEquals(List.of(7, 9, "Luca", "Bianchi", "luca@example.invalid"), repo.args);
        int calls = repo.calls;
        assertThrows(IllegalArgumentException.class, () -> service.collega(7, 0, new CollegamentoMedicoRequest("S", "O")));
        assertThrows(IllegalArgumentException.class, () -> service.collega(null, 9, new CollegamentoMedicoRequest("S", "O")));
        assertThrows(IllegalArgumentException.class, () -> service.collega(7, 9, new CollegamentoMedicoRequest("s".repeat(151), "O")));
        assertThrows(IllegalArgumentException.class, () -> service.collega(7, 9, null));
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiProfessionali(7, 9, new DatiProfessionaliMedicoRequest("S", "o".repeat(51))));
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiProfessionali(7, -1, new DatiProfessionaliMedicoRequest("S", "O")));
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiPersonali(7, 9, new DatiPersonaliMedicoRequest("N", "C", "non-valida")));
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiPersonali(7, 9, new DatiPersonaliMedicoRequest("n".repeat(101), "C", "a@example.invalid")));
        assertThrows(IllegalArgumentException.class, () -> service.aggiornaDatiPersonali(7, 9, null));
        assertEquals(calls, repo.calls, "nessuna chiamata al repository");
    }
    @Test void stateIsExactAndValidatedBeforeSingleDelegation() throws Exception {
        for (String stato : List.of("ATTIVO", "INATTIVO")) {
            int before = repo.calls; service.cambiaStatoMedico(7, 9, stato);
            assertEquals(before + 1, repo.calls); assertEquals(List.of(7, 9, stato), repo.args);
        }
        int before = repo.calls;
        for (String stato : Arrays.asList(null, "", "attivo", " ATTIVO", "INATTIVO ", "UNKNOWN")) {
            assertThrows(IllegalArgumentException.class, () -> service.cambiaStatoMedico(7, 9, stato));
        }
        for (Integer id : Arrays.asList(null, 0, -1)) {
            assertThrows(IllegalArgumentException.class, () -> service.cambiaStatoMedico(id, 9, "ATTIVO"));
            assertThrows(IllegalArgumentException.class, () -> service.cambiaStatoMedico(7, id, "ATTIVO"));
        }
        assertEquals(before, repo.calls);
    }
    @Test void stateChangePropagatesRepositoryConflictWithoutRetry() {
        var error=new it.medbook.exception.ConflittoMedicoException("synthetic conflict");
        var failing=new Repo() {
            @Override public MedicoResponse cambiaStatoMedico(int a,int m,String stato) {calls++;throw error;}
        };
        assertSame(error,assertThrows(it.medbook.exception.ConflittoMedicoException.class,
                ()->new MedicoService(failing,hasher).cambiaStatoMedico(7,9,"INATTIVO")));
        assertEquals(1,failing.calls);
    }
}
