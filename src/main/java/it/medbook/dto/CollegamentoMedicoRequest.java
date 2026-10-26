package it.medbook.dto;

/* Profilo medico su account esistente senza ruolo gestionale; ID utente come parametro separato. */
public record CollegamentoMedicoRequest(String specializzazione, String numeroIscrizioneOrdine) { }
