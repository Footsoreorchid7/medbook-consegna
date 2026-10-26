package it.medbook.dto;

import it.medbook.model.RuoloGestionale;

/** Nessun hash, indirizzo email o dato personale di profilo. I profili sono indipendenti dal ruolo. */
public record UtenteAutenticatoResponse(Integer idUtente, String nome, String cognome,
                                      boolean paziente, boolean medico, RuoloGestionale ruoloGestionale) { }
