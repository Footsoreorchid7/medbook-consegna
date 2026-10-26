package it.medbook.e2e;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/*
 * Identificativi sintetici registrati prima della richiesta, anche in caso di risposta persa. Il
 * manifest accetta e riconcilia solo valori generati da sé. Non elimina righe: la pulizia riguarda
 * l'intero catalogo esclusivo.
 */
public final class E2EManifest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Set<String> email = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> codiciFiscali = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> identificativi = new LinkedHashSet<>();

    /** Nuova email sintetica, registrata immediatamente. */
    public String nuovaEmail(String ruolo) {
        if (!ruolo.matches("[a-z]{1,12}")) { throw new IllegalArgumentException("Ruolo di fixture non valido."); }
        String valore = "e2e-" + run + "-" + ruolo + "-" + UUID.randomUUID() + "@example.invalid";
        email.add(valore);
        return valore;
    }

    /* Nuovo codice fiscale sintetico registrato subito nel manifest. */
    public String nuovoCodiceFiscale() {
        String valore;
        do {
            int n = RANDOM.nextInt(26 * 26 * 26);
            String first = "ZZZ" + (char) ('A' + n / 676 % 26) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26) + "90A01A000";
            int[] odd = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};
            int sum = 0;
            for (int i = 0; i < 15; i++) {
                char c = first.charAt(i); int value = Character.isDigit(c) ? c - '0' : c - 'A';
                sum += i % 2 == 0 ? odd[value] : value;
            }
            valore = first + (char) ('A' + sum % 26);
        } while (!codiciFiscali.add(valore));
        return valore;
    }

    /* Nuova chiave sintetica univoca per risorsa non utente, registrata prima dell'invio. */
    public String nuovoIdentificativo(String tipo) {
        if (!tipo.matches("[A-Za-z0-9]{1,12}")) { throw new IllegalArgumentException("Tipo di identificativo non valido."); }
        String valore;
        synchronized (identificativi) {
            valore = "E2E-" + run + "-" + tipo + "-" + (identificativi.size() + 1);
            identificativi.add(valore);
        }
        return valore;
    }

    public Set<String> identificativi() { synchronized (identificativi) { return Set.copyOf(identificativi); } }

    public Set<String> email() { return Set.copyOf(email); }

    public boolean appartiene(String emailFixture) { return email.contains(emailFixture); }

    /* ID utente dell'email del manifest, oppure null; rifiuta identificativi estranei. */
    public Integer idUtente(E2ECatalogo catalogo, String emailFixture) throws SQLException {
        if (!appartiene(emailFixture)) { throw new IllegalArgumentException("Identificativo estraneo al manifest."); }
        try (Connection c = catalogo.factory().getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id_utente FROM utente WHERE email = ?")) {
            s.setString(1, emailFixture);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : null; }
        }
    }

    /* Riconcilia quali email del manifest esistono anche dopo risposte perse. */
    public Map<String, Integer> riconcilia(E2ECatalogo catalogo) throws SQLException {
        Map<String, Integer> esistenti = new LinkedHashMap<>();
        for (String e : Set.copyOf(email)) {
            Integer id = idUtente(catalogo, e);
            if (id != null) { esistenti.put(e, id); }
        }
        return esistenti;
    }
}
