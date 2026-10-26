package it.medbook.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/*
 * Fixture anomala non creabile dalle API, applicata solo a catalogo dedicato sopra T0. Quanto
 * creabile passa dalle API. Seguono solo tre UPDATE controllati, con ID appena ricevuti, valore
 * precedente verificato e conteggio uno: ruolo desk e admin su due medici, effettivo NULL su una
 * confermata. Factory runtime verificata e soli privilegi DML; login degli account dopo gli
 * aggiornamenti.
 */
public final class E2EFixtureLegacy {
    public static final LocalDateTime INIZIO_S15 = LocalDateTime.parse("2031-02-05T12:00");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /* I due client aggiuntivi vengono chiusi da close. */
    public record Legacy(E2EFixtureClinica.Attore medicoDesk, E2EFixtureClinica.Attore medicoAmministratore,
                         int slotS15, int prenotazioneNull) implements AutoCloseable {
        @Override public void close() { medicoDesk.client().close(); medicoAmministratore.client().close(); }
    }

    private E2EFixtureLegacy() { }

    public static Legacy applica(E2EFixtureClinica f) throws Exception {
        if (f.fase() != E2EFixtureClinica.Fase.T0) { throw new IllegalStateException("F-LG si applica a T0."); }
        E2EFixtureClinica.Attore ad = f.attore("AD"), dk = f.attore("DK"), mt = f.attore("MT"), p1 = f.attore("P1");
        E2EManifest m = f.manifest();

        String[] email = {m.nuovaEmail("legacydesk"), m.nuovaEmail("legacyadmin")};
        String[] password = {"Fixture-LMD-" + esadecimale(), "Fixture-LAM-" + esadecimale()};
        int[] id = new int[2];
        for (int i = 0; i < 2; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nome", i == 0 ? "Legacy MD" : "Legacy AM"); body.put("cognome", "Fixture"); body.put("email", email[i]);
            body.put("password", password[i]); body.put("specializzazione", "Medicina generale");
            body.put("numeroIscrizioneOrdine", m.nuovoIdentificativo("ordine"));
            id[i] = json(ad, "POST", "/api/medici", body, 201).get("idUtente").intValue();
        }
        int s15 = json(mt, "POST", "/api/disponibilita", Map.of("idMedico", mt.id(), "dataOraInizio", INIZIO_S15.toString(),
                "dataOraFine", INIZIO_S15.plusHours(1).toString()), 201).get(0).get("idDisponibilita").intValue();
        json(dk, "PUT", "/api/disponibilita/" + s15 + "/configurazione",
                Map.of("idPrestazione", f.prestazione("Q1"), "idStanza", f.stanza("S1")), 200);
        int prenotazione = json(p1, "POST", "/api/prenotazioni", Map.of("idPaziente", p1.id(), "idDisponibilita", s15), 201)
                .get("idPrenotazione").intValue();
        json(dk, "POST", "/api/prenotazioni/" + prenotazione + "/conferma", Map.of("idOperatore", dk.id()), 200);

        /* Sole scritture SQL delle fixture cliniche: una riga per volta, valore precedente verificato. */
        try (Connection c = f.ambiente().catalogo().factory().getConnection()) {
            aggiorna(c, "UPDATE utente SET ruolo_gestionale = 'OPERATORE_DESK' WHERE id_utente = ? AND ruolo_gestionale IS NULL "
                    + "AND EXISTS (SELECT 1 FROM medico WHERE medico.id_utente = utente.id_utente)", id[0]);
            aggiorna(c, "UPDATE utente SET ruolo_gestionale = 'AMMINISTRATORE' WHERE id_utente = ? AND ruolo_gestionale IS NULL "
                    + "AND EXISTS (SELECT 1 FROM medico WHERE medico.id_utente = utente.id_utente)", id[1]);
            aggiorna(c, "UPDATE prenotazione SET id_medico_effettivo = NULL WHERE id_prenotazione = ? AND stato = 'CONFERMATA' "
                    + "AND id_medico_effettivo = ?", prenotazione, mt.id());
        }
        E2EClient md = f.accessi().accedi(email[0], password[0]);
        E2EClient am;
        try { am = f.accessi().accedi(email[1], password[1]); }
        catch (Exception | Error e) { md.close(); throw e; }
        return new Legacy(new E2EFixtureClinica.Attore("L-MD", id[0], email[0], password[0], md),
                new E2EFixtureClinica.Attore("L-AM", id[1], email[1], password[1], am), s15, prenotazione);
    }

    private static String esadecimale() {
        byte[] b = new byte[8]; RANDOM.nextBytes(b); return java.util.HexFormat.of().formatHex(b);
    }

    private static void aggiorna(Connection c, String sql, Object... parametri) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parametri.length; i++) { s.setObject(i + 1, parametri[i]); }
            if (s.executeUpdate() != 1) { throw new SQLException("Aggiornamento legacy non applicato a esattamente una riga."); }
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode json(E2EFixtureClinica.Attore a, String metodo, String path,
                                                                Object body, int status) throws Exception {
        E2EClient.Risposta r = a.client().invia(metodo, path, MAPPER.writeValueAsString(Objects.requireNonNull(body)), Map.of());
        if (r.status() != status) {
            throw new IllegalStateException("F-LG: " + a.codice() + " " + metodo + " " + path + " -> HTTP " + r.status() + ": " + r.testo());
        }
        return r.json();
    }
}
