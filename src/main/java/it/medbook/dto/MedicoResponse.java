package it.medbook.dto;

import it.medbook.model.RuoloGestionale;

/*
 * Proiezione amministrativa medico senza password o hash. Lo stato grezzo non viene normalizzato;
 * la distinta proiezione desk omette email e ruolo gestionale.
 */
public record MedicoResponse(Integer idUtente, String nome, String cognome, String email,
                             String specializzazione, String numeroIscrizioneOrdine, String stato,
                             boolean paziente, RuoloGestionale ruoloGestionale) { }
