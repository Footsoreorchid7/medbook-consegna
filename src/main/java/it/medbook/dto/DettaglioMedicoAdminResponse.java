package it.medbook.dto;

public record DettaglioMedicoAdminResponse(MedicoResponse medico, java.util.List<PrestazioneResponse> prestazioniAutorizzate) { }
