package it.medbook.service;

import it.medbook.repository.UtenteRepository.FiltriRicerca;
import it.medbook.security.CodiceFiscaleValidator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parametri query della ricerca utenti; ogni violazione genera IllegalArgumentException, mappata a 400. */
final class ValidazioneRicercaUtenti {
    static final Set<String> FILTRI = Set.of("idUtente", "email", "cognome", "codiceFiscale", "ruolo", "statoAccesso");
    static final Set<String> PAGINAZIONE = Set.of("dopoId", "limite");
    static final int LIMITE_PREDEFINITO = 20, LIMITE_MASSIMO = 50;

    private ValidazioneRicercaUtenti() { }

    /* Da parametri decodificati, non vuoti e unici garantiti dal controller a filtri validati. */
    static FiltriRicerca filtri(Map<String, String> parametri) {
        if (parametri == null) { throw invalida(); }
        boolean filtro = false;
        for (Map.Entry<String, String> p : parametri.entrySet()) {
            if (p.getValue() == null || p.getValue().isEmpty()) { throw invalida(); }
            if (FILTRI.contains(p.getKey())) { filtro = true; }
            else if (!PAGINAZIONE.contains(p.getKey())) { throw invalida(); }
        }
        if (!filtro) { throw invalida(); } // no unfiltered listing
        String id = parametri.get("idUtente"), ruolo = parametri.get("ruolo"), stato = parametri.get("statoAccesso");
        String dopoId = parametri.get("dopoId"), limite = parametri.get("limite"), cf = parametri.get("codiceFiscale");
        if (ruolo != null && !Set.of("AMMINISTRATORE", "OPERATORE_DESK", "NESSUNO").contains(ruolo)) { throw invalida(); }
        if (stato != null && !Set.of("ATTIVO", "DISABILITATO").contains(stato)) { throw invalida(); }
        return new FiltriRicerca(
                id == null ? null : intero(id, "[1-9][0-9]{0,9}"),
                testo(parametri.get("email"), 3, 255, true),
                testo(parametri.get("cognome"), 2, 100, false),
                cf == null ? null : CodiceFiscaleValidator.normalizzaEValida(cf),
                ruolo, stato,
                dopoId == null ? 0 : intero(dopoId, "0|[1-9][0-9]{0,9}"),
                limite == null ? LIMITE_PREDEFINITO : limite(limite));
    }

    private static int limite(String raw) {
        int value = intero(raw, "[1-9][0-9]?");
        if (value > LIMITE_MASSIMO) { throw invalida(); }
        return value;
    }
    /* Decimale canonico senza segno né zeri iniziali, entro l'intervallo Integer. */
    private static int intero(String raw, String forma) {
        if (!raw.matches(forma)) { throw invalida(); }
        long value = Long.parseLong(raw);
        if (value > Integer.MAX_VALUE) { throw invalida(); }
        return (int) value;
    }
    /** Prefix filter: strip, optional lower case (Locale.ROOT), length in code points, no control characters. */
    private static String testo(String raw, int minimo, int massimo, boolean minuscolo) {
        if (raw == null) { return null; }
        String value = raw.strip();
        if (minuscolo) { value = value.toLowerCase(Locale.ROOT); }
        int length = value.codePointCount(0, value.length());
        if (length < minimo || length > massimo || value.codePoints().anyMatch(Character::isISOControl)) { throw invalida(); }
        return value;
    }
    private static IllegalArgumentException invalida() { return new IllegalArgumentException("Richiesta non valida."); }
}
