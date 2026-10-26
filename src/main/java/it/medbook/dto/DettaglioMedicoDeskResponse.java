package it.medbook.dto;

public record DettaglioMedicoDeskResponse(MedicoDeskResponse medico, java.util.List<PrestazioneResponse> prestazioniAutorizzate, boolean paziente) { }
