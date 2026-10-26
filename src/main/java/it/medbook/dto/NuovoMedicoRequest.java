package it.medbook.dto;

/* Nuovo account senza ruolo gestionale con nuovo profilo medico. */
public record NuovoMedicoRequest(String nome, String cognome, String email, String password,
                                 String specializzazione, String numeroIscrizioneOrdine) { }
