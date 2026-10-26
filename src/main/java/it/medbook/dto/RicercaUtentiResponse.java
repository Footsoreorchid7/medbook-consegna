package it.medbook.dto;

import java.util.List;

/* Pagina per chiave: prossimoDopoId è l'ultimo ID della pagina se esistono altri risultati, altrimenti null. */
public record RicercaUtentiResponse(List<UtenteAmministrativoResponse> risultati, Integer prossimoDopoId) {
    public RicercaUtentiResponse { risultati = List.copyOf(risultati); }
}
