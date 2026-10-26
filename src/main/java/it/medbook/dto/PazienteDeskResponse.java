package it.medbook.dto;
/** Identificazione essenziale; nessun dato clinico, fiscale, recapito telefonico o hash. */
public record PazienteDeskResponse(int idPaziente, String nome, String cognome, String email,
                                  boolean medico, boolean gestionale, String statoAccesso) { }
