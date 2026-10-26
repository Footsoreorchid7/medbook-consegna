package it.medbook.dto;

/*
 * Proiezione amministrativa con i soli sette campi previsti. Codice fiscale e ruolo null se
 * assenti; nessun profilo, dato clinico, generazione, hash o metadato di modifica.
 */
public record UtenteAmministrativoResponse(Integer idUtente, String nome, String cognome, String codiceFiscale,
                                           String email, it.medbook.model.RuoloGestionale ruoloGestionale,
                                           String statoAccesso) { }
