package it.medbook.dto;
import java.util.List;
public record ElencoPazientiDeskResponse(List<PazienteDeskResponse> risultati, Integer prossimoDopoId) { }
