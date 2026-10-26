package it.medbook.dto;

/** Administrative desk projection: no account, credential or patient-profile data. */
public record MedicoDeskResponse(Integer idUtente, String nome, String cognome,
                                 String specializzazione, String numeroIscrizioneOrdine, String stato) { }
