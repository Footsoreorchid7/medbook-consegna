package it.medbook.dto;

public record AccountInternoResponse(Integer idUtente, String nome, String cognome, String email, it.medbook.model.RuoloGestionale ruoloGestionale, boolean paziente, boolean medico, String statoAccesso) { }
